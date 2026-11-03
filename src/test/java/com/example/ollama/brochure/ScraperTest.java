package com.example.ollama.brochure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class ScraperTest {

    @Test
    void fetchesTextAndLinksWithBrowserUserAgent() throws IOException {
        String html = "<html><head><title>Example</title></head><body>"
                + "<h1>Hello</h1><p>World</p>"
                + "<script>hidden script</script><style>hidden style</style>"
                + "<a href='/about'>About</a><a href=''>Empty</a><a>No href</a>"
                + "</body></html>";
        AtomicReference<String> userAgent = new AtomicReference<>();
        HttpServer server = startServer(html, userAgent);
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/";

            String contents = Scraper.fetchWebsiteContents(url);
            assertEquals("Example\n\nHello\nWorld\nAbout\nEmpty\nNo href", contents);
            assertEquals(java.util.List.of("/about"), Scraper.fetchWebsiteLinks(url));
            assertTrue(userAgent.get().startsWith("Mozilla/5.0"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesFallbackTitleAndTruncatesContents() throws IOException {
        HttpServer server = startServer("<body><p>" + "x".repeat(2_100) + "</p></body>", new AtomicReference<>());
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/";
            String contents = Scraper.fetchWebsiteContents(url);

            assertEquals(2_000, contents.length());
            assertTrue(contents.startsWith("No title found\n\n"));
            assertFalse(contents.contains("<p>"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(String html, AtomicReference<String> userAgent) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
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
