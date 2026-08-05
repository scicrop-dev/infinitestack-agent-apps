package com.infinitestack.chatapp.engine.expr;

import java.util.List;
import java.util.Map;

/**
 * Avalia a árvore contra as variáveis da conversa.
 *
 * <b>Modelo de valores.</b> Só existem três: número ({@code Double}), booleano e texto. Variável da
 * conversa entra sempre como texto — é o que o canal entrega — e é convertida sob demanda. Não há
 * declaração de tipo: tipar variável exigiria declarar o tipo no nó, decidir o que fazer quando a
 * conversão falha e definir coerção em cada operador, três decisões que não se pagam num fluxo de
 * atendimento.
 *
 * <b>Coerções, e por que cada uma.</b>
 * <ul>
 *   <li>{@code +} soma se os dois lados forem numéricos, senão concatena. É o operador que o autor
 *       de fluxo mais usa para montar texto, e exigir uma função à parte para concatenar seria
 *       atrito sem ganho.</li>
 *   <li>{@code ==} compara numericamente quando os dois lados são números; senão compara texto
 *       <b>ignorando caixa e espaços nas pontas</b>. Isso é deliberadamente igual à semântica do
 *       operador simples {@code eq} — as duas formas de escrever condição precisam concordar, ou o
 *       autor que migrar de uma para a outra vê o fluxo mudar de comportamento sem mudar de
 *       intenção.</li>
 *   <li>{@code < <= > >=} são estritamente numéricos e <b>falham</b> se algum lado não for número.
 *       Aqui a linguagem diverge do operador simples, que devolve falso: quem escreve expressão
 *       está escrevendo código, e um {@code <} que silenciosamente vira falso esconde justamente o
 *       dado malformado que se queria detectar.</li>
 * </ul>
 */
final class Evaluator {

    private final Map<String, String> variables;

    Evaluator(Map<String, String> variables) {
        this.variables = variables == null ? Map.of() : variables;
    }

    // instanceof encadeado, e não switch sobre padrões: pattern matching em switch só saiu de
    // preview no Java 21, e o contrato do Agent App fixa Java 17.
    Object evaluate(Expr expr) {
        if (expr instanceof Expr.Literal literal) return literal.value();
        if (expr instanceof Expr.Var var)         return variables.getOrDefault(var.name(), "");
        if (expr instanceof Expr.Unary unary)     return evaluateUnary(unary);
        if (expr instanceof Expr.Binary binary)   return evaluateBinary(binary);
        if (expr instanceof Expr.Call call)       return evaluateCall(call);
        throw new ExpressionException("Nó de expressão desconhecido: " + expr);
    }

    // ─── Operadores ───────────────────────────────────────────────────────────────

    private Object evaluateUnary(Expr.Unary unary) {
        Object operand = evaluate(unary.operand());
        return switch (unary.operator()) {
            case "!" -> !truthy(operand);
            case "-" -> -requireNumber(operand, "-");
            default  -> throw new ExpressionException("Operador unário desconhecido: " + unary.operator());
        };
    }

    private Object evaluateBinary(Expr.Binary binary) {
        // && e || avaliam o lado direito só quando necessário: `x != '' && number(x) > 10` depende
        // desse curto-circuito para não estourar em x vazio.
        if ("&&".equals(binary.operator())) {
            return truthy(evaluate(binary.left())) && truthy(evaluate(binary.right()));
        }
        if ("||".equals(binary.operator())) {
            return truthy(evaluate(binary.left())) || truthy(evaluate(binary.right()));
        }

        Object left = evaluate(binary.left());
        Object right = evaluate(binary.right());

        return switch (binary.operator()) {
            case "==" -> equals(left, right);
            case "!=" -> !equals(left, right);
            case "<"  -> requireNumber(left, "<")  <  requireNumber(right, "<");
            case "<=" -> requireNumber(left, "<=") <= requireNumber(right, "<=");
            case ">"  -> requireNumber(left, ">")  >  requireNumber(right, ">");
            case ">=" -> requireNumber(left, ">=") >= requireNumber(right, ">=");
            case "+"  -> add(left, right);
            case "-"  -> requireNumber(left, "-") - requireNumber(right, "-");
            case "*"  -> requireNumber(left, "*") * requireNumber(right, "*");
            case "/"  -> divide(left, right);
            case "%"  -> modulo(left, right);
            default   -> throw new ExpressionException("Operador desconhecido: " + binary.operator());
        };
    }

