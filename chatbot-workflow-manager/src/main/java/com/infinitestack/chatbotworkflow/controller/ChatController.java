package com.infinitestack.chatbotworkflow.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.chatbotworkflow.service.ConversationService;
import com.infinitestack.chatbotworkflow.service.WorkflowService;

/**
 * A porta de entrada de mensagens no motor.
 *
 * Hoje quem consome é o chat de teste do painel, mas a interface é a mesma que um adapter de canal
 * externo (WhatsApp, Telegram) vai usar: começar uma conversa e mandar texto nela. Por isso
 * {@code channel} e {@code channelUserId} existem desde já no start — quando o adapter chegar, ele
 * preenche os dois e nada mais muda aqui.
 */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/chatbot-workflow-manager}/api/chat")
public class ChatController {

    private final ConversationService conversationService;

    public ChatController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** Body: {@code { "workflowId": "...", "channel": "ui", "channelUserId": "..." }} */
    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, String> body) {
        String workflowId = body.get("workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "workflowId é obrigatório."));
        }
        try {
            return ResponseEntity.ok(conversationService.start(
                    workflowId, body.getOrDefault("channel", "ui"), body.get("channelUserId")));
        } catch (WorkflowService.WorkflowNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * A porta dos adapters de canal — WhatsApp, Telegram, Teams.
     *
     * Diferente de {@code /start} + {@code /{id}/message}, que exigem o chamador guardar o
     * {@code conversationId}: um canal externo só conhece quem mandou a mensagem. Aqui a conversa é
     * localizada por {@code (workflowId, channel, channelUserId)} e o serviço decide entre retomar
     * e recomeçar.
     *
     * Body: {@code { "workflowId", "channel", "channelUserId", "text",
     *                 "channelUserRef"?, "channelUserName"? }}
     *
     * <p>{@code channelUserRef} e {@code channelUserName} são metadados do canal (o JID do WhatsApp
     * e o nome de exibição). Chegam ao fluxo como variáveis {@code is_*} e <b>não</b> participam da
     * identidade da conversa — trocar de número não abre uma conversa nova.
     */
    @PostMapping("/resume")
    public ResponseEntity<?> resume(@RequestBody Map<String, String> body) {
        String workflowId = body.get("workflowId");
        String channelUserId = body.get("channelUserId");
        if (workflowId == null || workflowId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "workflowId é obrigatório."));
        }
        if (channelUserId == null || channelUserId.isBlank()) {
            // Sem identidade do interlocutor não há como separar uma conversa da outra — todo
            // mundo do canal cairia na mesma, com as variáveis de um vazando para o outro.
            return ResponseEntity.badRequest().body(Map.of("error", "channelUserId é obrigatório."));
        }
        try {
            return ResponseEntity.ok(conversationService.resume(
                    workflowId, body.get("channel"), channelUserId, body.getOrDefault("text", ""),
                    body.get("channelUserRef"), body.get("channelUserName")));
        } catch (WorkflowService.WorkflowNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** Body: {@code { "text": "..." }} */
    @PostMapping("/{conversationId}/message")
    public ResponseEntity<?> message(@PathVariable String conversationId, @RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text é obrigatório."));
        }
        try {
            return ResponseEntity.ok(conversationService.handleUserMessage(conversationId, text));
        } catch (ConversationService.ConversationNotFoundException | WorkflowService.WorkflowNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<?> get(@PathVariable String conversationId) {
        try {
            return ResponseEntity.ok(conversationService.get(conversationId));
        } catch (ConversationService.ConversationNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}
