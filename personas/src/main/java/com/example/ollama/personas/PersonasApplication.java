package com.example.ollama.personas;

import com.example.ollama.client.LocalOllamaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Starts the standalone persona conversation application. */
@SpringBootApplication
@EnableConfigurationProperties(LocalOllamaProperties.class)
public class PersonasApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonasApplication.class, args);
    }
}
