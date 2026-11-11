package com.example.ollama.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the local text generation adapter for applications that import this configuration. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalOllamaProperties.class)
public class LocalOllamaConfiguration {

    @Bean
    TextGenerationClient textGenerationClient(LocalOllamaProperties properties) {
        return new LocalOllamaTextGenerationClient(properties);
    }
}
