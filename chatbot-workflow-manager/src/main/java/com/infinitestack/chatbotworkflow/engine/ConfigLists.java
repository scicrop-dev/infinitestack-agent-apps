package com.infinitestack.chatbotworkflow.engine;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitura das listas e mapeamentos que aparecem em {@code config} — {@code "a, b, c"} e
 * {@code "coluna:variavel, outra:variavel2"}.
 *
 * Fica num utilitário próprio porque motor e executor precisam interpretar esses campos exatamente
 * do mesmo jeito: se um tolerasse espaço e o outro não, o mesmo {@code output} funcionaria em
 * DB_QUERY e falharia em CALL_WORKFLOW, sem nada na tela explicando a diferença.
 */
public final class ConfigLists {

    private ConfigLists() {}

    /** Lista separada por vírgula, tolerando espaços e itens vazios. */
    public static List<String> names(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * Mapeamento {@code "origem:destino"} ou {@code "origem=destino"}, preservando a ordem.
     * Item sem separador vira origem e destino iguais — o caso comum de "traga a coluna com o
     * mesmo nome".
     */
    public static Map<String, String> mapping(String raw) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String entry : names(raw)) {
            int separator = entry.indexOf(':');
            if (separator < 0) separator = entry.indexOf('=');
            if (separator < 0) {
                mapping.put(entry, entry);
            } else {
                mapping.put(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim());
            }
        }
        return mapping;
    }
}
