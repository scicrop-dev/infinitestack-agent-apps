package com.infinitestack.chatbotworkflow.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.infinitestack.chatbotworkflow.domain.ConversationState;
import com.infinitestack.chatbotworkflow.domain.Node;
import com.infinitestack.chatbotworkflow.domain.NodeType;
import com.infinitestack.chatbotworkflow.domain.Workflow;
import com.infinitestack.chatbotworkflow.engine.expr.Expressions;

/**
 * Confere se um fluxo é executável <b>antes</b> de ser gravado.
 *
 * A razão de existir: o motor só descobre um {@code next} apontando para nó inexistente quando a
 * conversa já está no ar, e aí quem paga é o usuário final, no meio do atendimento. Validar na
 * gravação transforma isso em uma lista de erros na tela do autor.
 *
 * Separa <b>erros</b> (impedem gravar) de <b>avisos</b> (gravam, mas provavelmente não é o que o
 * autor queria — nó órfão, fluxo sem nenhum END).
 */
@Component
public class WorkflowValidator {

    public record Result(List<String> errors, List<String> warnings) {
        public boolean valid() { return errors.isEmpty(); }
    }

    public Result validate(Workflow workflow) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (workflow.id() == null)    errors.add("Campo 'id' é obrigatório.");
        if (workflow.name() == null)  warnings.add("Campo 'name' não informado — o fluxo aparecerá sem título na lista.");
        if (workflow.nodes().isEmpty()) {
            errors.add("O workflow não tem nenhum nó.");
            return new Result(errors, warnings);
        }

        validateNodeIdentity(workflow, errors);
        validateNodeContent(workflow, errors);
        validateStart(workflow, errors);
        validateReachability(workflow, warnings);

