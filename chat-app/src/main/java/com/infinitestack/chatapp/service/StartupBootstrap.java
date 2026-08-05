package com.infinitestack.chatapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.infinitestack.chatapp.repository.SchemaInitializer;

/**
 * Prepara o banco em uma thread daemon, fora do caminho do boot.
 *
 * O IS considera a instalação bem-sucedida quando {@code /api/runtime-health} responde; qualquer
 * trabalho de banco feito de forma síncrona aqui entraria nesse tempo e transformaria um banco
 * lento em falha de instalação. Se falhar, o app segue no ar e o painel mostra o motivo —
 * {@link SchemaInitializer#ensureReady()} é retentado a cada requisição de escrita.
 */
@Component
public class StartupBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBootstrap.class);

    private final SchemaInitializer schema;
    private final WorkflowService workflowService;

    public StartupBootstrap(SchemaInitializer schema, WorkflowService workflowService) {
        this.schema = schema;
        this.workflowService = workflowService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(() -> {
            if (schema.ensureReady()) {
                workflowService.seedDemoIfEmpty();
            }
        }, "chatapp-bootstrap");
        worker.setDaemon(true);
        worker.start();
        log.info("[chat-app] bootstrap disparado em background");
    }
}
