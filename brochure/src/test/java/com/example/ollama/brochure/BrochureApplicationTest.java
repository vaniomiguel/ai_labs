package com.example.ollama.brochure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ollama.client.TextGenerationClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Verifies that the brochure API loads its shared client without loading the chat application. */
@SpringBootTest
class BrochureApplicationTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void brochureEndpointIsWiredWithTheSharedClient() throws Exception {
        assertThat(context.getBeansOfType(TextGenerationClient.class)).hasSize(1);
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/api/brochures").param("url", "ftp://example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Website URL must use HTTP or HTTPS and include a host"));
    }
}
