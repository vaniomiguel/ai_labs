package com.example.ollama.brochure;

import com.example.ollama.client.LocalOllamaConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Starts the standalone company brochure API. */
@SpringBootApplication
@Import(LocalOllamaConfiguration.class)
public class BrochureApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrochureApplication.class, args);
    }
}
