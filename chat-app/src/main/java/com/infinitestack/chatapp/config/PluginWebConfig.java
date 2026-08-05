package com.infinitestack.chatapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PluginWebConfig implements WebMvcConfigurer {

    @Value("${infinitestack.plugin.base-path:/api/plugins/chat-app}")
    private String pluginBasePath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(pluginBasePath + "/assets/**")
                .addResourceLocations("classpath:/static/");
    }
}
