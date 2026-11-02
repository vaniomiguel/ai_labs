package com.example.ollama.chat;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the existing single-prompt chat endpoint, separate from Outsmart games. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    /** Injects the chat service used to answer requests. */
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** Validates a prompt and returns the model's reply. */
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.ask(request.prompt());
    }
}
