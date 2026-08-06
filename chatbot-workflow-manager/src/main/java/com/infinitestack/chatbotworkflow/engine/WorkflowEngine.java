package com.infinitestack.chatbotworkflow.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.infinitestack.chatbotworkflow.domain.Action;
import com.infinitestack.chatbotworkflow.domain.ConversationState;
import com.infinitestack.chatbotworkflow.domain.ConversationStatus;
import com.infinitestack.chatbotworkflow.domain.EngineResult;
import com.infinitestack.chatbotworkflow.domain.Event;
import com.infinitestack.chatbotworkflow.domain.Node;
import com.infinitestack.chatbotworkflow.domain.NodeType;
import com.infinitestack.chatbotworkflow.domain.Workflow;
import com.infinitestack.chatbotworkflow.engine.expr.ExpressionException;
import com.infinitestack.chatbotworkflow.engine.expr.Expressions;

/**
 * O motor: recebe (workflow, estado, evento) e devolve (novo estado, ações).
 *
 * Não abre conexão com nada. Banco e HTTP entram pelo {@link ActionExecutor} do
 * {@link EngineContext}, e subfluxos pelo {@link WorkflowResolver} — as duas únicas portas para o
 * mundo externo, ambas injetadas por chamada. É o que permite testar um fluxo inteiro, inclusive
 * com efeitos e subfluxos, sem subir contexto Spring.
 *
 * <pre>
 *   evento chega
 *     └─ USER_MESSAGE: grava a resposta na variável do nó INPUT atual e avança
 *   percorre nós em sequência até:
 *     ├─ um INPUT                      → WAITING_INPUT (pausa esperando o usuário)
 *     ├─ um END na raiz                → FINISHED
 *     ├─ um END dentro de subfluxo     → retorna ao chamador e continua
 *     ├─ um erro de fluxo              → ERROR
 *     └─ estourar maxSteps             → ERROR
 * </pre>
 *
 * <b>Dois tetos, dois riscos diferentes.</b> {@code maxStepsPerTurn} protege contra ciclo entre nós
 * ({@code a → b → a}); {@code maxCallDepth} protege contra recursão entre fluxos ({@code A chama B
 * que chama A}). O primeiro sozinho até barraria a recursão, mas só depois de empilhar dezenas de
 * frames com uma cópia do escopo em cada — e o erro apontaria "ciclo" quando o problema é
 * profundidade.
 */
@Component
public class WorkflowEngine {

    private final int maxStepsPerTurn;
    private final int maxCallDepth;

    public WorkflowEngine(@Value("${chatbot.engine.max-steps-per-turn:50}") int maxStepsPerTurn,
                          @Value("${chatbot.engine.max-call-depth:10}") int maxCallDepth) {
        this.maxStepsPerTurn = maxStepsPerTurn;
        this.maxCallDepth = maxCallDepth;
    }

    /** Conveniência para fluxo único sem efeitos — usada nos testes e por quem não usa subfluxo. */
    public EngineResult advance(Workflow workflow, ConversationState state, Event event) {
        return advance(workflow, state, event, EngineContext.standalone(workflow));
    }

    public EngineResult advance(Workflow rootWorkflow, ConversationState state, Event event, EngineContext ctx) {
        List<Action> actions = new ArrayList<>();

        ConversationState current;
        try {
            current = applyEvent(rootWorkflow, state, event, actions, ctx);
        } catch (FlowError e) {
            return error(state, actions, e.getMessage());
        }
        // applyEvent devolve null quando o evento não deve avançar o fluxo (ex.: mensagem recebida
        // numa conversa já encerrada) — o estado fica exatamente como estava.
        if (current == null) {
            return new EngineResult(state, actions, null);
        }

        for (int step = 0; step < maxStepsPerTurn; step++) {
            Workflow workflow;
            Node node;
            try {
                workflow = resolveWorkflow(rootWorkflow, current.currentWorkflowId(), ctx);
                node = workflow.node(current.currentNodeId());
                if (node == null) {
                    throw new FlowError("Nó '" + current.currentNodeId() + "' não existe no fluxo '"
                            + workflow.id() + "'.");
                }
                if (node.type() == null) {
                    throw new FlowError("Nó '" + node.id() + "' tem tipo inválido.");
                }

                StepOutcome outcome = execute(node, current, actions, ctx);
                current = outcome.state();
                if (outcome.stop()) {
                    return new EngineResult(current, actions, null);
                }
            } catch (FlowError e) {
                return error(current, actions, e.getMessage());
            } catch (ExpressionException e) {
                return error(current, actions, "Expressão inválida em '" + current.currentNodeId()
                        + "': " + e.getMessage());
            }
        }

        return error(current, actions,
                "Limite de " + maxStepsPerTurn + " nós por turno excedido — o fluxo provavelmente tem um ciclo.");
    }

