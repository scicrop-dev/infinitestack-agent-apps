package com.infinitestack.chatbotwhatsapp.repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The WhatsApp transcript: every message that crossed the bridge, both directions.
 *
 * <p>Append-only by design. This is the record of what was actually said to and by real people —
 * editing or deleting rows would make it useless for the one thing it exists for, which is being
 * able to answer "what happened in this conversation" afterwards.
 */
@Repository
public class MessageRepository {

    /**
     * @param direction {@code IN} (from the contact) or {@code OUT} (from us)
     * @param type      {@code text}, {@code image} or {@code document}
     * @param origin    {@code contact}, {@code bot} or {@code operator} — who produced it
     * @param fileName  set only for documents; media is recorded by reference, never by content
     */
    public record Message(long id, String jid, String direction, String type, String content,
                          String fileName, String origin, String waMessageId, String replyToId,
                          Instant happenedAt) {}

    /** One line per contact for the list: last message and when. */
    public record Summary(String jid, String lastContent, String lastDirection, Instant lastAt, long total) {}

    private final JdbcTemplate jdbc;
    private final AppSchema schema;

    public MessageRepository(JdbcTemplate jdbc, AppSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /** Qualified table name, resolved per call so a config change needs no restart. */
    private String table() {
        return schema.table(SchemaInitializer.TABLE);
    }

    public void append(String jid, String direction, String type, String content,
                       String fileName, String origin, String waMessageId, String replyToId,
                       Instant happenedAt) {
        jdbc.update("""
                INSERT INTO %s
                    (jid, direction, type, content, file_name, origin, wa_message_id, reply_to_id,
                     happened_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(table()),
                jid, direction, type, content, fileName, origin, waMessageId, replyToId,
                Timestamp.from(happenedAt == null ? Instant.now() : happenedAt),
                Timestamp.from(Instant.now()));
    }

    /**
     * The most recent {@code limit} messages of a conversation, oldest first.
     *
     * Reads the tail and reverses it, rather than taking the head: a long conversation should open
     * showing what was just said, the way any chat does.
     */
    public List<Message> findByJid(String jid, int limit) {
        List<Message> newestFirst = jdbc.query("""
                SELECT id, jid, direction, type, content, file_name, origin,
                       wa_message_id, reply_to_id, happened_at
                  FROM %s
                 WHERE jid = ?
                 ORDER BY happened_at DESC, id DESC
                 LIMIT ?
                """.formatted(table()), (rs, n) -> new Message(
                        rs.getLong("id"),
                        rs.getString("jid"),
                        rs.getString("direction"),
                        rs.getString("type"),
                        rs.getString("content"),
                        rs.getString("file_name"),
                        rs.getString("origin"),
                        rs.getString("wa_message_id"),
                        rs.getString("reply_to_id"),
                        rs.getTimestamp("happened_at").toInstant()), jid, limit);

        // List.reversed() is Java 21; the Agent App contract pins Java 17.
        List<Message> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    /** Contacts that have any message, most recently active first. */
    public List<Summary> summaries(int limit) {
        return jdbc.query("""
                SELECT m.jid,
                       m.content        AS last_content,
                       m.direction      AS last_direction,
                       m.happened_at    AS last_at,
                       c.total          AS total
                  FROM %s m
                  JOIN (SELECT jid, MAX(happened_at) AS last_at, COUNT(*) AS total
                          FROM %s GROUP BY jid) c
                    ON c.jid = m.jid AND c.last_at = m.happened_at
                 ORDER BY m.happened_at DESC
                 LIMIT ?
                """.formatted(table(), table()), (rs, n) -> new Summary(
                        rs.getString("jid"),
                        rs.getString("last_content"),
                        rs.getString("last_direction"),
                        rs.getTimestamp("last_at").toInstant(),
                        rs.getLong("total")), limit);
    }

    public long count() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM %s".formatted(table()), Long.class);
        return total == null ? 0 : total;
    }
}
