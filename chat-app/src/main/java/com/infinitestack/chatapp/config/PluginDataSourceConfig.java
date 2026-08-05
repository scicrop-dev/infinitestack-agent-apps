package com.infinitestack.chatapp.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * DataSource construído a partir de spring.datasource.*, que por sua vez vem das variáveis
 * IS_DATASOURCE_* injetadas pelo host. O app nunca define conexão própria
 * ({@code allows_own_datasource: false} no manifest).
 */
@Configuration
public class PluginDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }
}