    // ─── Evento ───────────────────────────────────────────────────────────────────

    private ConversationState applyEvent(Workflow rootWorkflow, ConversationState state, Event event,
                                         List<Action> actions, EngineContext ctx) {
        if (event.type() == Event.Type.START) {
            if (rootWorkflow.node(rootWorkflow.start()) == null) {
                throw new FlowError("Nó inicial '" + rootWorkflow.start() + "' não existe no workflow.");
            }
            // START wipes conversation data but keeps the ambient variables (is_*). They describe
            // the context the conversation runs in — which channel, which speaker — not anything
            // the flow collected, so resetting them would be wrong: the second conversation of the
            // same person on the same channel would start blind.
            return ConversationState.initial()
                    .withVariables(systemVariablesOf(state))
                    .at(rootWorkflow.id(), rootWorkflow.start())
                    .withStatus(ConversationStatus.RUNNING);
        }

        if (!state.status().acceptsUserMessage()) {
            // Conversa encerrada, em erro ou pausada: a mensagem não avança nada. Responder é
            // melhor que o silêncio — no canal real o usuário não tem como saber que acabou.
            actions.add(Action.sendMessage(state.status().isTerminal()
                    ? "Esta conversa já foi encerrada. Inicie uma nova para começar de novo."
                    : "Esta conversa está pausada no momento."));
            return null;
        }

        Workflow workflow = resolveWorkflow(rootWorkflow, state.currentWorkflowId(), ctx);
        Node inputNode = workflow.node(state.currentNodeId());
        if (inputNode == null) {
            throw new FlowError("Nó '" + state.currentNodeId() + "' não existe no fluxo '" + workflow.id() + "'.");
        }
        if (inputNode.type() != NodeType.INPUT) {
            throw new FlowError("Conversa aguardava entrada mas parou em '" + inputNode.id()
                    + "', que é do tipo " + inputNode.type() + ".");
        }

        String variable = requireConfig(inputNode, "variable");
        return state.withVariable(variable, event.text().trim())
                    .at(inputNode.next())
                    .withStatus(ConversationStatus.RUNNING);
    }

    // ─── Nós ──────────────────────────────────────────────────────────────────────

    /** @param stop true quando o turno termina neste nó (WAITING_INPUT ou FINISHED). */
    private record StepOutcome(ConversationState state, boolean stop) {}

    private StepOutcome execute(Node node, ConversationState state, List<Action> actions, EngineContext ctx) {
        return switch (node.type()) {
            case MESSAGE       -> executeMessage(node, state, actions);
            case INPUT         -> executeInput(node, state, actions);
            case SET_VARIABLE  -> executeSetVariable(node, state);
            case IF            -> executeIf(node, state);
            case END           -> executeEnd(node, state, actions, ctx);
            case CALL_WORKFLOW -> executeCallWorkflow(node, state, ctx);
            case DB_QUERY, HTTP_REQUEST, SEND_DOCUMENT -> executeEffect(node, state, actions, ctx);
        };
    }

    private StepOutcome executeMessage(Node node, ConversationState state, List<Action> actions) {
        actions.add(Action.sendMessage(
                VariableInterpolator.interpolate(requireConfig(node, "text"), state.variables())));
        return new StepOutcome(state.at(requireNext(node)), false);
    }

    /**
     * INPUT é o único nó que pausa. O prompt é opcional: muitos fluxos já perguntaram no MESSAGE
     * anterior, e emitir um texto vazio geraria uma mensagem em branco no canal.
     */
    private StepOutcome executeInput(Node node, ConversationState state, List<Action> actions) {
        requireConfig(node, "variable");
        String prompt = node.config("prompt");
        if (prompt != null) {
            actions.add(Action.sendMessage(VariableInterpolator.interpolate(prompt, state.variables())));
        }
        actions.add(Action.waitInput());
        return new StepOutcome(state.withStatus(ConversationStatus.WAITING_INPUT), true);
    }

