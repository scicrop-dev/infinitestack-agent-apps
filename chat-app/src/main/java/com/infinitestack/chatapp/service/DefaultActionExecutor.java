package com.infinitestack.chatapp.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitestack.chatapp.domain.Node;
import com.infinitestack.chatapp.engine.ActionExecutor;
import com.infinitestack.chatapp.engine.SqlGuard;
import com.infinitestack.chatapp.engine.ConfigLists;

/**
 * Executa os efeitos de DB_QUERY e HTTP_REQUEST (fase 3 do roadmap).
 *
 * <b>Postura de segurança, e o porquê de cada padrão:</b>
 * <ul>
 *   <li><b>Banco: ligado, somente leitura, parametrizado.</b> É o caso de uso central — consultar
 *       o dado do cliente durante o atendimento — e o datasource já é o do host. O que protege não
 *       é desligar, é {@code SqlGuard}: só SELECT, comando único, e variável entra exclusivamente
 *       como parâmetro JDBC.</li>
 *   <li><b>HTTP: desligado, com allowlist obrigatória.</b> Instalação air-gapped é cenário real no
 *       IS, e um nó que chama URL arbitrária é saída de dados para fora do perímetro. Ligar exige
 *       duas decisões conscientes do operador: habilitar <i>e</i> listar os hosts. Habilitar sem
 *       listar não libera nada.</li>
 *   <li><b>Timeout em tudo.</b> O efeito roda dentro do turno, na thread da mensagem — sem teto,
 *       um endpoint pendurado prende a conversa.</li>
 * </ul>
 */
