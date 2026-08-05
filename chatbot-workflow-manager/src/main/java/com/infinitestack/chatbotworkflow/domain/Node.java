package com.infinitestack.chatbotworkflow.domain;

import java.util.Map;

/**
 * Um nó do grafo conversacional.
 *
 * @param id     identificador único dentro do workflow
 * @param type   tipo do nó
 * @param next   próximo nó na sequência linear. Não se aplica a IF (que ramifica via config
 *               then/else) nem a END (que encerra) — nesses casos vem null.
 * @param config parâmetros específicos do tipo, mantidos como mapa cru para que acrescentar
 *               um campo novo a um tipo de nó não exija mudar o modelo nem migrar o JSON gravado.
 */
public record Node(String id, NodeType type, String next, Map<String, String> config) {

    public Node {
        config = (config == null) ? Map.of() : Map.copyOf(config);
    }

    /** Valor de config, ou {@code fallback} se ausente/vazio. */
    public String config(String key, String fallback) {
        String value = config.get(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public String config(String key) {
        return config(key, null);
    }

    public boolean hasConfig(String key) {
        return config(key, null) != null;
    }
}
