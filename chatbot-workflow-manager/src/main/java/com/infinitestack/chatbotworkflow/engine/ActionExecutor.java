package com.infinitestack.chatbotworkflow.engine;

import java.util.LinkedHashMap;
import java.util.Map;

import com.infinitestack.chatbotworkflow.domain.Node;

/**
 * A camada de efeitos prevista na arquitetura (docs/001-architecture.md): tudo que o fluxo faz
 * <b>fora</b> da conversa — consultar banco, chamar serviço — passa por aqui.
 *
 * Existe para o motor continuar sendo função pura. O motor decide <i>que</i> um efeito deve
 * acontecer e com quais parâmetros; quem abre conexão é esta interface, injetada como dependência
 * da chamada. Em teste entra um executor de mentira e o fluxo inteiro roda sem banco nem rede.
 *
 * <b>Efeitos são síncronos dentro do turno.</b> Um DB_QUERY precisa devolver valor antes do IF
 * seguinte decidir o ramo — não há como adiar sem partir o turno em dois e reintroduzir no motor o
 * estado que ele não tem. O custo é que uma consulta lenta segura a thread da mensagem; por isso
 * todo executor tem timeout obrigatório.
 */
public interface ActionExecutor {

    /**
     * Executa o efeito descrito pelo nó.
     *
     * @param node      nó DB_QUERY ou HTTP_REQUEST, com a config já como veio do fluxo
     * @param variables variáveis do escopo atual, para ligação de parâmetros
     * @return variáveis a mesclar no escopo, mais detalhes para rastro — ou um erro
     */
    Result execute(Node node, Map<String, String> variables);

    /**
     * @param variables variáveis produzidas pelo efeito (vazio em caso de erro)
     * @param details   metadados do que foi executado, para o histórico
     * @param error     mensagem quando o efeito falhou; null em sucesso
     */
    record Result(Map<String, String> variables, Map<String, String> details, String error) {

        public Result {
            variables = (variables == null) ? Map.of() : Map.copyOf(variables);
            details   = (details == null)   ? Map.of() : Map.copyOf(details);
        }

        public static Result ok(Map<String, String> variables, Map<String, String> details) {
            return new Result(variables, details, null);
        }

        public static Result failure(String error) {
            return new Result(Map.of(), Map.of(), error);
        }

        public boolean hasError() {
            return error != null;
        }
    }

    /**
     * Executor padrão quando nenhum está configurado — e o executor usado nos testes do motor.
     *
     * Recusa qualquer efeito em vez de virar no-op silencioso: um fluxo cuja consulta "funcionou"
     * mas não trouxe nada tomaria o ramo errado sem nenhum sinal de que o efeito não aconteceu.
     */
    ActionExecutor DENY_ALL = (node, variables) -> Result.failure(
            "Nó '" + node.id() + "' (" + node.type() + ") exige um Action Executor configurado.");

    /** Helper para montar o mapa de variáveis de saída preservando a ordem de declaração. */
    static Map<String, String> newVariables() {
        return new LinkedHashMap<>();
    }
}
