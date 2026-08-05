package com.infinitestack.chatapp.engine;

import java.util.Set;

/**
 * Avalia a condição de um nó IF: {@code variable <operator> value}.
 *
 * Duas escolhas que valem explicar, porque o lado esquerdo quase sempre é texto digitado por um
 * humano em um chat:
 *
 * 1. <b>Comparação frouxa em eq/neq/contains</b> — trim e case-insensitive. Um fluxo que pergunta
 *    "digite SIM" não pode quebrar porque o usuário mandou "sim ". A alternativa (comparação
 *    estrita) empurraria todo autor de fluxo a escrever normalização à mão em cada ramo.
 * 2. <b>gt/lt/gte/lte são numéricos e só numéricos</b> — se qualquer um dos lados não for número,
 *    a condição é falsa em vez de cair para comparação lexicográfica. "10" &lt; "9" ser verdadeiro
 *    por ordem alfabética é o tipo de bug que só aparece em produção com o dado certo.
 */
public final class ConditionEvaluator {

    public static final Set<String> OPERATORS =
            Set.of("eq", "neq", "contains", "not_contains", "gt", "gte", "lt", "lte", "empty", "not_empty");

    private ConditionEvaluator() {}

    public static boolean evaluate(String left, String operator, String right) {
        String l = left == null ? "" : left.trim();
        String r = right == null ? "" : right.trim();

        return switch (operator == null ? "" : operator.trim().toLowerCase()) {
            case "eq"           -> l.equalsIgnoreCase(r);
            case "neq"          -> !l.equalsIgnoreCase(r);
            case "contains"     -> l.toLowerCase().contains(r.toLowerCase());
            case "not_contains" -> !l.toLowerCase().contains(r.toLowerCase());
            case "empty"        -> l.isEmpty();
            case "not_empty"    -> !l.isEmpty();
            case "gt"           -> compareNumeric(l, r, 1);
            case "gte"          -> compareNumeric(l, r, 1) || compareNumeric(l, r, 0);
            case "lt"           -> compareNumeric(l, r, -1);
            case "lte"          -> compareNumeric(l, r, -1) || compareNumeric(l, r, 0);
            default             -> false;
        };
    }

    /** Retorna false — não lança — quando algum lado não é número: fluxo mal preenchido não derruba a conversa. */
    private static boolean compareNumeric(String left, String right, int expectedSignum) {
        try {
            int signum = Double.compare(Double.parseDouble(left.replace(',', '.')),
                                        Double.parseDouble(right.replace(',', '.')));
            return Integer.signum(signum) == expectedSignum;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
