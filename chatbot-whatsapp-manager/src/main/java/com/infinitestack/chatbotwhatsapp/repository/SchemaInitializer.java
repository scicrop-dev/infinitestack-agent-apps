package com.infinitestack.chatbotwhatsapp.repository;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the transcript table on first boot, in the destination database the host injected.
 *
 * <p>Prepared in the background and never on the boot path — the host completes the installation
 * when {@code /api/runtime-health} answers, so a slow customer database must not be able to fail
 * the install of a perfectly good package. {@link #ensureReady()} is retried on every write, so a
 * database that comes back later does not require reinstalling the plugin.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    /** Bare table name — the plugin_id prefix is the naming rule; the schema comes from config. */
    public static final String TABLE = "chatbot_whatsapp_manager_message";

    /** {@code %s} is replaced by the qualified table name — the schema is configurable. */
    private static final List<String> DDL = List.of(
        """
        CREATE TABLE IF NOT EXISTS %s (
            id          BIGSERIAL    PRIMARY KEY,
            jid         VARCHAR(200) NOT NULL,
            direction   VARCHAR(10)  NOT NULL,
            type        VARCHAR(20)  NOT NULL,
            content     TEXT,
            file_name   VARCHAR(255),
            origin      VARCHAR(20),
            happened_at TIMESTAMP    NOT NULL,
            created_at  TIMESTAMP    NOT NULL
        )
        """,
        // The panel always reads one conversation ordered by time — this is the only hot query.
        // Id da mensagem no WhatsApp e id da mensagem citada. Vêm como ALTER separado para uma
        // instalação existente ganhá-los sem recriar a tabela — e são o que destrava reply,
        // quote e reaction: a chave de uma mensagem não é reconstruível a partir do conteúdo.
        "ALTER TABLE %s ADD COLUMN IF NOT EXISTS wa_message_id VARCHAR(80)",
        "ALTER TABLE %s ADD COLUMN IF NOT EXISTS reply_to_id VARCHAR(80)",
        """
        CREATE INDEX IF NOT EXISTS idx_chatbot_whatsapp_manager_message_wa_id
            ON %s (wa_message_id)
        """,
        """
        CREATE INDEX IF NOT EXISTS idx_chatbot_whatsapp_manager_message_jid
            ON %s (jid, happened_at)
        """,
        // Feeds the contact list ordering ("who wrote last") without scanning the whole table.
        """
        CREATE INDEX IF NOT EXISTS idx_chatbot_whatsapp_manager_message_recent
            ON %s (happened_at DESC)
        """
    );

    private final JdbcTemplate jdbc;
    private final AppSchema schema;

    private volatile boolean ready = false;
    private volatile String lastError = null;

    public SchemaInitializer(JdbcTemplate jdbc, AppSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /** @return true when the schema is usable. Never throws — the reason stays in {@link #lastError()}. */
    public boolean ensureReady() {
        if (ready) return true;
        synchronized (this) {
            if (ready) return true;
            try {
                // The schema comes first: every statement after it is qualified, so it has to exist.
                jdbc.execute(schema.createSchemaStatement());
                String table = schema.table(TABLE);
                for (String statement : DDL) {
                    jdbc.execute(statement.formatted(table));
                }
                ready = true;
                lastError = null;
                log.info("[chatbot-whatsapp-manager] schema ready ({})", schema.table(TABLE));
                return true;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("[chatbot-whatsapp-manager] could not prepare the schema: {}", e.getMessage());
                return false;
            }
        }
    }

    public boolean isReady()   { return ready; }
    public String  lastError() { return lastError; }
}
