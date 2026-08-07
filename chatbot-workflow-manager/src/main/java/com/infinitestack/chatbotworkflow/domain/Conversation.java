package com.infinitestack.chatbotworkflow.domain;

import java.time.Instant;

/**
 * Uma execução de workflow para um interlocutor específico — a linha de chatbot_workflow_manager_conversation.
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
        String channelUserRef,
        String channelUserName,
        ConversationState state,
        Instant createdAt,
        Instant updatedAt) {

    public Conversation withState(ConversationState newState) {
        return new Conversation(id, workflowId, channel, channelUserId, channelUserRef, channelUserName,
                newState, createdAt, Instant.now());
    }

    /**
     * Refreshes the channel metadata without touching anything else.
     *
     * A contact can change their WhatsApp display name mid-conversation, and the adapter reports
     * whatever the device says on each message — so the newest value wins. Blank input is ignored
     * rather than stored, because a channel that stops reporting a name should not erase one the
     * flow was already using.
     */
    public Conversation withChannelMetadata(String ref, String name) {
        return new Conversation(id, workflowId, channel, channelUserId,
                (ref == null || ref.isBlank()) ? channelUserRef : ref,
                (name == null || name.isBlank()) ? channelUserName : name,
                state, createdAt, Instant.now());
    }
}
