package com.example.ollama.personas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PersonaConversationRequest(
        @NotBlank @Size(max = 500) String topic,
        @NotNull @Valid Persona first,
        @NotNull @Valid Persona second) {

    public record Persona(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 160) String model,
            @NotBlank @Size(max = 2000) String personality) {
    }
}
