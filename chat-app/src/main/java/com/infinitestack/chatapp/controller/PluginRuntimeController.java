package com.infinitestack.chatapp.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.chatapp.repository.SchemaInitializer;
import com.infinitestack.chatapp.service.ConversationService;
import com.infinitestack.chatapp.service.WorkflowService;

/**
 * Endpoints de vida do plugin.
 *
 * {@code /runtime-health} é o que o IS espera responder para dar a instalação por concluída, e por
 * isso <b>nunca</b> toca no banco: se ele dependesse do datasource do cliente, um banco fora do ar
 * reprovaria a instalação de um pacote perfeitamente válido. O diagnóstico real (schema pronto?
 * quantos fluxos?) fica em {@code /status}, que pode falhar sem consequência.
 */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/chat-app}/api")
public class PluginRuntimeController {

    private final SchemaInitializer schema;
    private final WorkflowService workflowService;
    private final ConversationService conversationService;

    public PluginRuntimeController(SchemaInitializer schema,
                                   WorkflowService workflowService,
                                   ConversationService conversationService) {
        this.schema = schema;
        this.workflowService = workflowService;
        this.conversationService = conversationService;
    }

    @GetMapping("/runtime-health")
    public String runtimeHealth() {
        return "ok";
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>();
        boolean ready = schema.ensureReady();
        status.put("schemaReady", ready);
        status.put("schemaError", schema.lastError());

        if (ready) {
            try {
                status.put("workflowCount", workflowService.list().size());
                status.put("conversationCount", conversationService.count());
                status.put("status", "ready");
            } catch (Exception e) {
                // Schema existe mas a consulta falhou (permissão, conexão caiu no meio): reportar
                // "degraded" com a causa é mais útil para o operador do que um 500 genérico.
                status.put("status", "degraded");
                status.put("schemaError", e.getMessage());
            }
        } else {
            status.put("status", "no_database");
        }
        return status;
    }
}
