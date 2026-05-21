package com.infinitestack.recomendador.controller;

import com.infinitestack.recomendador.service.DataService;
import com.infinitestack.recomendador.service.ModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/recomendador-manejo}/api")
public class PluginRuntimeController {

    private final ModelService modelService;
    private final DataService dataService;

    public PluginRuntimeController(ModelService modelService, DataService dataService) {
        this.modelService = modelService;
        this.dataService = dataService;
    }

    @GetMapping("/runtime-health")
    public String runtimeHealth() {
        return "ok";
    }

    @GetMapping("/model-status")
    public Map<String, Object> modelStatus() {
        String status = modelService.isTraining() ? "training" : modelService.isTrained() ? "ready" : "pending";
        return Map.of(
            "trained", modelService.isTrained(),
            "training", modelService.isTraining(),
            "sampleCount", modelService.getSampleCount(),
            "status", status
        );
    }

    @PostMapping("/train")
    public ResponseEntity<Map<String, Object>> train(@RequestBody(required = false) Map<String, String> body) {
        if (modelService.isTraining()) {
            return ResponseEntity.ok(Map.of("status", "already_training"));
        }
        String sql = (body != null && body.containsKey("sql")) ? body.get("sql") : dataService.getQuery();
        modelService.retrain(sql);
        return ResponseEntity.ok(Map.of("status", "started"));
    }

    @GetMapping("/config/sql")
    public Map<String, String> getSql() {
        return Map.of("sql", dataService.getQuery());
    }

    @PutMapping("/config/sql")
    public ResponseEntity<Map<String, String>> setSql(@RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        if (sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sql não pode ser vazio"));
        }
        dataService.setQuery(sql);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
