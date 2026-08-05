package com.infinitestack.chatapp.engine;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regras que o SQL de um nó DB_QUERY tem que passar antes de chegar ao banco.
 *
 * <b>O modelo de ameaça é o texto que o usuário digita no chat.</b> Um fluxo é dado editável no
 * painel e roda contra o datasource do host — o mesmo banco de destino do cliente. Sem as duas
 * regras abaixo, uma consulta que monte SQL a partir da resposta do usuário entrega o banco a quem
 * estiver conversando com o bot.
 *
 * <ol>
 *   <li><b>Variável nunca entra como texto no SQL.</b> O nó usa {@code :nome} e o valor é ligado
 *       como parâmetro JDBC. A interpolação {@code {{var}}}, que vale em todos os outros campos, é
 *       <b>recusada</b> aqui — e recusada explicitamente, com mensagem, em vez de ignorada, senão o
 *       autor acharia que funcionou.</li>
 *   <li><b>Somente leitura.</b> Só SELECT (ou WITH…SELECT), comando único. Um fluxo não tem por que
 *       escrever no banco do cliente, e a superfície que isso abriria não se justifica pelo caso de
 *       uso.</li>
 * </ol>
 */
public final class SqlGuard {

    /** Comandos que denunciam intenção de escrita ou de mudança de estrutura. */
    private static final Set<String> FORBIDDEN = Set.of(
            "insert", "update", "delete", "merge", "truncate", "drop", "alter", "create",
            "grant", "revoke", "call", "do", "copy", "vacuum", "analyze", "comment", "refresh");

    private static final Pattern NAMED_PARAM = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    private SqlGuard() {}

    /** @throws IllegalArgumentException com a razão exata quando o SQL não pode ser executado. */
    public static void check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL vazio.");
        }
        if (sql.contains("{{")) {
            throw new IllegalArgumentException(
                    "SQL não aceita interpolação {{variavel}} — use parâmetro nomeado (:variavel) e declare-o em 'params'. "
                  + "Interpolar texto do usuário em SQL é injeção.");
        }

        String normalized = stripLiteralsAndComments(sql).toLowerCase(Locale.ROOT).trim();

        // Ponto-e-vírgula no meio = mais de um comando. Um no fim é só ruído e não incomoda.
        String withoutTrailing = normalized.endsWith(";")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
        if (withoutTrailing.contains(";")) {
            throw new IllegalArgumentException("SQL deve conter um único comando (encontrei ';' no meio).");
        }

        if (!withoutTrailing.startsWith("select") && !withoutTrailing.startsWith("with")) {
            throw new IllegalArgumentException("SQL deve começar com SELECT ou WITH — o nó é somente leitura.");
        }

        for (String keyword : FORBIDDEN) {
            if (containsWord(withoutTrailing, keyword)) {
                throw new IllegalArgumentException("SQL contém '" + keyword.toUpperCase(Locale.ROOT)
                        + "' — o nó é somente leitura.");
            }
        }
    }

    /** Nomes de parâmetro citados no SQL, na ordem em que aparecem. */
    public static List<String> namedParameters(String sql) {
        Matcher matcher = NAMED_PARAM.matcher(stripLiteralsAndComments(sql));
        return matcher.results().map(result -> result.group(1)).distinct().toList();
    }

    /**
     * Remove literais e comentários antes de procurar palavra proibida.
     *
     * Sem isso, {@code SELECT 'pedido deletado' AS status} seria recusado por conter "delete" dentro
     * de uma string — e um fluxo legítimo ficaria bloqueado por um falso positivo difícil de
     * entender pela mensagem.
     */
    private static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inSingle = false, inDouble = false, inLineComment = false, inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') { inLineComment = false; out.append(' '); }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') { inBlockComment = false; i++; out.append(' '); }
                continue;
            }
            if (inSingle) {
                if (c == '\'') inSingle = false;
                continue;
            }
            if (inDouble) {
                if (c == '"') inDouble = false;
                continue;
            }

            if (c == '-' && next == '-') { inLineComment = true; i++; continue; }
            if (c == '/' && next == '*') { inBlockComment = true; i++; continue; }
            if (c == '\'') { inSingle = true; out.append(' '); continue; }
            if (c == '"')  { inDouble = true; out.append(' '); continue; }

            out.append(c);
        }
        return out.toString();
    }

    /** Palavra inteira, para "created_at" não disparar o bloqueio de "create". */
    private static boolean containsWord(String haystack, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find();
    }
}
