package com.example.ollama.config;

import com.example.ollama.chat.LocalOllamaTextGenerationClient;
import com.example.ollama.chat.TextGenerationClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects the local Ollama implementation for chat requests. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalOllamaProperties.class)
public class LocalOllamaConfiguration {

    @Bean
    TextGenerationClient textGenerationClient(LocalOllamaProperties properties) {
        return new LocalOllamaTextGenerationClient(properties);
    }
}