    private boolean equals(Object left, Object right) {
        Double leftNumber = asNumber(left);
        Double rightNumber = asNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.doubleValue() == rightNumber.doubleValue();
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return truthy(left) == truthy(right);
        }
        return text(left).trim().equalsIgnoreCase(text(right).trim());
    }

    private Object add(Object left, Object right) {
        Double leftNumber = asNumber(left);
        Double rightNumber = asNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber + rightNumber;
        }
        return text(left) + text(right);
    }

    private Object divide(Object left, Object right) {
        double divisor = requireNumber(right, "/");
        if (divisor == 0) throw new ExpressionException("Divisão por zero.");
        return requireNumber(left, "/") / divisor;
    }

    private Object modulo(Object left, Object right) {
        double divisor = requireNumber(right, "%");
        if (divisor == 0) throw new ExpressionException("Módulo por zero.");
        return requireNumber(left, "%") % divisor;
    }

    // ─── Funções ──────────────────────────────────────────────────────────────────

    private Object evaluateCall(Expr.Call call) {
        List<Expr> args = call.arguments();
        String name = call.function().toLowerCase();

        return switch (name) {
            case "len"        -> (double) text(arg(args, 0, name)).length();
            case "lower"      -> text(arg(args, 0, name)).toLowerCase();
            case "upper"      -> text(arg(args, 0, name)).toUpperCase();
            case "trim"       -> text(arg(args, 0, name)).trim();
            case "isempty"    -> text(arg(args, 0, name)).trim().isEmpty();
            case "number"     -> {
                Double parsed = asNumber(arg(args, 0, name));
                if (parsed == null) throw new ExpressionException("number(): '" + text(arg(args, 0, name)) + "' não é número.");
                yield parsed;
            }
            case "isnumber"   -> asNumber(arg(args, 0, name)) != null;
            case "contains"   -> text(arg(args, 0, name)).toLowerCase().contains(text(arg(args, 1, name)).toLowerCase());
            case "startswith" -> text(arg(args, 0, name)).toLowerCase().startsWith(text(arg(args, 1, name)).toLowerCase());
            case "endswith"   -> text(arg(args, 0, name)).toLowerCase().endsWith(text(arg(args, 1, name)).toLowerCase());
            case "default"    -> {
                Object value = arg(args, 0, name);
                yield text(value).trim().isEmpty() ? arg(args, 1, name) : value;
            }
            default -> throw new ExpressionException("Função desconhecida: '" + call.function() + "'.");
        };
    }

    private Object arg(List<Expr> args, int index, String function) {
        if (index >= args.size()) {
            throw new ExpressionException(function + "(): faltam argumentos (esperava pelo menos " + (index + 1) + ").");
        }
        return evaluate(args.get(index));
    }

    // ─── Conversões ───────────────────────────────────────────────────────────────

    /**
     * Verdade de um valor: booleano é ele mesmo, número é "diferente de zero", texto é "não vazio".
     * Precisa existir porque {@code expression} pode devolver qualquer um dos três e o nó IF tem que
     * escolher um ramo de qualquer forma.
     */
    static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Double number) return number != 0;
        String asText = value.toString().trim();
        if (asText.equalsIgnoreCase("true"))  return true;
        if (asText.equalsIgnoreCase("false")) return false;
        return !asText.isEmpty();
    }

    /** Texto de saída: número inteiro sai sem ".0", que é o que o usuário espera ver na mensagem. */
    static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Double number) {
            if (number == Math.floor(number) && !number.isInfinite() && Math.abs(number) < 1e15) {
                return String.valueOf(number.longValue());
            }
            return String.valueOf(number.doubleValue());
        }
        return value.toString();
    }

    /** @return null quando não é número — quem chama decide entre coagir e falhar. */
    private static Double asNumber(Object value) {
        if (value instanceof Double number) return number;
        if (value instanceof Boolean) return null;
        String asText = value == null ? "" : value.toString().trim().replace(',', '.');
        if (asText.isEmpty()) return null;
        try {
            return Double.valueOf(asText);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double requireNumber(Object value, String operator) {
        Double number = asNumber(value);
        if (number == null) {
            throw new ExpressionException("Operador '" + operator + "' exige número, mas recebeu '"
                    + text(value) + "'.");
        }
        return number;
    }
}
