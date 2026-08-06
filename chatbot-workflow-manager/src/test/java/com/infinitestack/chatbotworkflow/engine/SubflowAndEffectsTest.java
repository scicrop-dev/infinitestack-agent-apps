package com.infinitestack.chatbotworkflow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.infinitestack.chatbotworkflow.domain.Action;
import com.infinitestack.chatbotworkflow.domain.ConversationState;
import com.infinitestack.chatbotworkflow.domain.ConversationStatus;
import com.infinitestack.chatbotworkflow.domain.EngineResult;
import com.infinitestack.chatbotworkflow.domain.Event;
import com.infinitestack.chatbotworkflow.domain.Node;
import com.infinitestack.chatbotworkflow.domain.NodeType;
import com.infinitestack.chatbotworkflow.domain.Workflow;

/**
 * Fases 3 e 5: efeitos via {@link ActionExecutor} e subfluxos via {@link WorkflowResolver}.
 *
 * Nenhum banco, nenhuma rede: as duas portas para o mundo externo são parâmetros da chamada, então
 * o teste passa um mapa e um lambda. Era exatamente para isso que o motor foi mantido puro.
 */
class SubflowAndEffectsTest {

    private final WorkflowEngine engine = new WorkflowEngine(50, 3);

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private static Node message(String id, String next, String text) {
        return new Node(id, NodeType.MESSAGE, next, Map.of("text", text));
    }

    private static Node end(String id, String text) {
        return new Node(id, NodeType.END, null, text == null ? Map.of() : Map.of("text", text));
    }

    private static List<String> texts(EngineResult result) {
        return result.actions().stream()
                .filter(a -> a.type() == Action.Type.SEND_MESSAGE)
                .map(Action::text)
                .toList();
    }

    private static WorkflowResolver resolverOf(Workflow... workflows) {
        Map<String, Workflow> index = new HashMap<>();
        for (Workflow workflow : workflows) index.put(workflow.id(), workflow);
        return index::get;
    }

    // ─── Subfluxos ────────────────────────────────────────────────────────────────

    @Test
    void chamaSubfluxoEVoltaTrazendoSoOQueEstaEmOutput() {
        Workflow filho = Workflow.of("filho", "Filho", "calcula", List.of(
                new Node("calcula", NodeType.SET_VARIABLE, "secreta", Map.of("variable", "resultado", "value", "42")),
                new Node("secreta", NodeType.SET_VARIABLE, "fim", Map.of("variable", "interna", "value", "nao-vaza")),
                end("fim", null)));

        // O START sempre reinicia o estado, então as variáveis do pai nascem no próprio fluxo.
        Workflow pai = Workflow.of("pai", "Pai", "prepara", List.of(
                new Node("prepara", NodeType.SET_VARIABLE, "chama", Map.of("variable", "nome", "value", "Thales")),
                new Node("chama", NodeType.CALL_WORKFLOW, "mostra",
                        Map.of("workflow", "filho", "input", "nome", "output", "resultado")),
                message("mostra", "fim", "Resultado: {{resultado}} / interna: '{{interna}}'"),
                end("fim", null)));

        EngineResult result = engine.advance(pai, ConversationState.initial(), Event.start(),
                new EngineContext(resolverOf(pai, filho), ActionExecutor.DENY_ALL));

        assertNull(result.error());
        assertEquals(List.of("Resultado: 42 / interna: ''"), texts(result));
        assertEquals("42", result.state().variable("resultado"));
        // 'interna' ficou no escopo do filho — não estava em output.
        assertEquals("", result.state().variable("interna"));
        // O escopo do pai voltou intacto.
        assertEquals("Thales", result.state().variable("nome"));
        assertEquals(ConversationStatus.FINISHED, result.state().status());
        assertEquals(0, result.state().depth());
    }

    @Test
    void subfluxoSoEnxergaOQueEstaEmInput() {
        Workflow filho = Workflow.of("filho", "Filho", "eco", List.of(
                message("eco", "fim", "vejo nome='{{nome}}' e segredo='{{segredo}}'"),
                end("fim", null)));

        Workflow pai = Workflow.of("pai", "Pai", "prepara", List.of(
                new Node("prepara", NodeType.SET_VARIABLE, "prepara2", Map.of("variable", "nome", "value", "Thales")),
                new Node("prepara2", NodeType.SET_VARIABLE, "chama", Map.of("variable", "segredo", "value", "confidencial")),
                new Node("chama", NodeType.CALL_WORKFLOW, "fim", Map.of("workflow", "filho", "input", "nome")),
                end("fim", null)));

        EngineResult result = engine.advance(pai, ConversationState.initial(), Event.start(),
                new EngineContext(resolverOf(pai, filho), ActionExecutor.DENY_ALL));

        assertEquals(List.of("vejo nome='Thales' e segredo=''"), texts(result));
    }

