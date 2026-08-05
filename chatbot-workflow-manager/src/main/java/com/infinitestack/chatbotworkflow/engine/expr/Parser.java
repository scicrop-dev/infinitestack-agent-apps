package com.infinitestack.chatbotworkflow.engine.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser recursivo-descendente. Precedência, da mais fraca para a mais forte:
 *
 * <pre>
 *   ||  or
 *   &amp;&amp;  and
 *   ==  !=  &lt;  &lt;=  &gt;  &gt;=
 *   +   -
 *   *   /  %
 *   !   not   - (unário)
 *   literais, variáveis, chamadas, ( )
 * </pre>
 *
 * Recursivo-descendente e não um gerador de parser porque a gramática cabe em uma tela, não tem
 * ambiguidade e assim não entra dependência nova no pacote — um Agent App é distribuído como fat jar
 * e cada biblioteca a mais é peso no {@code .ispz}.
 */
final class Parser {

    private final List<Lexer.Token> tokens;
    private int index = 0;

    Parser(List<Lexer.Token> tokens) {
        this.tokens = tokens;
    }

    static Expr parse(String source) {
        Parser parser = new Parser(new Lexer(source).tokenize());
        Expr expr = parser.or();
        if (parser.peek().type() != Lexer.Type.EOF) {
            throw new ExpressionException("Sobrou '" + parser.peek().text() + "' no fim da expressão.");
        }
        return expr;
    }

    // ─── Níveis de precedência ────────────────────────────────────────────────────

    private Expr or() {
        Expr left = and();
        while (matchOperator("||", "or")) {
            left = new Expr.Binary("||", left, and());
        }
        return left;
    }

    private Expr and() {
        Expr left = comparison();
        while (matchOperator("&&", "and")) {
            left = new Expr.Binary("&&", left, comparison());
        }
        return left;
    }

    private Expr comparison() {
        Expr left = additive();
        // Sem encadeamento: `a < b < c` é erro de intenção mais provável que operação desejada.
        if (matchOperator("==", "=", "!=", "<", "<=", ">", ">=")) {
            String operator = previous().text();
            return new Expr.Binary("=".equals(operator) ? "==" : operator, left, additive());
        }
        return left;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (matchOperator("+", "-")) {
            left = new Expr.Binary(previous().text(), left, multiplicative());
        }
        return left;
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (matchOperator("*", "/", "%")) {
            left = new Expr.Binary(previous().text(), left, unary());
        }
        return left;
    }

    private Expr unary() {
        if (matchOperator("!", "not")) {
            return new Expr.Unary("!", unary());
        }
        if (matchOperator("-")) {
            return new Expr.Unary("-", unary());
        }
        return primary();
    }

    private Expr primary() {
        Lexer.Token token = peek();

        switch (token.type()) {
            case NUMBER -> {
                advance();
                try {
                    return new Expr.Literal(Double.parseDouble(token.text()));
                } catch (NumberFormatException e) {
                    throw new ExpressionException("Número inválido: '" + token.text() + "'.");
                }
            }
            case STRING -> {
                advance();
                return new Expr.Literal(token.text());
            }
            case IDENT -> {
                advance();
                if (token.text().equalsIgnoreCase("true"))  return new Expr.Literal(Boolean.TRUE);
                if (token.text().equalsIgnoreCase("false")) return new Expr.Literal(Boolean.FALSE);
                if (peek().type() == Lexer.Type.LPAREN)      return call(token.text());
                return new Expr.Var(token.text());
            }
            case LPAREN -> {
                advance();
                Expr inner = or();
                expect(Lexer.Type.RPAREN, ")");
                return inner;
            }
            default -> throw new ExpressionException(
                    "Esperava um valor mas encontrei '" + describe(token) + "'.");
        }
    }

    private Expr call(String function) {
        expect(Lexer.Type.LPAREN, "(");
        List<Expr> arguments = new ArrayList<>();
        if (peek().type() != Lexer.Type.RPAREN) {
            arguments.add(or());
            while (peek().type() == Lexer.Type.COMMA) {
                advance();
                arguments.add(or());
            }
        }
        expect(Lexer.Type.RPAREN, ")");
        return new Expr.Call(function, arguments);
    }

    // ─── Utilitários ──────────────────────────────────────────────────────────────

    private boolean matchOperator(String... options) {
        Lexer.Token token = peek();
        if (token.type() != Lexer.Type.OPERATOR) return false;
        for (String option : options) {
            if (option.equals(token.text())) {
                advance();
                return true;
            }
        }
        return false;
    }

    private void expect(Lexer.Type type, String symbol) {
        if (peek().type() != type) {
            throw new ExpressionException("Esperava '" + symbol + "' mas encontrei '" + describe(peek()) + "'.");
        }
        advance();
    }

    private String describe(Lexer.Token token) {
        return token.type() == Lexer.Type.EOF ? "fim da expressão" : token.text();
    }

    private Lexer.Token peek()     { return tokens.get(index); }
    private Lexer.Token previous() { return tokens.get(index - 1); }
    private void advance()         { if (index < tokens.size() - 1) index++; }
}
