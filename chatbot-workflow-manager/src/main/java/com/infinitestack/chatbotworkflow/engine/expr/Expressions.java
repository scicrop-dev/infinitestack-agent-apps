package com.infinitestack.chatbotworkflow.engine.expr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fachada pública da linguagem de expressões (fase 6 do roadmap).
 *
 * <pre>
 *   Expressions.condition("opcao == '1' &amp;&amp; len(nome) &gt; 2", variables)  → boolean
 *   Expressions.text("'Olá, ' + nome", variables)                        → String
 *   Expressions.validate("opcao == '1'")                                 → null ou a mensagem de erro
 * </pre>
 *
 * <b>Cache de árvores.</b> Um nó IF dentro de um menu é avaliado a cada volta da conversa, e todas
 * as conversas do mesmo fluxo repetem a mesma expressão — parsear a cada avaliação seria trabalho
 * jogado fora. O cache é limitado: fluxo é dado editável pelo painel, e um cache sem teto viraria
 * vazamento de memória proporcional ao número de edições, não ao número de fluxos.
 */
public final class Expressions {

    private static final int MAX_CACHED = 500;
    private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();

    private Expressions() {}

    /** Avalia e devolve o resultado cru (Double, Boolean ou String). */
    public static Object evaluate(String source, Map<String, String> variables) {
        return new Evaluator(variables).evaluate(compile(source));
    }

    /** Avalia como condição, aplicando a regra de verdade a qualquer tipo de resultado. */
    public static boolean condition(String source, Map<String, String> variables) {
        return Evaluator.truthy(evaluate(source, variables));
    }

    /** Avalia e devolve texto — número inteiro sai sem casa decimal. */
    public static String text(String source, Map<String, String> variables) {
        return Evaluator.text(evaluate(source, variables));
    }

    /**
     * Verifica só a sintaxe, sem avaliar. Usado pelo validador de fluxo, que não tem variáveis em
     * mãos: erro de digitação em expressão precisa aparecer na gravação, não na conversa.
     *
     * @return null se a sintaxe está correta, ou a mensagem do erro.
     */
    public static String validate(String source) {
        try {
            compile(source);
            return null;
        } catch (ExpressionException e) {
            return e.getMessage();
        }
    }

    private static Expr compile(String source) {
        if (source == null || source.isBlank()) {
            throw new ExpressionException("Expressão vazia.");
        }
        Expr cached = CACHE.get(source);
        if (cached != null) return cached;

        Expr parsed = Parser.parse(source);
        if (CACHE.size() >= MAX_CACHED) CACHE.clear();
        CACHE.put(source, parsed);
        return parsed;
    }
}
