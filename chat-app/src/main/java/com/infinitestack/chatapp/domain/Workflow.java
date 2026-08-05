package com.infinitestack.chatapp.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definição de um fluxo conversacional: um nó inicial e o grafo de nós alcançáveis a partir dele.
 *
 * A instância é imutável e carrega um índice id→nó montado uma vez na construção — o motor faz
 * uma busca por id a cada passo, e varrer a lista em cada passo seria O(n) por nó percorrido.
 */
public record Workflow(String id, String name, String start, List<Node> nodes, Map<String, Node> index) {

    public static Workflow of(String id, String name, String start, List<Node> nodes) {
        Map<String, Node> index = new LinkedHashMap<>();
        for (Node node : nodes) {
            // Nó sem id não entra no índice (Map.copyOf não aceita chave null) e nó com id
            // repetido sobrescreve. Nenhum dos dois é rejeitado aqui: o objetivo é montar um
            // Workflow inspecionável para o validador reportar os dois casos com o id na mão.
            if (node.id() != null) {
                index.put(node.id(), node);
            }
        }
        return new Workflow(id, name, start, List.copyOf(nodes), Map.copyOf(index));
    }

    /** Retorna null se o id não existir — o motor trata isso como erro de fluxo, não exceção. */
    public Node node(String nodeId) {
        return nodeId == null ? null : index.get(nodeId);
    }
}
