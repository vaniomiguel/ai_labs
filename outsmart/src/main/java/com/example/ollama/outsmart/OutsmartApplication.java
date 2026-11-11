package com.example.ollama.outsmart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the standalone Outsmart game and its browser interface. */
@SpringBootApplication
public class OutsmartApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutsmartApplication.class, args);
    }
}
