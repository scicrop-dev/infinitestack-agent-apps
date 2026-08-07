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

    /** Bare names — the plugin_id prefix is the naming rule; the schema comes from config. */
    public static final String T_WORKFLOW = "chatbot_workflow_manager_workflow";
    public static final String T_CONVERSATION = "chatbot_workflow_manager_conversation";
    public static final String T_EVENT = "chatbot_workflow_manager_event";

    /**
     * Each statement carries a single {@code %s}, filled with the qualified name of the table it
     * touches — kept as a pair so the mapping stays visible instead of depending on list order.
     */
    private record Ddl(String table, String statement) {}

    private static final List<Ddl> DDL = List.of(
        new Ddl(T_WORKFLOW, """
        CREATE TABLE IF NOT EXISTS %s (
            id          VARCHAR(120) PRIMARY KEY,
            name        VARCHAR(255),
            definition  TEXT        NOT NULL,
            updated_at  TIMESTAMP   NOT NULL
        )
        """),
        new Ddl(T_CONVERSATION, """
        CREATE TABLE IF NOT EXISTS %s (
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
        """),
        new Ddl(T_CONVERSATION, """
        CREATE INDEX IF NOT EXISTS idx_chatbot_workflow_manager_conversation_channel
            ON %s (channel, channel_user_id)
        """),
        // Índice da retomada por canal: é a consulta que roda a CADA mensagem recebida de um
        // adapter externo, então é a única com caminho quente de verdade neste schema.
        new Ddl(T_CONVERSATION, """
        CREATE INDEX IF NOT EXISTS idx_chatbot_workflow_manager_conversation_resume
            ON %s (workflow_id, channel, channel_user_id, updated_at DESC)
        """),
        // Colunas de subfluxo (fase 5). Vêm como ALTER separado, e não no CREATE acima, para que
        // uma instalação que já criou a tabela na 1.0 ganhe as colunas sem precisar recriar nada.
        new Ddl(T_CONVERSATION, "ALTER TABLE %s ADD COLUMN IF NOT EXISTS current_workflow_id VARCHAR(120)"),
        new Ddl(T_CONVERSATION, "ALTER TABLE %s ADD COLUMN IF NOT EXISTS call_stack TEXT"),
        // Metadados do canal (JID e nome de exibição do WhatsApp). ALTER separado pelo mesmo motivo
        // das colunas de subfluxo: instalação existente ganha as colunas sem recriar a tabela.
        new Ddl(T_CONVERSATION, "ALTER TABLE %s ADD COLUMN IF NOT EXISTS channel_user_ref VARCHAR(200)"),
        new Ddl(T_CONVERSATION, "ALTER TABLE %s ADD COLUMN IF NOT EXISTS channel_user_name VARCHAR(200)"),
        new Ddl(T_EVENT, """
        CREATE TABLE IF NOT EXISTS %s (
            id              BIGSERIAL   PRIMARY KEY,
            conversation_id VARCHAR(64) NOT NULL,
            seq             INTEGER     NOT NULL,
            direction       VARCHAR(10) NOT NULL,
            type            VARCHAR(30) NOT NULL,
            payload         TEXT,
            created_at      TIMESTAMP   NOT NULL
        )
        """),
        new Ddl(T_EVENT, """
        CREATE INDEX IF NOT EXISTS idx_chatbot_workflow_manager_event_conversation
            ON %s (conversation_id, seq)
        """)
    );

    private final JdbcTemplate jdbc;
    private final AppSchema appSchema;

    private volatile boolean ready = false;
    private volatile String lastError = null;

    public SchemaInitializer(JdbcTemplate jdbc, AppSchema appSchema) {
        this.jdbc = jdbc;
        this.appSchema = appSchema;
    }

    /** @return true se o schema está pronto para uso. Não lança: o estado da falha fica em {@link #lastError()}. */
    public boolean ensureReady() {
        if (ready) return true;
        synchronized (this) {
            if (ready) return true;
            try {
                // The schema comes first: every statement after it is qualified, so it has to exist.
                jdbc.execute(appSchema.createSchemaStatement());
                for (Ddl ddl : DDL) {
                    jdbc.execute(ddl.statement().formatted(appSchema.table(ddl.table())));
                }
                ready = true;
                lastError = null;
                log.info("[chatbot-workflow-manager] schema pronto ({}: {}, {}, {})",
                        appSchema.name(), T_WORKFLOW, T_CONVERSATION, T_EVENT);
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
