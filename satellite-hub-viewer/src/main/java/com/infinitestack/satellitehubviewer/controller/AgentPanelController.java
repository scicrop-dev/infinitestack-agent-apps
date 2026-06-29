package com.infinitestack.satellitehubviewer.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;

@Controller
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/satellite-hub-viewer}")
public class AgentPanelController {

    @Value("${infinitestack.plugin.base-path:/api/plugins/satellite-hub-viewer}")
    private String pluginBasePath;

    @Value("${infinitestack.plugin.assets-path:${infinitestack.plugin.base-path}/assets}")
    private String pluginAssetsPath;

    // Tile server que reprojeta COG (UTM → Web Mercator) e serve tiles XYZ ao MapLibre.
    @Value("${satellite.cog.tiler:https://titiler.xyz}")
    private String cogTiler;

    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("pluginBasePath", pluginBasePath);
        model.addAttribute("pluginAssetsPath", pluginAssetsPath);
        model.addAttribute("cogTiler", cogTiler);
        return "isp-index";
    }

    @GetMapping("/status")
    public String status(Model model, HttpServletRequest request) {
        model.addAttribute("pluginBasePath", pluginBasePath);
        model.addAttribute("pluginAssetsPath", pluginAssetsPath);
        model.addAttribute("pluginStatus", "healthy");
        model.addAttribute("pluginPath", request.getRequestURI());
        return "isp-status";
    }
}
