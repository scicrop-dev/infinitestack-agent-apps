package com.infinitestack.chatapp.engine.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Quebra o texto da expressão em tokens.
 *
 * Aceita os operadores nas duas grafias — {@code &&}/{@code and}, {@code ||}/{@code or},
 * {@code !}/{@code not}, {@code ==}/{@code =} — porque o público que escreve fluxo não é
 * necessariamente programador, e exigir a forma simbólica só produziria erro de sintaxe evitável.
 */
final class Lexer {

    enum Type { NUMBER, STRING, IDENT, OPERATOR, LPAREN, RPAREN, COMMA, EOF }

    record Token(Type type, String text, int position) {}

    private final String source;
    private int pos = 0;

    Lexer(String source) {
        this.source = source == null ? "" : source;
    }

    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (pos >= source.length()) {
                tokens.add(new Token(Type.EOF, "", pos));
                return tokens;
            }
            char c = source.charAt(pos);

            if (c == '(') { tokens.add(new Token(Type.LPAREN, "(", pos++)); continue; }
            if (c == ')') { tokens.add(new Token(Type.RPAREN, ")", pos++)); continue; }
            if (c == ',') { tokens.add(new Token(Type.COMMA, ",", pos++)); continue; }
            if (c == '\'' || c == '"') { tokens.add(readString(c)); continue; }
            if (Character.isDigit(c)) { tokens.add(readNumber()); continue; }
            if (Character.isLetter(c) || c == '_') { tokens.add(readIdentifier()); continue; }

            tokens.add(readOperator());
        }
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
    }

    private Token readString(char quote) {
        int start = pos;
        pos++; // consome a aspa de abertura
        StringBuilder value = new StringBuilder();
        while (pos < source.length() && source.charAt(pos) != quote) {
            char c = source.charAt(pos);
            if (c == '\\' && pos + 1 < source.length()) {
                pos++;
                char escaped = source.charAt(pos);
                value.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default  -> escaped;   // \' \" \\ e qualquer outro: literal
                });
            } else {
                value.append(c);
            }
            pos++;
        }
        if (pos >= source.length()) {
            throw new ExpressionException("String não fechada na posição " + start + ".");
        }
        pos++; // consome a aspa de fechamento
        return new Token(Type.STRING, value.toString(), start);
    }

    private Token readNumber() {
        int start = pos;
        while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) pos++;
        return new Token(Type.NUMBER, source.substring(start, pos), start);
    }

    private Token readIdentifier() {
        int start = pos;
        while (pos < source.length()
                && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_'
                    || source.charAt(pos) == '.')) {
            pos++;
        }
        String text = source.substring(start, pos);
        // Palavras que são operadores, não nomes de variável.
        if (text.equalsIgnoreCase("and") || text.equalsIgnoreCase("or") || text.equalsIgnoreCase("not")) {
            return new Token(Type.OPERATOR, text.toLowerCase(), start);
        }
        return new Token(Type.IDENT, text, start);
    }

    private Token readOperator() {
        int start = pos;
        // Dois caracteres primeiro: senão "<=" seria lido como "<" seguido de "=".
        if (pos + 1 < source.length()) {
            String two = source.substring(pos, pos + 2);
            if (List.of("==", "!=", "<=", ">=", "&&", "||").contains(two)) {
                pos += 2;
                return new Token(Type.OPERATOR, two, start);
            }
        }
        char c = source.charAt(pos);
        if ("+-*/%<>=!".indexOf(c) < 0) {
            throw new ExpressionException("Caractere inesperado '" + c + "' na posição " + pos + ".");
        }
        pos++;
        return new Token(Type.OPERATOR, String.valueOf(c), start);
    }
}
