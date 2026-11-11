package com.example.ollama.chat;

import com.example.ollama.client.LocalOllamaConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Starts the standalone chat API. */
@SpringBootApplication
@Import(LocalOllamaConfiguration.class)
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
