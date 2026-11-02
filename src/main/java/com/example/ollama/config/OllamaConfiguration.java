package com.example.ollama.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures the existing chat client's OpenAI-compatible connection to Ollama. */
@Configuration
public class OllamaConfiguration {

    /** Creates the chat client from openai.* settings; Outsmart uses its own settings. */
    @Bean
    OpenAIClient openAIClient(
            @Value("${openai.base-url}") String baseUrl,
            @Value("${openai.api-key}") String apiKey) {
        return OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }
}
