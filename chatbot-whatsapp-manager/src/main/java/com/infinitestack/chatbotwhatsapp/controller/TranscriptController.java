package com.infinitestack.chatbotwhatsapp.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.chatbotwhatsapp.repository.MessageRepository;
import com.infinitestack.chatbotwhatsapp.repository.SchemaInitializer;

/**
 * Receives the transcript from the host and serves it back to the panel.
 *
 * <p>{@code POST /api/transcript} is called by {@code WhatsappTranscriptRelay} in the backend over
 * 127.0.0.1 — it is not reachable from a browser, because everything a browser sends goes through
 * the host proxy, which requires a JWT. That is the same trust model the other Agent App uses for
 * its own host-facing endpoint.
 */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/chatbot-whatsapp-manager}/api")
public class TranscriptController {

    private static final Logger log = LoggerFactory.getLogger(TranscriptController.class);

    private final MessageRepository messages;
    private final SchemaInitializer schema;

    @Value("${chatbot.whatsapp.page-size:500}")
    private int pageSize;

    public TranscriptController(MessageRepository messages, SchemaInitializer schema) {
        this.messages = messages;
        this.schema = schema;
    }

    /**
     * Body: {@code { jid, direction, type, content, fileName?, origin, waMessageId?, replyToId?, at }}
     *
     * <p>Answers 503 when the schema is not ready so the relay retries instead of dropping the
     * message — a database that is briefly unavailable should not punch a hole in the transcript.
     */
    @PostMapping("/transcript")
    public ResponseEntity<?> ingest(@RequestBody Map<String, String> body) {
        String jid = body.get("jid");
        if (jid == null || jid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jid is required"));
        }
        if (!schema.ensureReady()) {
            return ResponseEntity.status(503).body(Map.of("error", "schema not ready"));
        }

        try {
            messages.append(jid,
                    body.getOrDefault("direction", "OUT"),
                    body.getOrDefault("type", "text"),
                    body.getOrDefault("content", ""),
                    body.get("fileName"),
                    body.getOrDefault("origin", ""),
                    body.get("waMessageId"),
                    body.get("replyToId"),
                    parseInstant(body.get("at")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            log.warn("[chatbot-whatsapp-manager] could not store transcript entry | jid: {} | {}", jid, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** The conversation with one contact, oldest first. */
    @GetMapping("/messages")
    public ResponseEntity<?> messages(@RequestParam String jid) {
        if (!schema.ensureReady()) {
            return ResponseEntity.status(503).body(Map.of("error", "schema not ready",
                    "detail", String.valueOf(schema.lastError())));
        }
        List<MessageRepository.Message> found = messages.findByJid(jid, pageSize);
        return ResponseEntity.ok(Map.of("jid", jid, "total", found.size(), "messages", found));
    }

    /** Contacts with any recorded message, most recently active first. */
    @GetMapping("/conversations")
    public ResponseEntity<?> conversations() {
        if (!schema.ensureReady()) {
            return ResponseEntity.status(503).body(Map.of("error", "schema not ready"));
        }
        return ResponseEntity.ok(Map.of("conversations", messages.summaries(200)));
    }

    private Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? Instant.now() : Instant.parse(value);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
