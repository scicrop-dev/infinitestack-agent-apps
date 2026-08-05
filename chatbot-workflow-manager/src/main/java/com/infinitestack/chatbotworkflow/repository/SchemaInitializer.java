package com.infinitestack.chatbotworkflow.repository;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cria as tabelas do app no banco de destino do cliente, no primeiro boot.
 *
 * <b>Por que em background e nunca bloqueando o boot</b> (quem dispara é o {@link StartupBootstrap}):
 * o IS instala o plugin iniciando o JAR e esperando {@code /api/runtime-health} responder — se o
 * banco do cliente estiver lento ou fora do ar, um schema init síncrono seguraria o boot e a
 * instalação inteira falharia, por um motivo que não tem nada a ver com o pacote estar correto.
 * Aqui o app sobe sempre; o painel mostra o estado do schema e o motivo da falha.
 *
 * <b>Por que ensureReady() é re-chamável:</b> banco que voltou depois do boot não deveria exigir
 * reinstalar o plugin. Toda entrada de escrita chama este método; quando já está pronto, custa a
 * leitura de um volatile.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private static final List<String> DDL = List.of(
        """
        CREATE TABLE IF NOT EXISTS chatbot_workflow (
            id          VARCHAR(120) PRIMARY KEY,
            name        VARCHAR(255),
            definition  TEXT        NOT NULL,
            updated_at  TIMESTAMP   NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS chatbot_conversation (
            id              VARCHAR(64)  PRIMARY KEY,
            workflow_id     VARCHAR(120) NOT NULL,
            channel         VARCHAR(40)  NOT NULL,
            channel_user_id VARCHAR(200),
            status          VARCHAR(20)  NOT NULL,
            current_node_id VARCHAR(120),
            variables       TEXT         NOT NULL,
            created_at      TIMESTAMP    NOT NULL,
            updated_at      TIMESTAMP    NOT NULL
        )
        """,
        """
        CREATE INDEX IF NOT EXISTS idx_chatbot_conversation_channel
            ON chatbot_conversation (channel, channel_user_id)
        """,
        // Índice da retomada por canal: é a consulta que roda a CADA mensagem recebida de um
        // adapter externo, então é a única com caminho quente de verdade neste schema.
        """
        CREATE INDEX IF NOT EXISTS idx_chatbot_conversation_resume
            ON chatbot_conversation (workflow_id, channel, channel_user_id, updated_at DESC)
        """,
        // Colunas de subfluxo (fase 5). Vêm como ALTER separado, e não no CREATE acima, para que
        // uma instalação que já criou a tabela na 1.0 ganhe as colunas sem precisar recriar nada.
        "ALTER TABLE chatbot_conversation ADD COLUMN IF NOT EXISTS current_workflow_id VARCHAR(120)",
        "ALTER TABLE chatbot_conversation ADD COLUMN IF NOT EXISTS call_stack TEXT",
        """
        CREATE TABLE IF NOT EXISTS chatbot_event (
            id              BIGSERIAL   PRIMARY KEY,
            conversation_id VARCHAR(64) NOT NULL,
            seq             INTEGER     NOT NULL,
            direction       VARCHAR(10) NOT NULL,
            type            VARCHAR(30) NOT NULL,
            payload         TEXT,
            created_at      TIMESTAMP   NOT NULL
        )
        """,
        """
        CREATE INDEX IF NOT EXISTS idx_chatbot_event_conversation
            ON chatbot_event (conversation_id, seq)
        """
    );

    private final JdbcTemplate jdbc;

    private volatile boolean ready = false;
    private volatile String lastError = null;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true se o schema está pronto para uso. Não lança: o estado da falha fica em {@link #lastError()}. */
    public boolean ensureReady() {
        if (ready) return true;
        synchronized (this) {
            if (ready) return true;
            try {
                for (String statement : DDL) {
                    jdbc.execute(statement);
                }
                ready = true;
                lastError = null;
                log.info("[chatbot-workflow-manager] schema pronto (chatbot_workflow, chatbot_conversation, chatbot_event)");
                return true;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("[chatbot-workflow-manager] falha ao preparar o schema: {}", e.getMessage());
                return false;
            }
        }
    }

    public boolean isReady()     { return ready; }
    public String  lastError()   { return lastError; }
}
