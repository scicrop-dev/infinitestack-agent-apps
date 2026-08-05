package com.infinitestack.chatbotworkflow.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Acesso a chatbot_workflow. Guarda a definição como o texto JSON original, não como colunas
 * derivadas do grafo: o formato do fluxo ainda vai crescer (subfluxos, expressões) e uma
 * modelagem relacional dos nós exigiria migração a cada tipo novo.
 */
@Repository
public class WorkflowRepository {

    /** Linha de chatbot_workflow. {@code definition} é o JSON cru do fluxo. */
    public record Row(String id, String name, String definition, Instant updatedAt) {}

    /** Projeção para a lista lateral — evita trafegar a definição inteira de todos os fluxos. */
    public record Summary(String id, String name, Instant updatedAt) {}

    private static final RowMapper<Row> ROW_MAPPER = (rs, n) -> new Row(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("definition"),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public WorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Summary> findAllSummaries() {
        return jdbc.query(
                "SELECT id, name, updated_at FROM chatbot_workflow ORDER BY name NULLS LAST, id",
                (rs, n) -> new Summary(rs.getString("id"), rs.getString("name"),
                                       rs.getTimestamp("updated_at").toInstant()));
    }

    /** @return null se não existir — ausência é resposta 404 do controller, não exceção. */
    public Row findById(String id) {
        List<Row> rows = jdbc.query(
                "SELECT id, name, definition, updated_at FROM chatbot_workflow WHERE id = ?",
                ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void save(String id, String name, String definition) {
        jdbc.update("""
                INSERT INTO chatbot_workflow (id, name, definition, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE
                    SET name = EXCLUDED.name,
                        definition = EXCLUDED.definition,
                        updated_at = EXCLUDED.updated_at
                """, id, name, definition, Timestamp.from(Instant.now()));
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM chatbot_workflow WHERE id = ?", id);
    }

    public int count() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM chatbot_workflow", Integer.class);
        return total == null ? 0 : total;
    }
}
