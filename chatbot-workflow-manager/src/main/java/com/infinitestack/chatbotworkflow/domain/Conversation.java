package com.infinitestack.chatbotworkflow.domain;

import java.time.Instant;

/**
 * Uma execução de workflow para um interlocutor específico — a linha de chatbot_conversation.
 *
 * Identidade e metadados de canal ficam aqui; o que o motor manipula fica em {@link #state()}.
 * {@code channel} é sempre preenchido ("ui" no chat de teste do painel) porque o motor é
 * agnóstico de canal por design: trocar o adapter não muda nada do que está gravado.
 */
public record Conversation(
        String id,
        String workflowId,
        String channel,
        String channelUserId,
        ConversationState state,
        Instant createdAt,
        Instant updatedAt) {

    public Conversation withState(ConversationState newState) {
        return new Conversation(id, workflowId, channel, channelUserId, newState, createdAt, Instant.now());
    }
}
