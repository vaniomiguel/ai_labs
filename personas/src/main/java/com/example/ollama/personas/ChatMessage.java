package com.example.ollama.personas;

/** Provider-independent chat message; adapters translate the role to their own API. */
record ChatMessage(Role role, String text) {
    enum Role { SYSTEM, USER, ASSISTANT }
}
