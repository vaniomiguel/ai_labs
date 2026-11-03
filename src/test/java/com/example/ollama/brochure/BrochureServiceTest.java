package com.example.ollama.brochure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.ollama.chat.TextResponseFormat;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.jackson.databind.ObjectMapper;

class BrochureServiceTest {

    @Test
    void addsHttpsSchemeToBareWebsiteAddress() throws IOException {
        try (MockedStatic<Scraper> scraper = mockStatic(Scraper.class)) {
            scraper.when(() -> Scraper.fetchWebsiteContents("https://www.cnn.com"))
                    .thenReturn("CNN\n\nCompany content");
            scraper.when(() -> Scraper.fetchWebsiteLinks("https://www.cnn.com"))
                    .thenReturn(List.of());
            BrochureService service = new BrochureService((systemPrompt, userPrompt, format) -> {
                assertTrue(userPrompt.contains("https://www.cnn.com"));
                assertTrue(userPrompt.contains("Company content"));
                return "# CNN";
            }, new ObjectMapper());

            assertEquals("# CNN", service.generateBrochure("www.cnn.com"));
            scraper.verify(() -> Scraper.fetchWebsiteContents("https://www.cnn.com"));
        }
    }

    @Test
    void rejectsUnsupportedWebsiteScheme() {
        BrochureService service = new BrochureService((systemPrompt, userPrompt, format) -> "",
                new ObjectMapper());

        assertThrows(InvalidWebsiteUrlException.class,
                () -> service.generateBrochure("ftp://example.com"));
    }

    @Test
    void generatesMarkdownFromHomePageAndSelectedLinkedPage() throws IOException {
        HttpServer server = startServer(
                "<title>Example Co</title><body><p>We make widgets.</p>"
                        + "<a href='/about?ref=home'>About</a><a href='/privacy'>Privacy</a></body>",
                "<title>About Example Co</title><body><p>Founded by Ada.</p></body>");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            AtomicInteger modelCalls = new AtomicInteger();
            BrochureService service = new BrochureService((systemPrompt, userPrompt, format) -> {
                modelCalls.incrementAndGet();
                if (format == TextResponseFormat.JSON_OBJECT) {
                    assertTrue(systemPrompt.contains("\"links\" array"));
                    assertTrue(userPrompt.contains(url));
                    assertTrue(userPrompt.contains("0: /about?ref=home"));
                    assertTrue(userPrompt.contains("1: /privacy"));
                    return """
                            {"links":[{"type":"about page","index":0}]}
                            """;
                }
                assertEquals(TextResponseFormat.DEFAULT, format);
                assertTrue(systemPrompt.contains("Markdown"));
                assertTrue(userPrompt.contains("We make widgets."));
                assertTrue(userPrompt.contains("Founded by Ada."));
                assertTrue(userPrompt.contains("Source: " + url + "about?ref=home"));
                return "# Example Co\n\nWe make widgets.";
            }, new ObjectMapper());

            assertEquals("# Example Co\n\nWe make widgets.", service.generateBrochure(url));
            assertEquals(2, modelCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void skipsInvalidModelSelectionsAndStillGeneratesBrochure() throws IOException {
        HttpServer server = startServer("<a href='/about'>About</a>", "About page");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            BrochureService service = new BrochureService((systemPrompt, userPrompt, format) -> {
                if (format == TextResponseFormat.JSON_OBJECT) {
                    return "{\"links\":[{\"type\":\"careers\",\"index\":99}]}";
                }
                assertTrue(userPrompt.contains("Source: " + url));
                return "# Example Co";
            }, new ObjectMapper());

            assertEquals("# Example Co", service.generateBrochure(url));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(String homeHtml, String aboutHtml) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String html = exchange.getRequestURI().getPath().equals("/about") ? aboutHtml : homeHtml;
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
