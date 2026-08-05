package com.infinitestack.chatapp.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A UI do app. O IS abre {base_path}/ dentro de um iframe na seção Agents → Apps.
 */
@Controller
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/chat-app}")
public class AgentPanelController {

    @Value("${infinitestack.plugin.base-path:/api/plugins/chat-app}")
    private String pluginBasePath;

    @Value("${infinitestack.plugin.assets-path:${infinitestack.plugin.base-path}/assets}")
    private String pluginAssetsPath;

    @GetMapping({"", "/"})
    public String index(Model model) {
        populateModel(model);
        return "isp-index";
    }

    @GetMapping("/status")
    public String status(Model model, HttpServletRequest request) {
        populateModel(model);
        model.addAttribute("pluginStatus", "healthy");
        model.addAttribute("pluginPath", request.getRequestURI());
        return "isp-status";
    }

    private void populateModel(Model model) {
        model.addAttribute("pluginBasePath", pluginBasePath);
        model.addAttribute("pluginAssetsPath", pluginAssetsPath);
    }
}
