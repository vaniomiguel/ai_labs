package com.example.ollama.outsmart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Checks the model suggestions against Ollama's local model-list response shape. */
class OutsmartOllamaModelCatalogTest {

    /** Lists distinct installed names so every player selector can suggest the same models. */
    @Test
    void listsInstalledModels() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = """
                    {"models":[{"name":"qwen3.5:4b"},{"name":"gemma3:1b"},{"name":"gemma3:1b"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            OutsmartProperties properties = new OutsmartProperties(1, new OutsmartProperties.Ollama(
                    URI.create("http://localhost:" + server.getAddress().getPort()), Duration.ofMinutes(2)));

            OutsmartOllamaModelCatalog.Catalog catalog =
                    new OutsmartOllamaModelCatalog(properties, new ObjectMapper()).list();

            assertEquals(List.of("gemma3:1b", "qwen3.5:4b"), catalog.models());
            assertNull(catalog.error());
        } finally {
            server.stop(0);
        }
    }
}
