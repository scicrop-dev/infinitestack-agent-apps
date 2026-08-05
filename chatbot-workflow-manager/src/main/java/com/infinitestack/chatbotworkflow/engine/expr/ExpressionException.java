package com.infinitestack.chatbotworkflow.engine.expr;

/**
 * Erro de sintaxe ou de avaliação numa expressão.
 *
 * Diferente da comparação simples (variable/operator/value), que engole entrada inesperada e devolve
 * false, a linguagem de expressões <b>falha explicitamente</b>: quem escreve uma expressão está
 * escrevendo código, e um erro silencioso viraria um ramo tomado por engano — o pior tipo de bug de
 * fluxo, porque a conversa segue parecendo normal.
 */
public class ExpressionException extends RuntimeException {
    public ExpressionException(String message) {
        super(message);
    }
}
