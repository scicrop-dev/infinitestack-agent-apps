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
    HTTP_REQUEST,
    SEND_DOCUMENT;

    /**
     * Nós que saem do processo para produzir seu resultado — banco, rede ou disco.
     *
     * SEND_DOCUMENT entra aqui, e não entre os conversacionais, porque ler arquivo é I/O: se o
     * motor o executasse sozinho ele deixaria de ser função pura e os testes voltariam a precisar
     * de filesystem.
     */
    public boolean isEffect() {
        return this == DB_QUERY || this == HTTP_REQUEST || this == SEND_DOCUMENT;
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
