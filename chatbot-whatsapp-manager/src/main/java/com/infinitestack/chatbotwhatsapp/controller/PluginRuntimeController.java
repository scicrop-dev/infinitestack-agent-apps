package com.infinitestack.chatbotwhatsapp.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint the host waits for when installing the plugin.
 *
 * {@code /runtime-health} deliberately touches nothing — not the database, not the sidecar. A
 * health check that reached out to either would turn someone else's outage into a failed
 * installation of a perfectly good package. The real diagnosis lives in {@code /status}, which is
 * allowed to fail.
 */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/chatbot-whatsapp-manager}/api")
public class PluginRuntimeController {

    @Value("${infinitestack.plugin.id:chatbot-whatsapp-manager}")
    private String pluginId;

    private final com.infinitestack.chatbotwhatsapp.repository.SchemaInitializer schema;
    private final com.infinitestack.chatbotwhatsapp.repository.MessageRepository messages;

    public PluginRuntimeController(com.infinitestack.chatbotwhatsapp.repository.SchemaInitializer schema,
                                   com.infinitestack.chatbotwhatsapp.repository.MessageRepository messages) {
        this.schema = schema;
        this.messages = messages;
    }

    @GetMapping("/runtime-health")
    public String runtimeHealth() {
        return "ok";
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>();
        status.put("plugin", pluginId);
        boolean ready = schema.ensureReady();
        status.put("schemaReady", ready);
        status.put("schemaError", schema.lastError());
        if (ready) {
            try {
                status.put("messageCount", messages.count());
                status.put("status", "ready");
            } catch (Exception e) {
                status.put("status", "degraded");
                status.put("schemaError", e.getMessage());
            }
        } else {
            status.put("status", "no_database");
        }
        return status;
    }
}
