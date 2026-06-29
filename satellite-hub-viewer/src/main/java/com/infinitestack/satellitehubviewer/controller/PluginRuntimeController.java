package com.infinitestack.satellitehubviewer.controller;

import com.infinitestack.satellitehubviewer.service.SatelliteDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/satellite-hub-viewer}/api")
public class PluginRuntimeController {

    private final SatelliteDataService dataService;

    public PluginRuntimeController(SatelliteDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping("/runtime-health")
    public String runtimeHealth() {
        return "ok";
    }

    @GetMapping("/db-status")
    public Map<String, Object> dbStatus() {
        return dataService.healthCheck();
    }
}
