package com.infinitestack.chatbotworkflow.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitui {@code {{variavel}}} pelo valor coletado na conversa.
 *
 * Deliberadamente burro: só troca nome por valor. Nada de operadores, chamadas ou aritmética —
 * uma linguagem de expressões é a fase 6 do roadmap, e antecipá-la aqui criaria um dialeto
 * meio-implementado que depois teria que ser mantido por compatibilidade.
 *
 * Variável não definida vira string vazia, não fica com a chave crua no texto: uma mensagem
 * dizendo "Olá, {{nome}}" para o usuário final é pior do que "Olá, ".
 */
public final class VariableInterpolator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

    private VariableInterpolator() {}

    public static String interpolate(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) return "";
        if (template.indexOf("{{") < 0) return template;

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = variables.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
