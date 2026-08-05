package com.infinitestack.chatapp.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.chatapp.domain.Conversation;
import com.infinitestack.chatapp.domain.ConversationState;
import com.infinitestack.chatapp.domain.ConversationStatus;

/**
 * Acesso a chatapp_conversation e chatapp_event.
 *
 * As variáveis e a pilha de chamada são gravadas como JSON em colunas TEXT — o conjunto de
 * variáveis é definido por cada fluxo, e a pilha é uma estrutura aninhada, então nenhum dos dois
 * tem schema fixo.
 *
 * chatapp_event é o histórico append-only do que entrou e do que saiu: é o que a UI relê para
 * remontar o chat depois de um refresh, e o que dá rastreabilidade de uma conversa que deu errado.
 */
@Repository
public class ConversationRepository {

    /** @param direction "IN" (do usuário) ou "OUT" (do fluxo). */
    public record EventRow(long id, String conversationId, int seq, String direction, String type,
                           String payload, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ConversationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ─── Conversa ─────────────────────────────────────────────────────────────────

    public void insert(Conversation conversation) {
        ConversationState state = conversation.state();
        jdbc.update("""
                INSERT INTO chatapp_conversation
                    (id, workflow_id, channel, channel_user_id, status, current_workflow_id,
                     current_node_id, variables, call_stack, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                conversation.id(),
                conversation.workflowId(),
                conversation.channel(),
                conversation.channelUserId(),
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
                UPDATE chatapp_conversation
                   SET status = ?, current_workflow_id = ?, current_node_id = ?,
                       variables = ?, call_stack = ?, updated_at = ?
                 WHERE id = ?
                """,
                state.status().name(),
                state.currentWorkflowId(),
                state.currentNodeId(),
                writeJson(state.variables()),
                writeJson(state.callStack()),
                Timestamp.from(conversation.updatedAt()),
                conversation.id());
    }

    /** @return null se não existir. */
    public Conversation findById(String id) {
        List<Conversation> rows = jdbc.query("""
                SELECT id, workflow_id, channel, channel_user_id, status, current_workflow_id,
                       current_node_id, variables, call_stack, created_at, updated_at
                  FROM chatapp_conversation
                 WHERE id = ?
                """, (rs, n) -> new Conversation(
                        rs.getString("id"),
                        rs.getString("workflow_id"),
                        rs.getString("channel"),
                        rs.getString("channel_user_id"),
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
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM chatapp_conversation", Integer.class);
        return total == null ? 0 : total;
    }

    // ─── Eventos ──────────────────────────────────────────────────────────────────

    public void appendEvent(String conversationId, String direction, String type, String payload) {
        // O seq é calculado a partir do máximo atual e não de uma sequence global porque o que
        // importa é a ordem DENTRO da conversa — é assim que a UI remonta o diálogo.
        jdbc.update("""
                INSERT INTO chatapp_event (conversation_id, seq, direction, type, payload, created_at)
                VALUES (?, COALESCE((SELECT MAX(seq) FROM chatapp_event WHERE conversation_id = ?), 0) + 1,
                        ?, ?, ?, ?)
                """, conversationId, conversationId, direction, type, payload, Timestamp.from(Instant.now()));
    }

    public List<EventRow> findEvents(String conversationId) {
        return jdbc.query("""
                SELECT id, conversation_id, seq, direction, type, payload, created_at
                  FROM chatapp_event
                 WHERE conversation_id = ?
                 ORDER BY seq
                """, (rs, n) -> new EventRow(
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