        return new Result(errors, warnings);
    }

    /** Ids ausentes ou duplicados quebram o índice do workflow — sem eles nada mais faz sentido. */
    private void validateNodeIdentity(Workflow workflow, List<String> errors) {
        Set<String> seen = new HashSet<>();
        int position = 0;
        for (Node node : workflow.nodes()) {
            position++;
            if (node.id() == null) {
                errors.add("Nó na posição " + position + " não tem 'id'.");
            } else if (!seen.add(node.id())) {
                errors.add("Id de nó duplicado: '" + node.id() + "'.");
            }
        }
    }

    private void validateNodeContent(Workflow workflow, List<String> errors) {
        for (Node node : workflow.nodes()) {
            String label = "Nó '" + (node.id() == null ? "?" : node.id()) + "'";

            if (node.type() == null) {
                errors.add(label + " tem 'type' ausente ou inválido. Tipos aceitos: MESSAGE, INPUT, IF, SET_VARIABLE, END, CALL_WORKFLOW, DB_QUERY, HTTP_REQUEST, SEND_DOCUMENT.");
                continue;
            }
            switch (node.type()) {
                case MESSAGE -> {
                    requireConfig(node, "text", label, errors);
                    requireNext(workflow, node, label, errors);
                }
                case INPUT -> {
                    requireConfig(node, "variable", label, errors);
                    rejectReservedTarget(node.config("variable"), label, errors);
                    requireNext(workflow, node, label, errors);
                }
                case SET_VARIABLE -> validateSetVariable(workflow, node, label, errors);
                case IF -> validateIf(workflow, node, label, errors);
                case CALL_WORKFLOW -> {
                    requireConfig(node, "workflow", label, errors);
                    requireNext(workflow, node, label, errors);
                    // 'output' writes into the caller's scope on return — same reservation applies.
                    rejectReservedTargets(ConfigLists.names(node.config("output", "")), label, errors);
                    // A existência do subfluxo não é checada aqui: o validador é puro e não conhece
                    // repositório. Quem confere é WorkflowService na gravação, como aviso — a ordem
                    // em que os fluxos são criados não pode impedir de salvar o pai antes do filho.
                }
                case DB_QUERY -> validateDbQuery(workflow, node, label, errors);
                case HTTP_REQUEST -> validateHttpRequest(workflow, node, label, errors);
                case SEND_DOCUMENT -> validateSendDocument(workflow, node, label, errors);
                case END -> {
                    // END não precisa de nada: 'text' é opcional e 'next' não se aplica.
                }
                default -> { }
            }
        }
    }

    /** Duas formas de condição: 'expression' (linguagem completa) ou variable/operator/value. */
    private void validateIf(Workflow workflow, Node node, String label, List<String> errors) {
        String expression = node.config("expression");
        if (expression != null) {
            if (node.hasConfig("variable")) {
                errors.add(label + " (IF) declara 'expression' e 'variable' ao mesmo tempo — escolha uma forma.");
            }
            String syntaxError = Expressions.validate(expression);
            if (syntaxError != null) {
                errors.add(label + " (IF): expressão inválida — " + syntaxError);
            }
        } else {
            requireConfig(node, "variable", label, errors);
            String operator = node.config("operator", "eq");
            if (!ConditionEvaluator.OPERATORS.contains(operator.toLowerCase())) {
                errors.add(label + ": operador '" + operator + "' inválido. Aceitos: "
                        + String.join(", ", new java.util.TreeSet<>(ConditionEvaluator.OPERATORS)) + ".");
            }
        }
        requireBranch(workflow, node, "then", label, errors);
        requireBranch(workflow, node, "else", label, errors);
    }

    private void validateSetVariable(Workflow workflow, Node node, String label, List<String> errors) {
        requireConfig(node, "variable", label, errors);
        rejectReservedTarget(node.config("variable"), label, errors);
        requireNext(workflow, node, label, errors);

        String expression = node.config("expression");
        if (expression != null) {
            if (node.hasConfig("value")) {
                errors.add(label + " (SET_VARIABLE) declara 'value' e 'expression' ao mesmo tempo — escolha uma forma.");
            }
            String syntaxError = Expressions.validate(expression);
            if (syntaxError != null) {
                errors.add(label + " (SET_VARIABLE): expressão inválida — " + syntaxError);
            }
        }
    }

    /**
     * Roda o mesmo {@link SqlGuard} do executor. Descobrir na gravação que o SQL é rejeitado é o
     * ponto: descobrir na conversa significaria o atendimento parar na frente do usuário.
     */
    private void validateDbQuery(Workflow workflow, Node node, String label, List<String> errors) {
        requireNext(workflow, node, label, errors);
        String sql = node.config("sql");
        if (sql == null) {
            errors.add(label + " (DB_QUERY) exige config 'sql'.");
            return;
        }
        try {
            SqlGuard.check(sql);
        } catch (IllegalArgumentException e) {
            errors.add(label + " (DB_QUERY): " + e.getMessage());
            return;
        }
        rejectReservedTargets(ConfigLists.mapping(node.config("output", "")).values(), label, errors);
        rejectReservedTarget(node.config("countInto"), label, errors);

        List<String> declared = ConfigLists.names(node.config("params", ""));
        for (String required : SqlGuard.namedParameters(sql)) {
            if (!declared.contains(required)) {
                errors.add(label + " (DB_QUERY): SQL usa :" + required + " mas '" + required
                        + "' não está declarado em 'params'.");
            }
        }
    }

    /**
     * Only checks the shape here — {@code file} may be any path, so there is nothing to reject
     * statically.
     *
     * What actually protects the filesystem runs at execution time in {@code DocumentLibrary},
     * where the interpolated values are known: the template is authored and may point anywhere,
     * but a value substituted into it cannot introduce a separator or {@code ..}. That distinction
     * is impossible to make from the definition alone.
     */
    private void validateSendDocument(Workflow workflow, Node node, String label, List<String> errors) {
        requireNext(workflow, node, label, errors);
        requireConfig(node, "file", label, errors);
    }

    private void validateHttpRequest(Workflow workflow, Node node, String label, List<String> errors) {
        requireNext(workflow, node, label, errors);
        requireConfig(node, "url", label, errors);

        String method = node.config("method", "GET").trim().toUpperCase();
        if (!method.equals("GET") && !method.equals("POST")) {
            errors.add(label + " (HTTP_REQUEST): method deve ser GET ou POST, não '" + method + "'.");
        }
        String url = node.config("url", "");
        if (!url.isBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
            errors.add(label + " (HTTP_REQUEST): url deve começar com http:// ou https://.");
        }
        rejectReservedTargets(ConfigLists.mapping(node.config("output", "")).values(), label, errors);
        rejectReservedTarget(node.config("statusInto"), label, errors);
    }

    private void validateStart(Workflow workflow, List<String> errors) {
        if (workflow.start() == null) {
            errors.add("Campo 'start' é obrigatório.");
        } else if (workflow.node(workflow.start()) == null) {
            errors.add("Nó inicial '" + workflow.start() + "' não existe entre os nós declarados.");
        }
    }

    /**
     * Percorre o grafo a partir do start. Nó inalcançável não impede a execução do fluxo, mas
     * quase sempre é rastro de uma edição pela metade — vira aviso, não erro.
     */
    private void validateReachability(Workflow workflow, List<String> warnings) {
        if (workflow.start() == null || workflow.node(workflow.start()) == null) return;

        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(workflow.start());

        boolean hasEnd = false;
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            if (!reachable.add(nodeId)) continue;

            Node node = workflow.node(nodeId);
            if (node == null || node.type() == null) continue;
            if (node.type() == NodeType.END) hasEnd = true;

            if (node.type() == NodeType.IF) {
                enqueue(queue, node.config("then"));
                enqueue(queue, node.config("else"));
            } else {
                enqueue(queue, node.next());
            }
        }

        for (Node node : workflow.nodes()) {
            if (node.id() != null && !reachable.contains(node.id())) {
                warnings.add("Nó '" + node.id() + "' não é alcançável a partir de '" + workflow.start() + "'.");
            }
        }
        if (!hasEnd) {
            warnings.add("Nenhum nó END é alcançável — as conversas deste fluxo nunca chegam a FINISHED.");
        }
    }

    /**
     * Blocks a node from writing to a runtime-owned variable.
     *
     * These carry the conversation context (channel, speaker). Letting a flow overwrite one would
     * not fail anywhere — it would just make every branch that reads it take the wrong path, and
     * the cause would be invisible in the conversation history.
     */
    private void rejectReservedTarget(String name, String label, List<String> errors) {
        if (ConversationState.isSystemVariable(name)) {
            errors.add(label + ": '" + name + "' é uma variável do sistema (prefixo '"
                    + ConversationState.SYSTEM_PREFIX + "') e não pode ser escrita pelo fluxo.");
        }
    }

    private void rejectReservedTargets(Iterable<String> names, String label, List<String> errors) {
        for (String name : names) {
            rejectReservedTarget(name, label, errors);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private void enqueue(Deque<String> queue, String nodeId) {
        if (nodeId != null && !nodeId.isBlank()) queue.add(nodeId);
    }

    private void requireConfig(Node node, String key, String label, List<String> errors) {
        if (!node.hasConfig(key)) {
            errors.add(label + " (" + node.type() + ") exige config '" + key + "'.");
        }
    }

    private void requireNext(Workflow workflow, Node node, String label, List<String> errors) {
        if (node.next() == null || node.next().isBlank()) {
            errors.add(label + " (" + node.type() + ") não define 'next'.");
        } else if (workflow.node(node.next()) == null) {
            errors.add(label + ": 'next' aponta para '" + node.next() + "', que não existe.");
        }
    }

    private void requireBranch(Workflow workflow, Node node, String branch, String label, List<String> errors) {
        String target = node.config(branch);
        if (target == null) {
            errors.add(label + " (IF) não define o ramo '" + branch + "'.");
        } else if (workflow.node(target) == null) {
            errors.add(label + ": ramo '" + branch + "' aponta para '" + target + "', que não existe.");
        }
    }
}
