package com.example.ollama.brochure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BrochureControllerTest {

    @Test
    void returnsGeneratedMarkdownForUrlQueryParameter() throws Exception {
        BrochureService service = mock(BrochureService.class);
        when(service.generateBrochure("https://example.com"))
                .thenReturn("# Example Co\n\nA brochure.");
        var mvc = MockMvcBuilders.standaloneSetup(new BrochureController(service)).build();

        mvc.perform(get("/api/brochures").param("url", "https://example.com"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string("# Example Co\n\nA brochure."));

        verify(service).generateBrochure("https://example.com");
    }
}
