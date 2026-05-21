package com.infinitestack.recomendador.controller;

import com.infinitestack.recomendador.dto.SimulationRequest;
import com.infinitestack.recomendador.dto.SimulationResponse;
import com.infinitestack.recomendador.service.DataService;
import com.infinitestack.recomendador.service.ModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/recomendador-manejo}/api")
public class RecomendadorController {

    private final DataService dataService;
    private final ModelService modelService;

    public RecomendadorController(DataService dataService, ModelService modelService) {
        this.dataService = dataService;
        this.modelService = modelService;
    }

    @GetMapping("/opcoes")
    public ResponseEntity<Map<String, Object>> opcoes() {
        return ResponseEntity.ok(Map.of(
            "varieties", dataService.getDistinctVarieties(),
            "estágios", dataService.getDistinctEstagios(),
            "ambientes", dataService.getDistinctAmbientes(),
            "meses", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        ));
    }

    @PostMapping("/simular")
    public ResponseEntity<?> simular(@RequestBody SimulationRequest req) {
        if (!modelService.isTrained()) {
            return ResponseEntity.status(503).body(Map.of(
                "error", "Modelos ainda em treinamento ou base de dados vazia."
            ));
        }
        try {
            SimulationResponse resp = modelService.simulate(req);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
