package com.infinitestack.chatbotworkflow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * O motor é uma função pura de (workflow, estado, evento) — estes testes rodam sem Spring, sem
 * banco e sem mocks, que é justamente o que essa separação comprou.
 */
class WorkflowEngineTest {

    private final WorkflowEngine engine = new WorkflowEngine(50, 10);

    // ─── Helpers de construção ────────────────────────────────────────────────────

    private static Node message(String id, String next, String text) {
        return new Node(id, NodeType.MESSAGE, next, Map.of("text", text));
    }

    private static Node input(String id, String next, String variable, String prompt) {
        return new Node(id, NodeType.INPUT, next,
                prompt == null ? Map.of("variable", variable) : Map.of("variable", variable, "prompt", prompt));
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

    // ─── Percurso feliz ───────────────────────────────────────────────────────────

    @Test
    void percorreAteOPrimeiroInputEPausa() {
        Workflow workflow = Workflow.of("w", "W", "m1", List.of(
                message("m1", "m2", "Olá!"),
                message("m2", "in", "Tudo bem?"),
                input("in", "fim", "nome", "Qual o seu nome?"),
                end("fim", "Tchau")));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(List.of("Olá!", "Tudo bem?", "Qual o seu nome?"), texts(result));
        assertEquals(ConversationStatus.WAITING_INPUT, result.state().status());
        assertEquals("in", result.state().currentNodeId());
        assertTrue(result.actions().stream().anyMatch(a -> a.type() == Action.Type.WAIT_INPUT));
        assertNull(result.error());
    }

    @Test
    void gravaARespostaNaVariavelEInterpolaNaMensagemSeguinte() {
        Workflow workflow = Workflow.of("w", "W", "in", List.of(
                input("in", "fim", "nome", "Qual o seu nome?"),
                end("fim", "Até logo, {{nome}}!")));

        EngineResult turno1 = engine.advance(workflow, ConversationState.initial(), Event.start());
        EngineResult turno2 = engine.advance(workflow, turno1.state(), Event.userMessage("  Thales  "));

        assertEquals("Thales", turno2.state().variable("nome"));  // trim aplicado
        assertEquals(List.of("Até logo, Thales!"), texts(turno2));
        assertEquals(ConversationStatus.FINISHED, turno2.state().status());
        assertTrue(turno2.actions().stream().anyMatch(a -> a.type() == Action.Type.END));
    }

    @Test
    void variavelNaoDefinidaViraStringVaziaEmVezDoPlaceholderCru() {
        Workflow workflow = Workflow.of("w", "W", "m", List.of(
                message("m", "fim", "Olá, {{inexistente}}!"),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(List.of("Olá, !"), texts(result));
    }

    @Test
    void inputSemPromptNaoEmiteMensagemVazia() {
        Workflow workflow = Workflow.of("w", "W", "in", List.of(
                input("in", "fim", "resposta", null),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(List.of(), texts(result));
        assertEquals(ConversationStatus.WAITING_INPUT, result.state().status());
    }

    // ─── Ramificação ──────────────────────────────────────────────────────────────

    @Test
    void ifSegueOsRamosThenEElseConformeACondicao() {
        Workflow workflow = Workflow.of("w", "W", "in", List.of(
                input("in", "cond", "opcao", "1 ou 2?"),
                new Node("cond", NodeType.IF, null,
                        Map.of("variable", "opcao", "operator", "eq", "value", "1",
                               "then", "um", "else", "outro")),
                end("um", "Escolheu um"),
                end("outro", "Escolheu outro")));

        EngineResult inicio = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(List.of("Escolheu um"),
                texts(engine.advance(workflow, inicio.state(), Event.userMessage("1"))));
        assertEquals(List.of("Escolheu outro"),
                texts(engine.advance(workflow, inicio.state(), Event.userMessage("2"))));
    }

    @Test
    void comparacaoDeIgualdadeIgnoraCaixaEEspacos() {
        Workflow workflow = Workflow.of("w", "W", "in", List.of(
                input("in", "cond", "resposta", "Confirma?"),
                new Node("cond", NodeType.IF, null,
                        Map.of("variable", "resposta", "operator", "eq", "value", "sim",
                               "then", "ok", "else", "nao")),
                end("ok", "Confirmado"),
                end("nao", "Cancelado")));

        EngineResult inicio = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(List.of("Confirmado"),
                texts(engine.advance(workflow, inicio.state(), Event.userMessage(" SIM "))));
    }

    @Test
    void setVariableInterpolaEDeixaOValorDisponivelAdiante() {
        Workflow workflow = Workflow.of("w", "W", "set", List.of(
                new Node("set", NodeType.SET_VARIABLE, "set2", Map.of("variable", "saudacao", "value", "Bom dia")),
                new Node("set2", NodeType.SET_VARIABLE, "m", Map.of("variable", "frase", "value", "{{saudacao}}, visitante")),
                message("m", "fim", "{{frase}}"),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(List.of("Bom dia, visitante"), texts(result));
        assertEquals("Bom dia", result.state().variable("saudacao"));
    }

    // ─── Erros de fluxo ───────────────────────────────────────────────────────────

    @Test
    void cicloEntreNosViraErroEmVezDeTravarAThread() {
        // a → b → a: sem o teto de passos, este fluxo rodaria para sempre.
        Workflow workflow = Workflow.of("w", "W", "a", List.of(
                message("a", "b", "ping"),
                message("b", "a", "pong")));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("ciclo"), "erro deveria apontar o ciclo: " + result.error());
    }

    @Test
    void nextApontandoParaNoInexistenteViraErro() {
        Workflow workflow = Workflow.of("w", "W", "m", List.of(message("m", "fantasma", "Olá")));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("fantasma"));
    }

    @Test
    void startApontandoParaNoInexistenteViraErro() {
        Workflow workflow = Workflow.of("w", "W", "nao-existe", List.of(end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("nao-existe"));
    }

    @Test
    void ifSemORamoNecessarioViraErro() {
        Workflow workflow = Workflow.of("w", "W", "cond", List.of(
                new Node("cond", NodeType.IF, null,
                        Map.of("variable", "x", "operator", "eq", "value", "1", "then", "fim")),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("else"));
    }

    // ─── Mensagem fora de hora ────────────────────────────────────────────────────

    @Test
    void mensagemEmConversaEncerradaResponteSemMudarOEstado() {
        Workflow workflow = Workflow.of("w", "W", "fim", List.of(end("fim", "Acabou")));

        EngineResult encerrada = engine.advance(workflow, ConversationState.initial(), Event.start());
        assertEquals(ConversationStatus.FINISHED, encerrada.state().status());

        EngineResult depois = engine.advance(workflow, encerrada.state(), Event.userMessage("oi?"));

        assertEquals(ConversationStatus.FINISHED, depois.state().status());
        assertEquals(1, texts(depois).size());
        assertTrue(texts(depois).get(0).contains("encerrada"));
        assertNull(depois.error());
    }

    @Test
    void subfluxoSemResolvedorFalhaApontandoOFluxoQueFalta() {
        // advance() sem contexto usa um resolvedor que só conhece o próprio fluxo — quem exercita
        // subfluxo de verdade é SubflowAndEffectsTest.
        Workflow workflow = Workflow.of("w", "W", "sub", List.of(
                new Node("sub", NodeType.CALL_WORKFLOW, "fim", Map.of("workflow", "outro")),
                end("fim", null)));

        EngineResult result = engine.advance(workflow, ConversationState.initial(), Event.start());

        assertEquals(ConversationStatus.ERROR, result.state().status());
        assertTrue(result.error().contains("outro"));
    }
}
