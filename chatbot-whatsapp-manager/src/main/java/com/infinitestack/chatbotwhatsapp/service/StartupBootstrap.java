package com.infinitestack.chatbotwhatsapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.infinitestack.chatbotwhatsapp.repository.SchemaInitializer;

/** Prepares the schema on a daemon thread, off the boot path. */
@Component
public class StartupBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBootstrap.class);

    private final SchemaInitializer schema;

    public StartupBootstrap(SchemaInitializer schema) {
        this.schema = schema;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(schema::ensureReady, "whatsapp-schema-init");
        worker.setDaemon(true);
        worker.start();
        log.info("[chatbot-whatsapp-manager] bootstrap started in background");
    }
}
