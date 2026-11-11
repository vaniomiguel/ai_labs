package com.example.ollama.outsmart;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Checks the isolated application and moved browser assets without opening a server port. */
@SpringBootTest
class OutsmartApplicationTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void createsAGameInTheStandaloneApplication() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/outsmart/games").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"participants":[
                                  {"name":"Alex","model":{"provider":"ollama","id":"test-model"}},
                                  {"name":"Blake","model":{"provider":"ollama","id":"test-model"}},
                                  {"name":"Charlie","model":{"provider":"ollama","id":"test-model"}}
                                ]}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void servesTheMovedBrowserAssets() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/vendor/htmx.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("htmx")));
        mvc.perform(get("/outsmart.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".site-shell")));
    }
}