    /**
     * Aceita as duas formas: {@code value} (texto com {@code {{var}}}) ou {@code expression}
     * (linguagem de expressões). Declarar as duas é erro — silenciosamente preferir uma faria o
     * autor depurar por que a outra "não funciona".
     */
    private StepOutcome executeSetVariable(Node node, ConversationState state) {
        String name = requireConfig(node, "variable");
        String expression = node.config("expression");
        String value;

        if (expression != null) {
            if (node.hasConfig("value")) {
                throw new FlowError("Nó '" + node.id() + "' declara 'value' e 'expression' ao mesmo tempo.");
            }
            value = Expressions.text(expression, state.variables());
        } else {
            value = VariableInterpolator.interpolate(node.config("value", ""), state.variables());
        }
        return new StepOutcome(state.withVariable(name, value).at(requireNext(node)), false);
    }

    /**
     * IF ramifica por config (then/else) em vez de {@code next}. Se o ramo escolhido não estiver
     * declarado, o resultado é erro de fluxo explícito — não um salto silencioso para o próximo nó
     * da lista, que faria um "else" esquecido passar despercebido até a conversa errada acontecer.
     *
     * Duas formas de escrever a condição: {@code expression} (linguagem completa) ou o trio
     * {@code variable}/{@code operator}/{@code value}. A segunda continua existindo porque cobre a
     * esmagadora maioria dos fluxos sem exigir que o autor aprenda sintaxe nenhuma.
     */
    private StepOutcome executeIf(Node node, ConversationState state) {
        String expression = node.config("expression");
        boolean matched;

        if (expression != null) {
            matched = Expressions.condition(expression, state.variables());
        } else {
            String variable = requireConfig(node, "variable");
            String operator = node.config("operator", "eq");
            String expected = VariableInterpolator.interpolate(node.config("value", ""), state.variables());
            matched = ConditionEvaluator.evaluate(state.variable(variable), operator, expected);
        }

        String branch = matched ? node.config("then") : node.config("else");
        if (branch == null) {
            throw new FlowError("Nó IF '" + node.id() + "' não define o ramo '"
                    + (matched ? "then" : "else") + "'.");
        }
        return new StepOutcome(state.at(branch), false);
    }

    /**
     * END encerra a conversa <b>ou</b> retorna do subfluxo, conforme a pilha.
     *
     * O mesmo nó servir para os dois casos é o que permite escrever um fluxo sem saber se ele vai
     * ser usado sozinho ou chamado por outro — que é a razão de subfluxo existir.
     */
    private StepOutcome executeEnd(Node node, ConversationState state, List<Action> actions, EngineContext ctx) {
        String text = node.config("text");
        if (text != null) {
            actions.add(Action.sendMessage(VariableInterpolator.interpolate(text, state.variables())));
        }

        ConversationState.Frame frame = state.topFrame();
        if (frame == null) {
            actions.add(Action.end());
            return new StepOutcome(state.withStatus(ConversationStatus.FINISHED), true);
        }

        // Retorno: restaura o escopo do chamador e traz de volta só o que foi declarado em 'output'.
        Map<String, String> returned = new LinkedHashMap<>();
        for (String name : frame.outputs()) {
            returned.put(name, state.variable(name));
        }
        return new StepOutcome(
                state.popFrame()
                     .withScope(frame.callerVariables())
                     .withVariables(returned)
                     .at(frame.workflowId(), frame.returnNodeId()),
                false);
    }

    /**
     * Entra num subfluxo com escopo isolado.
     *
     * <b>Por que escopo isolado e não variáveis compartilhadas.</b> Compartilhar tornaria
     * {@code input}/{@code output} decorativos e faria um subfluxo reutilizável sobrescrever, sem
     * aviso, uma variável homônima do chamador — o bug clássico de fluxo grande. Com isolamento, o
     * contrato do subfluxo é o que ele declara receber e devolver, e nada mais atravessa.
     */
    private StepOutcome executeCallWorkflow(Node node, ConversationState state, EngineContext ctx) {
        String targetId = requireConfig(node, "workflow");
        String returnNode = requireNext(node);

        if (state.depth() >= maxCallDepth) {
            throw new FlowError("Profundidade máxima de " + maxCallDepth
                    + " subfluxos excedida ao chamar '" + targetId + "' — provável recursão entre fluxos.");
        }

        Workflow target = ctx.resolver().resolve(targetId);
        if (target == null) {
            throw new FlowError("Subfluxo '" + targetId + "' não encontrado.");
        }
        if (target.node(target.start()) == null) {
            throw new FlowError("Subfluxo '" + targetId + "' tem nó inicial '" + target.start() + "' inexistente.");
        }

        // The child sees only what 'input' declares — plus the ambient is_* variables, which are
        // not caller data but conversation context. Forcing every call site to list them in
        // 'input' would be noise, and forgetting to would make a subflow that branches on the
        // channel silently take the wrong path.
        Map<String, String> childScope = new LinkedHashMap<>(systemVariablesOf(state));
        for (String name : ConfigLists.names(node.config("input", ""))) {
            childScope.put(name, state.variable(name));
        }

        ConversationState.Frame frame = new ConversationState.Frame(
                state.currentWorkflowId(), returnNode, state.variables(), ConfigLists.names(node.config("output", "")));

        return new StepOutcome(
                state.pushFrame(frame)
                     .withScope(childScope)
                     .at(target.id(), target.start()),
                false);
    }