    @Test
    void inputDentroDeSubfluxoPausaERetomaNoFilho() {
        Workflow filho = Workflow.of("filho", "Filho", "pergunta", List.of(
                new Node("pergunta", NodeType.INPUT, "fim", Map.of("prompt", "Qual seu CEP?", "variable", "cep")),
                end("fim", null)));

        Workflow pai = Workflow.of("pai", "Pai", "chama", List.of(
                new Node("chama", NodeType.CALL_WORKFLOW, "mostra", Map.of("workflow", "filho", "output", "cep")),
                message("mostra", "fim", "CEP: {{cep}}"),
                end("fim", null)));

        EngineContext ctx = new EngineContext(resolverOf(pai, filho), ActionExecutor.DENY_ALL);

        EngineResult turno1 = engine.advance(pai, ConversationState.initial(), Event.start(), ctx);
        assertEquals(ConversationStatus.WAITING_INPUT, turno1.state().status());
        assertEquals("filho", turno1.state().currentWorkflowId());
        assertEquals(1, turno1.state().depth());

        EngineResult turno2 = engine.advance(pai, turno1.state(), Event.userMessage("13560-000"), ctx);
        assertEquals(List.of("CEP: 13560-000"), texts(turno2));
        assertEquals(ConversationStatus.FINISHED, turno2.state().status());
    }

