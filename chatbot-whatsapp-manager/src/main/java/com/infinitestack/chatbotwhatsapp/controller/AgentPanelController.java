package com.infinitestack.chatbotwhatsapp.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.http.HttpServletRequest;

@Controller
@RequestMapping("${infinitestack.plugin.base-path:/api/plugins/chatbot-whatsapp-manager}")
public class AgentPanelController {

    @Value("${infinitestack.plugin.base-path:/api/plugins/chatbot-whatsapp-manager}")
    private String pluginBasePath;

    @Value("${infinitestack.plugin.assets-path:${infinitestack.plugin.base-path}/assets}")
    private String pluginAssetsPath;

    /**
     * The host API prefix the panel calls from the browser.
     *
     * Handed to the template instead of hardcoded in the JavaScript because the panel talks to the
     * host, not to this plugin: the page runs inside the IS iframe, same origin, carrying the
     * operator's session — so it reads history and sends messages exactly like the IS front-end
     * does, and this app needs no credentials of its own.
     */
    @Value("${infinitestack.host.api-path:/api}")
    private String hostApiPath;

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
        model.addAttribute("hostApiPath", hostApiPath);
    }
}
