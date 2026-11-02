package com.example.ollama.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "prompt must not be blank")
        @Size(max = 20_000, message = "prompt must be at most 20000 characters")
        String prompt) {
}
