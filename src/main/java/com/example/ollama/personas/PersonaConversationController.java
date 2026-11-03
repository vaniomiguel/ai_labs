package com.example.ollama.personas;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PersonaConversationController {

    private final PersonaConversationService conversations;

    public PersonaConversationController(PersonaConversationService conversations) {
        this.conversations = conversations;
    }

    @GetMapping("/personas")
    public String page() {
        return "personas/index";
    }

    @ResponseBody
    @GetMapping("/api/personas/models")
    public List<String> models() {
        return conversations.models();
    }

    @ResponseBody
    @PostMapping("/api/personas/conversations")
    public ResponseEntity<PersonaConversationResponse> create(@Valid @RequestBody PersonaConversationRequest request) {
        var conversation = conversations.create(request);
        return ResponseEntity.created(URI.create("/api/personas/conversations/" + conversation.id()))
                .body(conversation);
    }

    @ResponseBody
    @GetMapping("/api/personas/conversations/{id}")
    public PersonaConversationResponse get(@PathVariable UUID id) {
        return conversations.get(id);
    }

    @ResponseBody
    @PostMapping("/api/personas/conversations/{id}/stop")
    public PersonaConversationResponse stop(@PathVariable UUID id) {
        return conversations.stop(id);
    }

    @ResponseBody
    @PostMapping("/api/personas/conversations/{id}/restart")
    public PersonaConversationResponse restart(@PathVariable UUID id,
            @Valid @RequestBody PersonaConversationRequest request) {
        return conversations.restart(id, request);
    }

    @ResponseBody
    @DeleteMapping("/api/personas/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        conversations.delete(id);
        return ResponseEntity.noContent().build();
    }
}
