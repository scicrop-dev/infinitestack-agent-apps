package com.infinitestack.chatbotworkflow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.chatbotworkflow.domain.Workflow;

/**
 * O validador é o que impede um fluxo quebrado de chegar ao usuário final — cada teste aqui
 * corresponde a um erro que, sem ele, só apareceria no meio de uma conversa em produção.
 */
class WorkflowValidatorTest {

    private final WorkflowParser parser = new WorkflowParser(new ObjectMapper());
    private final WorkflowValidator validator = new WorkflowValidator();

    private WorkflowValidator.Result validate(String json) {
        return validator.validate(parser.parse(json));
    }

    @Test
    void fluxoDeDemonstracaoEmbarcadoEValido() throws Exception {
        // Guarda contra a demo apodrecer: ela é o primeiro contato de quem instala o app.
        String json = new String(
                getClass().getClassLoader().getResourceAsStream("demo-workflow.json").readAllBytes(),
                StandardCharsets.UTF_8);

        WorkflowValidator.Result result = validate(json);

        assertTrue(result.valid(), "demo inválida: " + result.errors());
        assertEquals(List.of(), result.warnings(), "demo com avisos: " + result.warnings());
    }

    @Test
    void aceitaNodesComoMapaIdParaNo() {
        Workflow workflow = parser.parse("""
                { "id": "w", "name": "W", "start": "a",
                  "nodes": { "a": { "type": "END", "config": { "text": "fim" } } } }
                """);

        assertEquals("a", workflow.nodes().get(0).id());
        assertTrue(validator.validate(workflow).valid());
    }

    @Test
    void jsonMalformadoFalhaNoParseComMensagemUtil() {
        WorkflowParser.WorkflowParseException e = assertThrows(
                WorkflowParser.WorkflowParseException.class,
                () -> parser.parse("{ \"id\": \"w\", }"));

        assertTrue(e.getMessage().startsWith("JSON inválido"));
    }