    @Test
    void recursaoEntreFluxosParaNoTetoDeProfundidade() {
        // A chama B, B chama A: sem o teto de profundidade isso empilharia frames até a memória acabar.
        Workflow a = Workflow.of("a", "A", "chama", List.of(
                new Node("chama", NodeType.CALL_WORKFLOW, "fim", Map.of("workflow", "b")),
                end("fim", null)));
        Workflow b = Workflow.of("b", "B", "chama", List.of(
                new Node("chama", NodeType.CALL_WORKFLOW, "fim", Map.of("workflow", "a")),
                end("fim", null)));

        EngineResult result = engine.advance(a, ConversationState.initial(), Event.start(),
                new EngineContext(resolverOf(a, b), ActionExecutor.DENY_ALL));

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("Profundidade máxima"), result.error());
    }

    @Test
    void subfluxoInexistenteViraErroComONome() {
        Workflow pai = Workflow.of("pai", "Pai", "chama", List.of(
                new Node("chama", NodeType.CALL_WORKFLOW, "fim", Map.of("workflow", "fantasma")),
                end("fim", null)));

        EngineResult result = engine.advance(pai, ConversationState.initial(), Event.start(),
                new EngineContext(resolverOf(pai), ActionExecutor.DENY_ALL));

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("fantasma"));
    }

    // ─── Efeitos ──────────────────────────────────────────────────────────────────

    @Test
    void efeitoAlimentaVariaveisQueOFluxoUsaAdiante() {
        ActionExecutor executor = (node, variables) -> ActionExecutor.Result.ok(
                Map.of("situacao", "ENTREGUE", "encontrados", "1"),
                Map.of("rows", "1"));

        Workflow workflow = Workflow.of("w", "W", "prepara", List.of(
                new Node("prepara", NodeType.SET_VARIABLE, "consulta", Map.of("variable", "protocolo", "value", "123")),
                new Node("consulta", NodeType.DB_QUERY, "avalia",
                        Map.of("sql", "SELECT status FROM pedidos WHERE numero = :protocolo",
                               "params", "protocolo", "output", "status:situacao")),
                new Node("avalia", NodeType.IF, null,
                        Map.of("expression", "situacao == 'ENTREGUE'", "then", "ok", "else", "pendente")),
                end("ok", "Seu pedido foi entregue."),
                end("pendente", "Ainda em trânsito.")));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start(),
                new EngineContext(WorkflowResolver.of(workflow), executor));

        assertNull(result.error());
        assertEquals(List.of("Seu pedido foi entregue."), texts(result));
        assertEquals("ENTREGUE", result.state().variable("situacao"));
    }

    @Test
    void efeitoRegistraAcaoParaRastro() {
        ActionExecutor executor = (node, variables) ->
                ActionExecutor.Result.ok(Map.of(), Map.of("rows", "7"));

        Workflow workflow = Workflow.of("w", "W", "consulta", List.of(
                new Node("consulta", NodeType.DB_QUERY, "fim", Map.of("sql", "SELECT 1")),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start(),
                new EngineContext(WorkflowResolver.of(workflow), executor));

        Action effect = result.actions().stream()
                .filter(a -> a.type() == Action.Type.DB_QUERY)
                .findFirst().orElseThrow();
        assertEquals("7", effect.details().get("rows"));
    }

    @Test
    void falhaDoEfeitoInterrompeAConversaEmVezDeSeguirComVariavelVazia() {
        ActionExecutor executor = (node, variables) -> ActionExecutor.Result.failure("conexão recusada");

        Workflow workflow = Workflow.of("w", "W", "consulta", List.of(
                new Node("consulta", NodeType.DB_QUERY, "fim", Map.of("sql", "SELECT 1")),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start(),
                new EngineContext(WorkflowResolver.of(workflow), executor));

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("conexão recusada"));
    }

    @Test
    void semExecutorConfiguradoOEfeitoRecusaEmVezDeVirarNoOp() {
        Workflow workflow = Workflow.of("w", "W", "consulta", List.of(
                new Node("consulta", NodeType.DB_QUERY, "fim", Map.of("sql", "SELECT 1")),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("Action Executor"));
    }

    // ─── Expressões dentro do motor ───────────────────────────────────────────────

    @Test
    void documentoSaiComoMensagemENaoComoVariavel() {
        // A garantia que importa: o base64 do arquivo não pode entrar no escopo da conversa, que é
        // persistido em chatbot_conversation.variables e relido a cada mensagem seguinte.
        String dataUri = "[manual.pdf](data:application/pdf;base64,QUJD)";
        ActionExecutor executor = (node, variables) -> ActionExecutor.Result.message(
                List.of("Segue o manual:", dataUri), Map.of("file", "manual.pdf"));

        Workflow workflow = Workflow.of("w", "W", "envia", List.of(
                new Node("envia", NodeType.SEND_DOCUMENT, "fim", Map.of("file", "manual.pdf")),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start(),
                new EngineContext(WorkflowResolver.of(workflow), executor));

        assertNull(result.error());
        assertEquals(List.of("Segue o manual:", dataUri), texts(result));
        assertTrue(result.state().variables().isEmpty(),
                "o conteúdo do documento vazou para as variáveis: " + result.state().variables());
        assertTrue(result.actions().stream().anyMatch(a -> a.type() == Action.Type.SEND_DOCUMENT));
    }

    @Test
    void falhaAoLerODocumentoInterrompeAConversa() {
        ActionExecutor executor = (node, variables) ->
                ActionExecutor.Result.failure("Arquivo 'x.pdf' não encontrado");

        Workflow workflow = Workflow.of("w", "W", "envia", List.of(
                new Node("envia", NodeType.SEND_DOCUMENT, "fim", Map.of("file", "x.pdf")),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start(),
                new EngineContext(WorkflowResolver.of(workflow), executor));

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("x.pdf"));
    }

    @Test
    void ifPorExpressaoESetVariablePorExpressao() {
        Workflow workflow = Workflow.of("w", "W", "prepara", List.of(
                new Node("prepara", NodeType.SET_VARIABLE, "prepara2", Map.of("variable", "qtd", "value", "4")),
                new Node("prepara2", NodeType.SET_VARIABLE, "calcula", Map.of("variable", "nome", "value", "Thales")),
                new Node("calcula", NodeType.SET_VARIABLE, "avalia",
                        Map.of("variable", "total", "expression", "number(qtd) * 3")),
                new Node("avalia", NodeType.IF, null,
                        Map.of("expression", "total >= 9 && len(nome) > 2", "then", "alto", "else", "baixo")),
                end("alto", "Total alto: {{total}}"),
                end("baixo", "Total baixo: {{total}}")));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start(),
                EngineContext.standalone(workflow));

        assertEquals(List.of("Total alto: 12"), texts(result));
    }

    @Test
    void expressaoQuebradaViraErroDeFluxoComONoQueAContem() {
        Workflow workflow = Workflow.of("w", "W", "prepara", List.of(
                new Node("prepara", NodeType.SET_VARIABLE, "avalia", Map.of("variable", "nome", "value", "Thales")),
                new Node("avalia", NodeType.IF, null,
                        Map.of("expression", "nome > 10", "then", "fim", "else", "fim")),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start(),
                EngineContext.standalone(workflow));

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("avalia"), result.error());
        assertFalse(result.error().isBlank());
    }
}
