package com.infinitestack.satellitehubviewer.controller;

import com.infinitestack.satellitehubviewer.dto.DashboardStats;
import com.infinitestack.satellitehubviewer.dto.ImageDetail;
import com.infinitestack.satellitehubviewer.dto.PagedImages;
import com.infinitestack.satellitehubviewer.service.SatelliteDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/satellite-hub-viewer}/api")
public class SatelliteController {

    private static final Logger log = LoggerFactory.getLogger(SatelliteController.class);

    private final SatelliteDataService data;

    public SatelliteController(SatelliteDataService data) {
        this.data = data;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard() {
        try {
            DashboardStats stats = data.getDashboard();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return error("Falha ao carregar dashboard", e);
        }
    }

    /** FeatureCollection com todas as áreas + estatísticas agregadas (carregar uma vez). */
    @GetMapping("/areas")
    public ResponseEntity<?> areas() {
        try {
            return ResponseEntity.ok(data.getAreasFeatureCollection());
        } catch (Exception e) {
            return error("Falha ao carregar áreas", e);
        }
    }

    @GetMapping("/areas/{id}")
    public ResponseEntity<?> area(@PathVariable long id) {
        try {
            Map<String, Object> feature = data.getAreaFeature(id);
            if (feature == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Área não encontrada: " + id));
            }
            return ResponseEntity.ok(feature);
        } catch (Exception e) {
            return error("Falha ao carregar área " + id, e);
        }
    }

    /** Imagens da área no período/cobertura informados, paginadas e ordenadas por acquisition_date. */
    @GetMapping("/areas/{id}/images")
    public ResponseEntity<?> areaImages(
            @PathVariable long id,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "max_cloud_cover", required = false) Double maxCloudCover,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        try {
            int safeSize = Math.min(Math.max(size, 1), 500);
            int safePage = Math.max(page, 0);
            PagedImages result = data.getAreaImages(id, startDate, endDate, maxCloudCover, safePage, safeSize);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return error("Falha ao carregar imagens da área " + id, e);
        }
    }

    /** Imagens mais recentes de todas as áreas — exibidas ao abrir o app. */
    @GetMapping("/images/recent")
    public ResponseEntity<?> recentImages(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        try {
            int safeLimit = Math.min(Math.max(limit, 1), 200);
            return ResponseEntity.ok(data.getRecentImages(safeLimit));
        } catch (Exception e) {
            return error("Falha ao carregar imagens recentes", e);
        }
    }

    @GetMapping("/images/{id}")
    public ResponseEntity<?> image(@PathVariable long id) {
        try {
            ImageDetail detail = data.getImage(id);
            if (detail == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Imagem não encontrada: " + id));
            }
            return ResponseEntity.ok(detail);
        } catch (Exception e) {
            return error("Falha ao carregar imagem " + id, e);
        }
    }

    private ResponseEntity<Map<String, Object>> error(String message, Exception e) {
        log.error("[SatelliteController] {}: {}", message, e.getMessage());
        return ResponseEntity.status(500).body(Map.of(
                "error", message,
                "detail", e.getMessage() == null ? "" : e.getMessage()
        ));
    }
}