    @Test
    void nextQuebradoEUmErro() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "MESSAGE", "next": "fantasma", "config": { "text": "oi" } }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("fantasma")));
    }

    @Test
    void idDuplicadoEUmErro() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "MESSAGE", "next": "a", "config": { "text": "1" } },
                    { "id": "a", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("duplicado")));
    }

    @Test
    void tipoDeNoInvalidoListaOsTiposAceitos() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "MENSAGEM", "next": "b", "config": {} },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("MESSAGE, INPUT, IF")));
    }

    @Test
    void configObrigatoriaFaltandoEUmErro() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "INPUT", "next": "b", "config": {} },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("'variable'")));
    }

    @Test
    void operadorInvalidoNoIfEUmErro() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "IF",
                      "config": { "variable": "x", "operator": "igual", "then": "b", "else": "b" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("igual")));
    }

    @Test
    void startInexistenteEUmErro() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "z", "nodes": [
                    { "id": "a", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Nó inicial 'z'")));
    }

    @Test
    void noOrfaoEAvisoENaoErro() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "END", "config": {} },
                    { "id": "orfao", "type": "MESSAGE", "next": "a", "config": { "text": "ninguém me chama" } }
                ]}
                """);

        assertTrue(result.valid(), "órfão não deveria impedir a gravação: " + result.errors());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("orfao")));
    }

    @Test
    void fluxoSemEndAlcancavelEAviso() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "INPUT", "next": "a", "config": { "variable": "x" } }
                ]}
                """);

        assertTrue(result.valid());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("END")));
    }

    @Test
    void subfluxoEhAceitoEExigeOIdDoFluxoAlvo() {
        assertTrue(validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "CALL_WORKFLOW", "next": "b", "config": { "workflow": "outro" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """).valid());

        // A existência do fluxo alvo não é checada aqui — o validador é puro. Quem avisa é
        // WorkflowService na gravação, como aviso, para não impor ordem de criação.
        WorkflowValidator.Result semAlvo = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "CALL_WORKFLOW", "next": "b", "config": {} },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);
        assertFalse(semAlvo.valid());
        assertTrue(semAlvo.errors().stream().anyMatch(e -> e.contains("'workflow'")));
    }

    // ─── Fase 3: nós de efeito ────────────────────────────────────────────────────

    @Test
    void sqlComInterpolacaoEhRecusadoNaGravacao() {
        // A regra central de segurança: texto do usuário não vira SQL. Falhar aqui é o ponto —
        // descobrir isso em produção significaria injeção já possível.
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "DB_QUERY", "next": "b",
                      "config": { "sql": "SELECT * FROM pedidos WHERE id = '{{protocolo}}'" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("injeção")), result.errors().toString());
    }

    @Test
    void sqlDeEscritaEhRecusado() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "DB_QUERY", "next": "b",
                      "config": { "sql": "DELETE FROM pedidos" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("somente leitura")));
    }

    @Test
    void parametroNaoDeclaradoEhErro() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "DB_QUERY", "next": "b",
                      "config": { "sql": "SELECT 1 FROM t WHERE x = :protocolo" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("protocolo")));
    }

    @Test
    void selectValidoComParametroDeclaradoPassa() {
        assertTrue(validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "DB_QUERY", "next": "b",
                      "config": { "sql": "SELECT status FROM pedidos WHERE numero = :protocolo",
                                  "params": "protocolo", "output": "status:situacao" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """).valid());
    }

    @Test
    void palavraProibidaDentroDeLiteralNaoBloqueia() {
        // 'pedido deletado' contém "delete"; recusar isso seria falso positivo difícil de entender.
        assertTrue(validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "DB_QUERY", "next": "b",
                      "config": { "sql": "SELECT 'pedido deletado' AS rotulo, created_at FROM pedidos" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """).valid());
    }

    @Test
    void urlSemEsquemaEhErroNoHttpRequest() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "HTTP_REQUEST", "next": "b",
                      "config": { "url": "viacep.com.br/ws/01001000/json/" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("http://")));
    }

    // ─── Variáveis de sistema ─────────────────────────────────────────────────────

    @Test
    void fluxoNaoPodeEscreverEmVariavelDeSistema() {
        // Sobrescrever is_channel não falharia em lugar nenhum — só faria todo ramo que depende
        // dele tomar o caminho errado, sem rastro na conversa.
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "INPUT", "next": "b", "config": { "variable": "is_channel" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("variável do sistema")), result.errors().toString());
    }

    @Test
    void aReservaValeParaTodosOsNosQueEscrevem() {
        String[] nos = {
            "{ \"id\": \"a\", \"type\": \"SET_VARIABLE\", \"next\": \"b\", \"config\": { \"variable\": \"is_user_id\", \"value\": \"x\" } }",
            "{ \"id\": \"a\", \"type\": \"DB_QUERY\", \"next\": \"b\", \"config\": { \"sql\": \"SELECT 1 AS n\", \"output\": \"n:is_channel\" } }",
            "{ \"id\": \"a\", \"type\": \"DB_QUERY\", \"next\": \"b\", \"config\": { \"sql\": \"SELECT 1\", \"countInto\": \"is_total\" } }",
            "{ \"id\": \"a\", \"type\": \"HTTP_REQUEST\", \"next\": \"b\", \"config\": { \"url\": \"https://x.com\", \"statusInto\": \"is_status\" } }",
            "{ \"id\": \"a\", \"type\": \"CALL_WORKFLOW\", \"next\": \"b\", \"config\": { \"workflow\": \"outro\", \"output\": \"is_channel\" } }",
        };
        for (String no : nos) {
            WorkflowValidator.Result result = validate(
                    "{ \"id\": \"w\", \"name\": \"W\", \"start\": \"a\", \"nodes\": ["
                    + no + ", { \"id\": \"b\", \"type\": \"END\", \"config\": {} } ]}");
            assertFalse(result.valid(), "deveria recusar: " + no);
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("variável do sistema")),
                    "sem a mensagem certa em: " + no + " -> " + result.errors());
        }
    }

    @Test
    void lerVariavelDeSistemaContinuaPermitido() {
        assertTrue(validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "IF",
                      "config": { "expression": "is_channel == 'whatsapp'", "then": "b", "else": "b" } },
                    { "id": "b", "type": "END", "config": { "text": "canal: {{is_channel}}" } }
                ]}
                """).valid());
    }

    // ─── Fase 6: expressões ───────────────────────────────────────────────────────

    @Test
    void expressaoComSintaxeQuebradaEhApontadaNaGravacao() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "IF",
                      "config": { "expression": "opcao == ", "then": "b", "else": "b" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("expressão inválida")));
    }

    @Test
    void declararExpressionEVariableAoMesmoTempoEhErro() {
        WorkflowValidator.Result result = validate("""
                { "id": "w", "name": "W", "start": "a", "nodes": [
                    { "id": "a", "type": "IF",
                      "config": { "expression": "opcao == '1'", "variable": "opcao",
                                  "then": "b", "else": "b" } },
                    { "id": "b", "type": "END", "config": {} }
                ]}
                """);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("ao mesmo tempo")));
    }
}
