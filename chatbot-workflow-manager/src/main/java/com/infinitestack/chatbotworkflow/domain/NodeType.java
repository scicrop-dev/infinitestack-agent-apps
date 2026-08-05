package com.infinitestack.chatbotworkflow.domain;

/**
 * Tipos de nó reconhecidos pelo motor.
 *
 * {@link #DB_QUERY} e {@link #HTTP_REQUEST} são os únicos que produzem efeito fora da conversa e,
 * por isso, os únicos que dependem de um {@code ActionExecutor} configurado — o motor sozinho não
 * abre conexão com nada.
 */
public enum NodeType {
    MESSAGE,
    INPUT,
    IF,
    SET_VARIABLE,
    END,
    CALL_WORKFLOW,
    DB_QUERY,
    HTTP_REQUEST;

    /** Nós que saem do processo para produzir valor. */
    public boolean isEffect() {
        return this == DB_QUERY || this == HTTP_REQUEST;
    }

    /** Retorna null em vez de lançar — quem chama decide se é erro de validação ou de runtime. */
    public static NodeType fromString(String raw) {
        if (raw == null) return null;
        try {
            return NodeType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
