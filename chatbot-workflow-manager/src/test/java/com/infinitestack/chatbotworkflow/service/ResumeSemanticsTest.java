package com.infinitestack.chatbotworkflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.chatbotworkflow.domain.Conversation;
import com.infinitestack.chatbotworkflow.engine.WorkflowEngine;
import com.infinitestack.chatbotworkflow.engine.WorkflowParser;
import com.infinitestack.chatbotworkflow.engine.WorkflowValidator;
import com.infinitestack.chatbotworkflow.repository.ConversationRepository;
import com.infinitestack.chatbotworkflow.repository.SchemaInitializer;
import com.infinitestack.chatbotworkflow.repository.WorkflowRepository;

/**
 * A semântica de {@code resume()} — a decisão entre retomar e recomeçar é o que faz um canal
 * externo funcionar, e ela não tem nada a ver com banco.
 *
 * Os repositórios são substituídos por implementações em memória (subclasses que sobrescrevem os
 * métodos usados), então o teste roda sem Postgres. É o mesmo princípio que mantém o motor puro,
 * aplicado uma camada acima.
 */
class ResumeSemanticsTest {

    private static final String FLOW = """
            { "id": "atendimento", "name": "Atendimento", "start": "ola", "nodes": [
                { "id": "ola",  "type": "MESSAGE", "next": "pede", "config": { "text": "Olá!" } },
                { "id": "pede", "type": "INPUT",   "next": "fim",  "config": { "prompt": "Qual seu nome?", "variable": "nome" } },
                { "id": "fim",  "type": "END",     "config": { "text": "Até logo, {{nome}}!" } }
            ]}
            """;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowParser parser = new WorkflowParser(objectMapper);

        SchemaInitializer schema = new SchemaInitializer(null) {
            @Override public boolean ensureReady() { return true; }
        };

        WorkflowRepository workflows = new InMemoryWorkflowRepository();
        workflows.save("atendimento", "Atendimento", FLOW);

        WorkflowService workflowService =
                new WorkflowService(workflows, parser, new WorkflowValidator(), schema);

