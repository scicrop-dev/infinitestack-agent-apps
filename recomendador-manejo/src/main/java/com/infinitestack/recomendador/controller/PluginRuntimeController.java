package com.infinitestack.recomendador.controller;

import com.infinitestack.recomendador.service.ModelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/recomendador-manejo}/api")
public class PluginRuntimeController {

    private final ModelService modelService;

    public PluginRuntimeController(ModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping("/runtime-health")
    public String runtimeHealth() {
        return "ok";
    }

    @GetMapping("/model-status")
    public Map<String, Object> modelStatus() {
        return Map.of(
            "trained", modelService.isTrained(),
            "sampleCount", modelService.getSampleCount(),
            "status", modelService.isTrained() ? "ready" : "training"
        );
    }
}