@Component
public class DefaultActionExecutor implements ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultActionExecutor.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${chatapp.actions.db.enabled:true}")
    private boolean dbEnabled;

    @Value("${chatapp.actions.db.max-rows:100}")
    private int dbMaxRows;

    @Value("${chatapp.actions.db.timeout-seconds:5}")
    private int dbTimeoutSeconds;

    @Value("${chatapp.actions.http.enabled:false}")
    private boolean httpEnabled;

    @Value("${chatapp.actions.http.allowed-hosts:}")
    private String httpAllowedHosts;

    @Value("${chatapp.actions.http.timeout-seconds:10}")
    private int httpTimeoutSeconds;

    @Value("${chatapp.actions.http.max-response-bytes:262144}")
    private int httpMaxResponseBytes;

    public DefaultActionExecutor(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)   // redirect escaparia da allowlist
                .build();
    }

    @Override
    public Result execute(Node node, Map<String, String> variables) {
        try {
            return switch (node.type()) {
                case DB_QUERY     -> executeDbQuery(node, variables);
                case HTTP_REQUEST -> executeHttpRequest(node, variables);
                default -> Result.failure("Tipo de nó não executável aqui: " + node.type());
            };
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (Exception e) {
            log.warn("[chat-app] efeito falhou no nó {}: {}", node.id(), e.toString());
            return Result.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ─── DB_QUERY ─────────────────────────────────────────────────────────────────

    /**
     * config:
     * <pre>
     *   sql          SELECT com parâmetros nomeados (:nome) — obrigatório
     *   params       variáveis a ligar aos parâmetros, separadas por vírgula
     *   output       "coluna:variavel, coluna2:variavel2" — se omitido, todas as colunas da
     *                primeira linha viram variáveis com o nome da coluna
     *   countInto    variável que recebe a quantidade de linhas
     * </pre>
     */
    private Result executeDbQuery(Node node, Map<String, String> variables) {
        if (!dbEnabled) {
            return Result.failure("Consultas ao banco estão desabilitadas (chatapp.actions.db.enabled=false).");
        }
        String sql = node.config("sql");
        SqlGuard.check(sql);

        Map<String, Object> bindings = new LinkedHashMap<>();
        List<String> declared = ConfigLists.names(node.config("params", ""));
        for (String name : declared) {
            bindings.put(name, variables.getOrDefault(name, ""));
        }
        // Parâmetro citado no SQL mas não declarado seria um erro de bind obscuro do Spring;
        // falhar aqui aponta o nome que falta.
        for (String required : SqlGuard.namedParameters(sql)) {
            if (!bindings.containsKey(required)) {
                throw new IllegalArgumentException("SQL usa :" + required
                        + " mas '" + required + "' não está em 'params'.");
            }
        }

        jdbc.getJdbcTemplate().setQueryTimeout(dbTimeoutSeconds);
        jdbc.getJdbcTemplate().setMaxRows(dbMaxRows);
        List<Map<String, Object>> rows = jdbc.queryForList(sql, bindings);

        Map<String, String> produced = new LinkedHashMap<>();
        String countInto = node.config("countInto");
        if (countInto != null) {
            produced.put(countInto, String.valueOf(rows.size()));
        }

        if (!rows.isEmpty()) {
            Map<String, Object> first = rows.get(0);
            String outputSpec = node.config("output");
            if (outputSpec == null) {
                first.forEach((column, value) -> produced.put(column, asText(value)));
            } else {
                for (Map.Entry<String, String> mapping : ConfigLists.mapping(outputSpec).entrySet()) {
                    produced.put(mapping.getValue(), asText(findIgnoringCase(first, mapping.getKey())));
                }
            }
        }

        Map<String, String> details = Map.of(
                "rows", String.valueOf(rows.size()),
                "params", String.join(",", declared));
        return Result.ok(produced, details);
    }

    // ─── HTTP_REQUEST ─────────────────────────────────────────────────────────────

    /**
     * config:
     * <pre>
     *   url          obrigatório, aceita {{variavel}} (valores são URL-encoded na substituição)
     *   method       GET (padrão) ou POST
     *   body         corpo para POST, aceita {{variavel}}
     *   headers      "Nome=valor, Nome2=valor2"
     *   output       "caminho.json:variavel, outro:variavel2" — caminho por ponto na resposta JSON
     *   statusInto   variável que recebe o código HTTP
     * </pre>
     */
    private Result executeHttpRequest(Node node, Map<String, String> variables) throws Exception {
        if (!httpEnabled) {
            return Result.failure("Chamadas HTTP estão desabilitadas (chatapp.actions.http.enabled=false).");
        }
        List<String> allowed = ConfigLists.names(httpAllowedHosts);
        if (allowed.isEmpty()) {
            return Result.failure("Nenhum host liberado — preencha chatapp.actions.http.allowed-hosts.");
        }

        String url = interpolateEncoded(node.config("url"), variables);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Nó HTTP_REQUEST exige config 'url'.");
        }
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("URL deve ser http ou https: " + url);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (allowed.stream().noneMatch(entry -> host.equals(entry.toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("Host '" + host + "' não está em chatapp.actions.http.allowed-hosts.");
        }

        String method = node.config("method", "GET").trim().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new IllegalArgumentException("method deve ser GET ou POST.");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(httpTimeoutSeconds));
        for (Map.Entry<String, String> header : ConfigLists.mapping(node.config("headers", "")).entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        if (method.equals("POST")) {
            String body = com.infinitestack.chatapp.engine.VariableInterpolator
                    .interpolate(node.config("body", ""), variables);
            request.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.GET();
        }

        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (body.length() > httpMaxResponseBytes) {
            throw new IllegalArgumentException("Resposta maior que o limite de "
                    + httpMaxResponseBytes + " bytes.");
        }

        Map<String, String> produced = new LinkedHashMap<>();
        String statusInto = node.config("statusInto");
        if (statusInto != null) {
            produced.put(statusInto, String.valueOf(response.statusCode()));
        }

        String outputSpec = node.config("output");
        if (outputSpec != null && !body.isBlank()) {
            JsonNode json = objectMapper.readTree(body);
            for (Map.Entry<String, String> mapping : ConfigLists.mapping(outputSpec).entrySet()) {
                produced.put(mapping.getValue(), asText(jsonPath(json, mapping.getKey())));
            }
        }

        Map<String, String> details = Map.of(
                "status", String.valueOf(response.statusCode()),
                "method", method,
                "host", host);
        return Result.ok(produced, details);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Interpolação para URL: o valor é percent-encoded na substituição.
     *
     * Sem isso, uma resposta com espaço ou {@code &} montaria uma URL diferente da pretendida —
     * no melhor caso quebra, no pior injeta um parâmetro de query a mais.
     */
    private String interpolateEncoded(String template, Map<String, String> variables) {
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            String placeholder = "{{" + variable.getKey() + "}}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder,
                        URLEncoder.encode(variable.getValue() == null ? "" : variable.getValue(),
                                          StandardCharsets.UTF_8));
            }
        }
        // Placeholder que sobrou não tem valor: vira vazio, como na interpolação normal.
        return result.replaceAll("\\{\\{\\s*[a-zA-Z0-9_.-]+\\s*}}", "");
    }

    /** Caminho por ponto: {@code dados.0.nome} desce em objeto e em array. */
    private Object jsonPath(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isMissingNode()) return null;
            current = segment.matches("\\d+") && current.isArray()
                    ? current.get(Integer.parseInt(segment))
                    : current.get(segment);
        }
        if (current == null || current.isNull() || current.isMissingNode()) return null;
        return current.isValueNode() ? current.asText() : current.toString();
    }

    /** Nome de coluna volta do driver em caixa imprevisível (Postgres minúsculo, Oracle maiúsculo). */
    private Object findIgnoringCase(Map<String, Object> row, String column) {
        Object direct = row.get(column);
        if (direct != null || row.containsKey(column)) return direct;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) return entry.getValue();
        }
        return null;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
