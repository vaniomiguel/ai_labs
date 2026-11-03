package com.example.ollama.brochure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.ollama.chat.TextResponseFormat;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BrochureServiceTest {

    @Test
    void selectsScrapedLinksAndConvertsModelJsonToRecords() throws IOException {
        HttpServer server = startServer("""
                <a href="/about">About</a><a href="/privacy">Privacy</a>
                <a href="mailto:info@example.com">Email</a>
                """);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            BrochureService service = new BrochureService((systemPrompt, userPrompt, format) -> {
                assertEquals(TextResponseFormat.JSON_OBJECT, format);
                assertTrue(systemPrompt.contains("\"links\" array"));
                assertTrue(userPrompt.contains(url));
                assertTrue(userPrompt.contains("/about"));
                assertTrue(userPrompt.contains("/privacy"));
                return """
                        {"links":[{"type":"about page","url":"/about"}]}
                        """;
            }, new ObjectMapper());

            assertEquals(new BrochureLinks(List.of(new BrochureLink("about page", url + "about"))),
                    service.selectRelevantLinks(url));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsLinksInventedByTheModel() throws IOException {
        HttpServer server = startServer("<a href='/about'>About</a>");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            BrochureService service = new BrochureService((systemPrompt, userPrompt, format) ->
                    "{\"links\":[{\"type\":\"careers\",\"url\":\"/invented\"}]}", new ObjectMapper());

            assertThrows(IllegalArgumentException.class, () -> service.selectRelevantLinks(url));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(String html) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }
}
