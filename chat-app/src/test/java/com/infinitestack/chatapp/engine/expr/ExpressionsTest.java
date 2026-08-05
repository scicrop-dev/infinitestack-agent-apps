package com.infinitestack.chatapp.engine.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Fase 6 — a linguagem de expressões. */
class ExpressionsTest {

    private static final Map<String, String> VARS = Map.of(
            "nome", "Thales",
            "opcao", "1",
            "idade", "35",
            "valor", "10.5",
            "vazio", "  ",
            "resposta", " SIM ");

    private boolean cond(String source) {
        return Expressions.condition(source, VARS);
    }

    private String text(String source) {
        return Expressions.text(source, VARS);
    }

    // ─── Comparação e lógica ──────────────────────────────────────────────────────

    @Test
    void comparaTextoENumero() {
        assertTrue(cond("opcao == '1'"));
        assertTrue(cond("idade > 30"));
        assertTrue(cond("idade >= 35 && valor < 11"));
        assertFalse(cond("idade < 30 || opcao == '2'"));
    }

    @Test
    void igualdadeIgnoraCaixaEEspacosComoOOperadorSimples() {
        // As duas formas de escrever condição precisam concordar: quem migrar de eq para ==
        // não pode ver o fluxo mudar de comportamento sem mudar de intenção.
        assertTrue(cond("resposta == 'sim'"));
        assertTrue(cond("nome == 'THALES'"));
    }

    @Test
    void aceitaAsDuasGrafiasDeOperadorLogico() {
        assertTrue(cond("opcao == '1' and idade > 10"));
        assertTrue(cond("opcao == '9' or nome == 'Thales'"));
        assertTrue(cond("not (opcao == '9')"));
        assertTrue(cond("!(opcao == '9')"));
    }

    @Test
    void curtoCircuitoImpedeAvaliarOLadoDireito() {
        // Sem curto-circuito, number(vazio) estouraria e a condição inteira viraria erro.
        assertFalse(cond("vazio != '  ' && number(vazio) > 1"));
    }

    // ─── Aritmética e texto ───────────────────────────────────────────────────────

    @Test
    void somaNumeroMasConcatenaTexto() {
        assertEquals("45", text("idade + 10"));
        assertEquals("Olá, Thales", text("'Olá, ' + nome"));
    }

    @Test
    void inteiroSaiSemCasaDecimal() {
        // 35 + 10 é 45.0 internamente; mandar "45.0" ao usuário seria vazamento de implementação.
        assertEquals("45", text("idade + 10"));
        assertEquals("21", text("valor * 2"));
    }

    @Test
    void respeitaPrecedenciaEParenteses() {
        assertEquals("7", text("1 + 2 * 3"));
        assertEquals("9", text("(1 + 2) * 3"));
    }

    // ─── Funções ──────────────────────────────────────────────────────────────────

    @Test
    void funcoesDeTexto() {
        assertEquals("THALES", text("upper(nome)"));
        assertEquals("thales", text("lower(nome)"));
        assertEquals("SIM", text("trim(resposta)"));
        assertEquals("6", text("len(nome)"));
        assertTrue(cond("contains(nome, 'hal')"));
        assertTrue(cond("startsWith(nome, 'Th')"));
        assertTrue(cond("isEmpty(vazio)"));
        assertTrue(cond("isNumber(idade)"));
        assertFalse(cond("isNumber(nome)"));
    }

    @Test
    void defaultResolveVariavelNaoPreenchida() {
        assertEquals("Thales", text("default(apelido, nome)"));
        assertEquals("Thales", text("default(vazio, nome)"));
    }

    @Test
    void variavelNaoDefinidaEhTextoVazio() {
        assertEquals("", text("inexistente"));
        assertTrue(cond("isEmpty(inexistente)"));
    }

    // ─── Divergência deliberada do operador simples ───────────────────────────────

    @Test
    void comparacaoNumericaComTextoFalhaEmVezDeDevolverFalso() {
        // ConditionEvaluator.gt devolveria false aqui. Em expressão, o erro aparece — quem escreve
        // expressão está escrevendo código, e o dado malformado é justamente o que se quer detectar.
        ExpressionException e = assertThrows(ExpressionException.class, () -> cond("nome > 10"));
        assertTrue(e.getMessage().contains("exige número"));
    }

    @Test
    void divisaoPorZeroFalha() {
        assertThrows(ExpressionException.class, () -> text("10 / 0"));
    }

    // ─── Verdade de valores não booleanos ─────────────────────────────────────────

    @Test
    void regraDeVerdadeCobreTextoENumero() {
        assertTrue(cond("nome"));          // texto não vazio
        assertFalse(cond("vazio"));        // só espaços
        assertFalse(cond("inexistente"));  // ausente
        assertTrue(cond("idade"));         // número diferente de zero
        assertFalse(cond("0"));
    }

    // ─── Sintaxe ──────────────────────────────────────────────────────────────────

    @Test
    void validateAceitaSintaxeCorretaEApontaErro() {
        assertNull(Expressions.validate("opcao == '1' && len(nome) > 2"));
        assertNotNull(Expressions.validate("opcao == "));
        assertNotNull(Expressions.validate("(opcao == '1'"));
        assertNotNull(Expressions.validate("'texto sem fechar"));
    }

    @Test
    void funcaoDesconhecidaFalhaComONome() {
        ExpressionException e = assertThrows(ExpressionException.class, () -> text("inverter(nome)"));
        assertTrue(e.getMessage().contains("inverter"));
    }
}