        service = new ConversationService(new InMemoryConversationRepository(objectMapper),
                workflowService, new WorkflowEngine(50, 10), schema,
                (node, variables) -> null);
    }

    // ─── Comportamento ────────────────────────────────────────────────────────────

    @Test
    void primeiroContatoIniciaEOTextoNaoViraResposta() {
        // O fluxo ainda não perguntou nada; o "oi" é só o gatilho. Consumi-lo como resposta
        // gravaria "oi" em 'nome' e pularia a pergunta.
        ConversationService.Turn turn = service.resume("atendimento", "whatsapp", "5511999@s.whatsapp.net", "oi");

        assertTrue(turn.startedNew());
        assertEquals(List.of("Olá!", "Qual seu nome?"), turn.messages());
        assertEquals("WAITING_INPUT", turn.status());
        assertEquals("", turn.variables().getOrDefault("nome", ""));
    }

    @Test
    void segundaMensagemRetomaAMesmaConversa() {
        ConversationService.Turn primeiro = service.resume("atendimento", "whatsapp", "jid-a", "oi");
        ConversationService.Turn segundo  = service.resume("atendimento", "whatsapp", "jid-a", "Thales");

        assertFalse(segundo.startedNew());
        assertEquals(primeiro.conversationId(), segundo.conversationId());
        assertEquals("Thales", segundo.variables().get("nome"));
        assertEquals(List.of("Até logo, Thales!"), segundo.messages());
        assertEquals("FINISHED", segundo.status());
    }

    @Test
    void devolveSoAsMensagensDoTurnoENaoOHistorico() {
        service.resume("atendimento", "whatsapp", "jid-b", "oi");
        ConversationService.Turn segundo = service.resume("atendimento", "whatsapp", "jid-b", "Ana");

        // "Olá!" e "Qual seu nome?" já foram entregues no turno anterior — reenviá-los faria o
        // usuário receber a conversa inteira de novo a cada mensagem.
        assertEquals(1, segundo.messages().size());
        assertFalse(segundo.messages().contains("Olá!"));
    }

    @Test
    void conversaEncerradaRecomecaEmVezDeResponderQueAcabou() {
        service.resume("atendimento", "whatsapp", "jid-c", "oi");
        ConversationService.Turn encerrou = service.resume("atendimento", "whatsapp", "jid-c", "Ana");
        assertEquals("FINISHED", encerrou.status());

        ConversationService.Turn depois = service.resume("atendimento", "whatsapp", "jid-c", "oi de novo");

        assertTrue(depois.startedNew());
        assertNotEquals(encerrou.conversationId(), depois.conversationId());
        assertEquals(List.of("Olá!", "Qual seu nome?"), depois.messages());
    }

    @Test
    void interlocutoresDiferentesNaoCompartilhamConversa() {
        service.resume("atendimento", "whatsapp", "jid-1", "oi");
        service.resume("atendimento", "whatsapp", "jid-2", "oi");

        ConversationService.Turn um   = service.resume("atendimento", "whatsapp", "jid-1", "Alice");
        ConversationService.Turn dois = service.resume("atendimento", "whatsapp", "jid-2", "Bruno");

        assertEquals("Alice", um.variables().get("nome"));
        assertEquals("Bruno", dois.variables().get("nome"));
        assertNotEquals(um.conversationId(), dois.conversationId());
    }

    @Test
    void mesmoInterlocutorEmCanaisDiferentesTemConversasSeparadas() {
        // Um usuário pode estar no meio de um atendimento no WhatsApp e abrir outro no Telegram.
        service.resume("atendimento", "whatsapp", "id-x", "oi");
        service.resume("atendimento", "telegram", "id-x", "oi");

        ConversationService.Turn wa = service.resume("atendimento", "whatsapp", "id-x", "ViaWhats");
        ConversationService.Turn tg = service.resume("atendimento", "telegram", "id-x", "ViaTelegram");

        assertEquals("ViaWhats", wa.variables().get("nome"));
        assertEquals("ViaTelegram", tg.variables().get("nome"));
    }

    // ─── Variáveis de sistema ─────────────────────────────────────────────────────

    @Test
    void fluxoJaComecaComOCanalEOInterlocutor() {
        ConversationService.Turn turn = service.resume("atendimento", "whatsapp", "5511999", "oi");

        assertEquals("whatsapp", turn.variables().get("is_channel"));
        assertEquals("5511999", turn.variables().get("is_user_id"));
    }

    @Test
    void variaveisDeSistemaSobrevivemAoTurnoSeguinte() {
        service.resume("atendimento", "telegram", "tg-42", "oi");
        ConversationService.Turn segundo = service.resume("atendimento", "telegram", "tg-42", "Ana");

        assertEquals("telegram", segundo.variables().get("is_channel"));
        assertEquals("tg-42", segundo.variables().get("is_user_id"));
    }

    @Test
    void conversaNovaAposEncerradaRecebeAsVariaveisDeNovo() {
        // O START zera os dados da conversa; o contexto do canal não pode ir junto, senão a
        // segunda conversa da mesma pessoa começaria cega.
        service.resume("atendimento", "whatsapp", "jid-z", "oi");
        service.resume("atendimento", "whatsapp", "jid-z", "Ana");           // encerra

        ConversationService.Turn nova = service.resume("atendimento", "whatsapp", "jid-z", "de novo");

        assertTrue(nova.startedNew());
        assertEquals("whatsapp", nova.variables().get("is_channel"));
        assertEquals("jid-z", nova.variables().get("is_user_id"));
    }

    @Test
    void oFluxoConsegueInterpolarEEscolherRamoPeloCanal() {
        // É o uso real: adaptar a resposta à capacidade do canal.
        WorkflowRepository workflows = new InMemoryWorkflowRepository();
        workflows.save("por-canal", "Por canal", """
                { "id": "por-canal", "name": "Por canal", "start": "decide", "nodes": [
                    { "id": "decide", "type": "IF",
                      "config": { "expression": "is_channel == 'whatsapp'",
                                  "then": "wa", "else": "outro" } },
                    { "id": "wa",    "type": "END", "config": { "text": "Anexo enviado para {{is_user_id}}." } },
                    { "id": "outro", "type": "END", "config": { "text": "Segue o link." } }
                ]}
                """);
        SchemaInitializer schema = new SchemaInitializer(null) {
            @Override public boolean ensureReady() { return true; }
        };
        ObjectMapper objectMapper = new ObjectMapper();
        ConversationService local = new ConversationService(
                new InMemoryConversationRepository(objectMapper),
                new WorkflowService(workflows, new WorkflowParser(objectMapper), new WorkflowValidator(), schema),
                new WorkflowEngine(50, 10), schema, (node, variables) -> null);

        assertEquals(List.of("Anexo enviado para 5511999."),
                local.resume("por-canal", "whatsapp", "5511999", "oi").messages());
        assertEquals(List.of("Segue o link."),
                local.resume("por-canal", "teams", "user-x", "oi").messages());
    }

    // ─── Dublês em memória ────────────────────────────────────────────────────────

    private static class InMemoryWorkflowRepository extends WorkflowRepository {
        private final Map<String, Row> rows = new LinkedHashMap<>();

        InMemoryWorkflowRepository() { super(null); }

        @Override public void save(String id, String name, String definition) {
            rows.put(id, new Row(id, name, definition, Instant.now()));
        }
        @Override public Row findById(String id) { return rows.get(id); }
        @Override public List<Summary> findAllSummaries() {
            return rows.values().stream().map(r -> new Summary(r.id(), r.name(), r.updatedAt())).toList();
        }
        @Override public int count() { return rows.size(); }
        @Override public int delete(String id) { return rows.remove(id) == null ? 0 : 1; }
    }

    private static class InMemoryConversationRepository extends ConversationRepository {
        private final Map<String, Conversation> conversations = new LinkedHashMap<>();
        private final Map<String, List<EventRow>> events = new HashMap<>();

        InMemoryConversationRepository(ObjectMapper objectMapper) { super(null, objectMapper); }

        @Override public void insert(Conversation conversation) {
            conversations.put(conversation.id(), conversation);
        }
        @Override public void update(Conversation conversation) {
            conversations.put(conversation.id(), conversation);
        }
        @Override public Conversation findById(String id) { return conversations.get(id); }
        @Override public int count() { return conversations.size(); }

        @Override public Conversation findLatestByChannel(String workflowId, String channel, String channelUserId) {
            return conversations.values().stream()
                    .filter(c -> workflowId.equals(c.workflowId()))
                    .filter(c -> channel.equals(c.channel()))
                    .filter(c -> channelUserId.equals(c.channelUserId()))
                    .reduce((first, second) -> second)   // o mais recente inserido
                    .orElse(null);
        }

        @Override public void appendEvent(String conversationId, String direction, String type, String payload) {
            List<EventRow> list = events.computeIfAbsent(conversationId, key -> new ArrayList<>());
            list.add(new EventRow(list.size() + 1L, conversationId, list.size() + 1,
                                  direction, type, payload, Instant.now()));
        }

        @Override public List<EventRow> findEvents(String conversationId) {
            return events.getOrDefault(conversationId, List.of());
        }
    }

    @SuppressWarnings("unused")
    private static String newId() { return UUID.randomUUID().toString(); }
}
