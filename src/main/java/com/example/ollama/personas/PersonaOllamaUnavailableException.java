package com.example.ollama.personas;

class PersonaOllamaUnavailableException extends RuntimeException {
    PersonaOllamaUnavailableException(String message) {
        super(message);
    }

    PersonaOllamaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
