package com.example.ollama.personas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Verifies persona configuration and template discovery in its own application. */
@SpringBootTest
class PersonasApplicationTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void personaPageRendersWithItsOwnModelClient() throws Exception {
        assertThat(context.getBeansOfType(ModelChatClient.class)).hasSize(1);
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/personas"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Persona Conversations")));
    }
}
