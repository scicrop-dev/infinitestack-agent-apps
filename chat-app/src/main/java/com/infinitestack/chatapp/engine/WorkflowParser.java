package com.infinitestack.chatapp.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.chatapp.domain.Node;
import com.infinitestack.chatapp.domain.NodeType;
import com.infinitestack.chatapp.domain.Workflow;

/**
 * Converte o JSON do fluxo no modelo de domínio.
 *
 * Parse e validação são passos separados de propósito: aqui só se responde "isso é um JSON com o
 * formato certo?"; se as referências entre nós fecham é problema do {@link WorkflowValidator}.
 * Misturar os dois faria erros de estrutura mascararem erros de conteúdo — o autor corrigiria um
 * de cada vez, com um round-trip por erro.
 *
 * Tipo de nó desconhecido não quebra o parse: vira um {@link Node} com {@code type} null, para o
 * validador poder apontar "tipo 'MENSAGEM' inválido no nó X" com o id em mãos.
 */
@Component
public class WorkflowParser {

    private final ObjectMapper objectMapper;

    public WorkflowParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Workflow parse(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            // getOriginalMessage() traz a causa sem o dump de contexto do Jackson, que é longo
            // demais para caber no painel de erros do editor.
            throw new WorkflowParseException("JSON inválido: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new WorkflowParseException("O workflow deve ser um objeto JSON.");
        }
        return fromJson(root);
    }

    public Workflow fromJson(JsonNode root) {
        String id = text(root, "id");
        String name = text(root, "name");
        String start = text(root, "start");

        JsonNode nodesNode = root.get("nodes");
        List<Node> nodes = new ArrayList<>();
        if (nodesNode != null && nodesNode.isArray()) {
            for (JsonNode nodeJson : nodesNode) {
                nodes.add(parseNode(nodeJson));
            }
        } else if (nodesNode != null && nodesNode.isObject()) {
            // Forma alternativa aceita: mapa id→nó. Alguns editores geram assim, e rejeitar
            // custaria ao autor reescrever o fluxo inteiro por uma diferença puramente sintática.
            for (Iterator<Map.Entry<String, JsonNode>> it = nodesNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                nodes.add(parseNode(entry.getValue(), entry.getKey()));
            }
        } else {
            throw new WorkflowParseException("O workflow deve ter um array 'nodes'.");
        }

        return Workflow.of(id, name, start, nodes);
    }

    private Node parseNode(JsonNode nodeJson) {
        return parseNode(nodeJson, text(nodeJson, "id"));
    }

    private Node parseNode(JsonNode nodeJson, String id) {
        if (nodeJson == null || !nodeJson.isObject()) {
            throw new WorkflowParseException("Cada item de 'nodes' deve ser um objeto JSON.");
        }
        NodeType type = NodeType.fromString(text(nodeJson, "type"));
        String next = text(nodeJson, "next");

        Map<String, String> config = new LinkedHashMap<>();
        JsonNode configNode = nodeJson.get("config");
        if (configNode != null && configNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = configNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode value = entry.getValue();
                // Tudo vira texto: o modelo de variáveis desta fase é textual, e aceitar
                // número/booleano aqui só adiaria a conversão para dentro do motor.
                config.put(entry.getKey(), value.isValueNode() ? value.asText() : value.toString());
            }
        }
        return new Node(id, type, next, config);
    }

    /** Retorna null para ausente, null literal ou string vazia — os três significam "não informado". */
    private String text(JsonNode parent, String field) {
        if (parent == null) return null;
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) return null;
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    /** JSON malformado ou estruturalmente impossível de virar Workflow. */
    public static class WorkflowParseException extends RuntimeException {
        public WorkflowParseException(String message) { super(message); }
    }
}
