package com.infinitestack.chatbotworkflow.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.infinitestack.chatbotworkflow.repository.WorkflowRepository;
import com.infinitestack.chatbotworkflow.service.WorkflowService;

/**
 * CRUD dos fluxos.
 *
 * O corpo de PUT e do /validate é o JSON do workflow <b>cru</b> ({@code consumes = TEXT_PLAIN} não
 * serviria porque o editor manda application/json): recebe-se como String em vez de um DTO tipado
 * porque a mensagem de erro de parse — "vírgula faltando na linha 12" — é o produto principal
 * deste endpoint, e o binder do Spring a substituiria por um 400 sem detalhe.
 */
@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/chatbot-workflow-manager}/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return workflowService.list().stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.id(),
                        "name", s.name() == null ? s.id() : s.name(),
                        "updatedAt", s.updatedAt().toString()))
                .toList();
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> get(@PathVariable String id) {
        WorkflowRepository.Row row = workflowService.get(id);
        if (row == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Workflow '" + id + "' não encontrado."));
        }
        return ResponseEntity.ok(Map.of(
                "id", row.id(),
                "name", row.name() == null ? row.id() : row.name(),
                "updatedAt", row.updatedAt().toString(),
                "definition", row.definition()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> save(@PathVariable String id, @RequestBody String body) {
        WorkflowService.SaveResult result = workflowService.save(id, body);
        Map<String, Object> payload = Map.of(
                "saved", result.saved(),
                "id", result.id() == null ? "" : result.id(),
                "errors", result.errors(),
                "warnings", result.warnings());
        // 422 e não 400: o JSON é sintaticamente aceitável como requisição, o que falhou foram as
        // regras do fluxo. A distinção importa para o editor, que trata os dois casos igual mas
        // deixa o log do IS legível.
        return result.saved() ? ResponseEntity.ok(payload) : ResponseEntity.unprocessableEntity().body(payload);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody String body) {
        WorkflowService.SaveResult result = workflowService.validate(body);
        return Map.of(
                "valid", result.errors().isEmpty(),
                "id", result.id() == null ? "" : result.id(),
                "errors", result.errors(),
                "warnings", result.warnings());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (!workflowService.delete(id)) {
            return ResponseEntity.status(404).body(Map.of("error", "Workflow '" + id + "' não encontrado."));
        }
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }
}
