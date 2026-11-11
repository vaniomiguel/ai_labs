package com.example.ollama.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ollama.client.TextGenerationClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Verifies the standalone application's wiring and routes without a listening server. */
@SpringBootTest
class ChatApplicationTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void chatEndpointUsesTheConfiguredClient() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"text\":\"Reply to: hello\"}"));
    }

    @Test
    void blankPromptIsRejected() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedClient {

        @Bean
        @Primary
        TextGenerationClient scriptedTextGenerationClient() {
            return (systemPrompt, userPrompt, format) -> "Reply to: " + userPrompt;
        }
    }
}
