package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static pt.conta.shared.Inputs.map;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pt.conta.ai.*;
import pt.conta.shared.*;

/** Verifies the native Ollama transport against an HTTP stub, without downloading model weights. */
class OllamaContractTest {
  @Test
  void explainsAnOutageAndRecoversWithoutReplacingTheProvider() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var unavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
    server.createContext("/api/tags", exchange -> {
      byte[] body = "{\"models\":[]}".getBytes();
      exchange.sendResponseHeaders(unavailable.get() ? 503 : 200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
      var provider = new OllamaProvider(new AccountingProperties(Path.of("/tmp"), "owner", "unused",
          endpoint, "http://localhost:8090", "", 3, false, 100, 1000000, 1000000));
      assertThatThrownBy(provider::models).isInstanceOf(OllamaProvider.AiUnavailable.class)
          .hasMessageContaining(endpoint).hasMessageContaining("HTTP 503");
      unavailable.set(false);
      assertThat(provider.models()).isEmpty();
      assertThat(OllamaProvider.endpoint("http://user:secret@localhost:11434/path?token=secret"))
          .isEqualTo("http://localhost:11434/path");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void discoversCapabilitiesAndSendsImagesAndSchema() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var request = new AtomicReference<String>();
    server.createContext(
        "/api/tags",
        exchange -> {
          byte[] body =
              Json.write(
                      map(
                          "models",
                          List.of(
                              map("name", "vision:test", "digest", "digest-1"),
                              map("name", "embedding:test", "digest", "digest-2"),
                              map("name", "remote-cloud", "digest", "cloud"))))
                  .getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/api/show",
        exchange -> {
          var input = Json.read(new String(exchange.getRequestBody().readAllBytes()));
          byte[] body =
              Json.write(
                      map(
                          "capabilities",
                          input.path("model").asText().equals("vision:test")
                              ? List.of("completion", "vision")
                              : List.of("embedding")))
                  .getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/api/chat",
        exchange -> {
          request.set(new String(exchange.getRequestBody().readAllBytes()));
          byte[] body = Json.write(map("message", map("content", "{\"ok\":true}"))).getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var properties =
          new AccountingProperties(
              Path.of("/tmp"),
              "owner",
              "not-used",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "http://localhost:8090",
              "",
              3,
              false,
              100,
              1000000,
              1000000);
      var provider = new OllamaProvider(properties);
      var models = provider.models();
      assertThat(models).hasSize(2);
      assertThat(models.getFirst().completion()).isTrue();
      assertThat(models.getFirst().vision()).isTrue();
      assertThat(models.getLast().completion()).isFalse();
      var schema =
          map(
              "type",
              "object",
              "properties",
              map("ok", map("type", "boolean")),
              "required",
              List.of("ok"));
      assertThat(
              provider.complete(
                  new AiProvider.Request(
                      "vision:test", "Instructions", "Document", schema, List.of("aW1hZ2U="))))
          .isEqualTo("{\"ok\":true}");
      var sent = Json.read(request.get());
      assertThat(sent.path("format").path("type").asText()).isEqualTo("object");
      assertThat(sent.path("messages").get(1).path("images").get(0).asText()).isEqualTo("aW1hZ2U=");
      assertThat(sent.path("options").path("num_ctx").asInt()).isEqualTo(16384);
    } finally {
      server.stop(0);
    }
  }
}