    /**
     * Nós de efeito: delega ao executor e incorpora o que voltou.
     *
     * O executor pode devolver <b>variáveis</b> (DB_QUERY, HTTP_REQUEST) ou <b>mensagens</b>
     * (SEND_DOCUMENT, cujo produto é o arquivo em si). Mensagem não vira variável de propósito: o
     * conteúdo de um documento chega como base64 e gravá-lo no escopo o persistiria na coluna de
     * estado da conversa, sendo relido a cada mensagem seguinte.
     *
     * Falha do efeito é erro de fluxo — a conversa para. A alternativa (seguir com variáveis vazias)
     * levaria o IF seguinte a tomar o ramo do "não encontrado" quando o caso real é "o banco caiu",
     * e ninguém descobriria a diferença lendo o histórico.
     */
    private StepOutcome executeEffect(Node node, ConversationState state, List<Action> actions, EngineContext ctx) {
        ActionExecutor.Result result = ctx.executor().execute(node, state.variables());
        if (result.hasError()) {
            throw new FlowError("Nó '" + node.id() + "' (" + node.type() + ") falhou: " + result.error());
        }
        for (String message : result.messages()) {
            actions.add(Action.sendMessage(message));
        }
        actions.add(Action.effect(actionTypeFor(node.type()),
                "Nó '" + node.id() + "' executado.", result.details()));
        return new StepOutcome(state.withVariables(result.variables()).at(requireNext(node)), false);
    }

    /** SEND_DOCUMENT não tem ação própria — o que sai dele é mensagem; o registro é de rastro. */
    private Action.Type actionTypeFor(NodeType nodeType) {
        return nodeType == NodeType.SEND_DOCUMENT
                ? Action.Type.SEND_DOCUMENT
                : Action.Type.valueOf(nodeType.name());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    /** The is_* subset of a scope — the variables the runtime owns and the flow cannot write. */
    private static Map<String, String> systemVariablesOf(ConversationState state) {
        Map<String, String> ambient = new LinkedHashMap<>();
        state.variables().forEach((name, value) -> {
            if (ConversationState.isSystemVariable(name)) ambient.put(name, value);
        });
        return ambient;
    }

    private Workflow resolveWorkflow(Workflow rootWorkflow, String workflowId, EngineContext ctx) {
        if (workflowId == null || (rootWorkflow.id() != null && rootWorkflow.id().equals(workflowId))) {
            return rootWorkflow;
        }
        Workflow resolved = ctx.resolver().resolve(workflowId);
        if (resolved == null) {
            throw new FlowError("Fluxo '" + workflowId + "' não encontrado — a conversa ficou órfã "
                    + "(o fluxo foi removido enquanto ela estava em andamento?).");
        }
        return resolved;
    }

    private String requireConfig(Node node, String key) {
        String value = node.config(key);
        if (value == null) {
            throw new FlowError("Nó '" + node.id() + "' (" + node.type() + ") exige config '" + key + "'.");
        }
        return value;
    }

    private String requireNext(Node node) {
        if (node.next() == null || node.next().isBlank()) {
            throw new FlowError("Nó '" + node.id() + "' (" + node.type() + ") não define 'next'.");
        }
        return node.next();
    }

    private EngineResult error(ConversationState state, List<Action> actions, String message) {
        actions.add(Action.sendMessage("Ocorreu um erro no fluxo e a conversa foi interrompida."));
        return new EngineResult(state.withStatus(ConversationStatus.ERROR), actions, message);
    }

    /** Erro de autoria do fluxo, não do processo — sempre vira status ERROR com mensagem. */
    private static class FlowError extends RuntimeException {
        FlowError(String message) { super(message); }
    }
}
