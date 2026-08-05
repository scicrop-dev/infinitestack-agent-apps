package com.infinitestack.chatbotworkflow.engine.expr;

import java.util.List;

/**
 * Árvore sintática da expressão.
 *
 * Fica separada do avaliador para que uma expressão possa ser parseada uma vez e avaliada muitas —
 * um nó IF dentro de um menu roda a cada volta, e reparsear a cada avaliação seria trabalho puro.
 */
sealed interface Expr {

    record Literal(Object value) implements Expr {}

    /** Referência a uma variável da conversa. Não definida resolve para string vazia. */
    record Var(String name) implements Expr {}

    record Unary(String operator, Expr operand) implements Expr {}

    record Binary(String operator, Expr left, Expr right) implements Expr {}

    record Call(String function, List<Expr> arguments) implements Expr {}
}
