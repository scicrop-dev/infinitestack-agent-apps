package com.infinitestack.chatbotworkflow.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.chatbotworkflow.domain.Conversation;
import com.infinitestack.chatbotworkflow.domain.ConversationState;
import com.infinitestack.chatbotworkflow.domain.ConversationStatus;

/**
 * Acesso a chatbot_workflow_manager_conversation e chatbot_workflow_manager_event.
 *
 * As variáveis e a pilha de chamada são gravadas como JSON em colunas TEXT — o conjunto de
 * variáveis é definido por cada fluxo, e a pilha é uma estrutura aninhada, então nenhum dos dois
 * tem schema fixo.
 *
 * chatbot_workflow_manager_event é o histórico append-only do que entrou e do que saiu: é o que a UI relê para
 * remontar o chat depois de um refresh, e o que dá rastreabilidade de uma conversa que deu errado.
 */
@Repository
public class ConversationRepository {

    /** @param direction "IN" (do usuário) ou "OUT" (do fluxo). */
    public record EventRow(long id, String conversationId, int seq, String direction, String type,
                           String payload, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AppSchema appSchema;

    public ConversationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, AppSchema appSchema) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.appSchema = appSchema;
    }

    private String conversations() { return appSchema.table(SchemaInitializer.T_CONVERSATION); }
    private String events()        { return appSchema.table(SchemaInitializer.T_EVENT); }

    // ─── Conversa ─────────────────────────────────────────────────────────────────

    public void insert(Conversation conversation) {
        ConversationState state = conversation.state();
        jdbc.update("""
                INSERT INTO %s
                    (id, workflow_id, channel, channel_user_id, channel_user_ref, channel_user_name,
                     status, current_workflow_id, current_node_id, variables, call_stack,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(conversations()),
                conversation.id(),
                conversation.workflowId(),
                conversation.channel(),
                conversation.channelUserId(),
                conversation.channelUserRef(),
                conversation.channelUserName(),
                state.status().name(),
                state.currentWorkflowId(),
                state.currentNodeId(),
                writeJson(state.variables()),
                writeJson(state.callStack()),
                Timestamp.from(conversation.createdAt()),
                Timestamp.from(conversation.updatedAt()));
    }

    public void update(Conversation conversation) {
        ConversationState state = conversation.state();
        jdbc.update("""
                UPDATE %s
                   SET status = ?, current_workflow_id = ?, current_node_id = ?,
                       variables = ?, call_stack = ?, channel_user_ref = ?, channel_user_name = ?,
                       updated_at = ?
                 WHERE id = ?
                """.formatted(conversations()),
                state.status().name(),
                state.currentWorkflowId(),
                state.currentNodeId(),
                writeJson(state.variables()),
                writeJson(state.callStack()),
                conversation.channelUserRef(),
                conversation.channelUserName(),
                Timestamp.from(conversation.updatedAt()),
                conversation.id());
    }

    /** @return null se não existir. */
    public Conversation findById(String id) {
        List<Conversation> rows = jdbc.query("""
                SELECT id, workflow_id, channel, channel_user_id, channel_user_ref, channel_user_name,
                       status, current_workflow_id, current_node_id, variables, call_stack,
                       created_at, updated_at
                  FROM %s
                 WHERE id = ?
                """.formatted(conversations()), (rs, n) -> new Conversation(
                        rs.getString("id"),
                        rs.getString("workflow_id"),
                        rs.getString("channel"),
                        rs.getString("channel_user_id"),
                        rs.getString("channel_user_ref"),
                        rs.getString("channel_user_name"),
                        new ConversationState(
                                ConversationStatus.valueOf(rs.getString("status")),
                                // Conversa gravada antes das colunas de subfluxo existirem tem
                                // current_workflow_id nulo: nesse caso ela roda no fluxo da própria
                                // conversa, que é exatamente o comportamento da 1.0.
                                orDefault(rs.getString("current_workflow_id"), rs.getString("workflow_id")),
                                rs.getString("current_node_id"),
                                readVariables(rs.getString("variables")),
                                readCallStack(rs.getString("call_stack"))),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int count() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM %s".formatted(conversations()), Integer.class);
        return total == null ? 0 : total;
    }

    /**
     * A conversa mais recente de um interlocutor num fluxo — a chave que um adapter de canal usa.
     *
     * <b>Por que "a mais recente" e não "a ativa".</b> Uma conversa encerrada continua na tabela
     * (é histórico), então filtrar por status aqui devolveria nada assim que o usuário terminasse
     * um atendimento. Quem decide se retoma ou recomeça é {@code ConversationService.resume()},
     * que precisa ver o estado terminal para tomar essa decisão — filtrar aqui esconderia
     * justamente a informação de que ele precisa.
     *
     * @return null se este interlocutor nunca conversou neste fluxo.
     */
    public Conversation findLatestByChannel(String workflowId, String channel, String channelUserId) {
        List<String> ids = jdbc.query("""
                SELECT id
                  FROM %s
                 WHERE workflow_id = ? AND channel = ? AND channel_user_id = ?
                 ORDER BY updated_at DESC
                 LIMIT 1
                """.formatted(conversations()), (rs, n) -> rs.getString("id"), workflowId, channel, channelUserId);
        return ids.isEmpty() ? null : findById(ids.get(0));
    }

    // ─── Eventos ──────────────────────────────────────────────────────────────────

    public void appendEvent(String conversationId, String direction, String type, String payload) {
        // O seq é calculado a partir do máximo atual e não de uma sequence global porque o que
        // importa é a ordem DENTRO da conversa — é assim que a UI remonta o diálogo.
        jdbc.update("""
                INSERT INTO %s (conversation_id, seq, direction, type, payload, created_at)
                VALUES (?, COALESCE((SELECT MAX(seq) FROM %s WHERE conversation_id = ?), 0) + 1,
                        ?, ?, ?, ?)
                """.formatted(events(), events()), conversationId, conversationId, direction, type, payload, Timestamp.from(Instant.now()));
    }

    public List<EventRow> findEvents(String conversationId) {
        return jdbc.query("""
                SELECT id, conversation_id, seq, direction, type, payload, created_at
                  FROM %s
                 WHERE conversation_id = ?
                 ORDER BY seq
                """.formatted(events()), (rs, n) -> new EventRow(
                        rs.getLong("id"),
                        rs.getString("conversation_id"),
                        rs.getInt("seq"),
                        rs.getString("direction"),
                        rs.getString("type"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()), conversationId);
    }

    // ─── Serialização ─────────────────────────────────────────────────────────────

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // Variáveis e frames são estruturas simples; falhar aqui seria bug de programação.
            throw new IllegalStateException("Falha ao serializar o estado da conversa", e);
        }
    }

    private Map<String, String> readVariables(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            // Linha corrompida não pode impedir a conversa de ser lida — perde-se o contexto,
            // não o histórico.
            return Map.of();
        }
    }

    /**
     * Pilha ilegível cai para vazia: a conversa passa a se comportar como se estivesse na raiz.
     * É degradação e não falha porque a alternativa — recusar a leitura — deixaria a conversa
     * inacessível inclusive para diagnóstico.
     */
    private List<ConversationState.Frame> readCallStack(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<ConversationState.Frame>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
