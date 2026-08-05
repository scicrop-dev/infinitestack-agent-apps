package com.infinitestack.chatbotworkflow.domain;

/**
 * Estados de uma conversa (docs/007-state-machine.md).
 *
 * RUNNING é transitório: só existe enquanto o motor percorre nós dentro de um turno. Toda conversa
 * gravada está em um estado de repouso — WAITING_INPUT (esperando o usuário), FINISHED ou ERROR.
 * PAUSED é reservado para a pausa administrativa (roadmap): o motor nunca o atribui sozinho.
 */
public enum ConversationStatus {
    RUNNING,
    WAITING_INPUT,
    PAUSED,
    ERROR,
    FINISHED;

    /** Estados que não avançam mais sem intervenção externa. */
    public boolean isTerminal() {
        return this == FINISHED || this == ERROR;
    }

    /** Estados que aceitam mensagem do usuário. */
    public boolean acceptsUserMessage() {
        return this == WAITING_INPUT;
    }
}
