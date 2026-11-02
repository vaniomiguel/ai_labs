package com.example.ollama;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the Spring Boot app and discovers the chat and Outsmart feature packages. */
@SpringBootApplication
public class OllamaSpringBootApplication {

    /** Runs the application with the supplied command-line configuration. */
    public static void main(String[] args) {
        SpringApplication.run(OllamaSpringBootApplication.class, args);
    }
}
