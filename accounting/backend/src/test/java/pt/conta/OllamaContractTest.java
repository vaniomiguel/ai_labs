package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static pt.conta.shared.Inputs.map;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pt.conta.ai.*;
import pt.conta.shared.*;

/** Verifies the native Ollama transport against an HTTP stub, without downloading model weights. */
class OllamaContractTest {
  @Test
  void reportsAContextRejectionAsPermanentRatherThanAModelOutage() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var reject=new java.util.concurrent.atomic.AtomicBoolean(true);
    server.createContext("/api/chat",exchange->{
      exchange.getRequestBody().readAllBytes();byte[] body=(reject.get()?"{\"error\":\"input exceeds context window\"}":"{\"done_reason\":\"length\",\"message\":{\"content\":\"{}\"}}").getBytes();
      exchange.sendResponseHeaders(reject.get()?400:200,body.length);exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    try {
      var provider=new OllamaProvider(new AccountingProperties(Path.of("/tmp"),"owner","unused",
          "http://127.0.0.1:"+server.getAddress().getPort(),"http://localhost:8090","",3,false,100,1000000,1000000));
      assertThatThrownBy(()->provider.complete(new AiProvider.Request("test","Instructions","Full input",map("type","object"),List.of())))
          .isInstanceOfSatisfying(AiProvider.ContextExceeded.class, error -> {
            assertThat(error.getMessage()).contains("contexto integral");
            assertThat(error.usage()).isEqualTo(new AiProvider.Usage(null, null, 16384,
                AiProvider.CapacitySource.CONFIGURED, null, AiProvider.ContextWarning.CONTEXT_REJECTED));
          });
      reject.set(false);
      assertThatThrownBy(()->provider.complete(new AiProvider.Request("test","Instructions","Full input",map("type","object"),List.of(),true)))
          .isInstanceOfSatisfying(AiProvider.ContextExceeded.class, error -> {
            assertThat(error.getMessage()).contains("antes de concluir");
            assertThat(error.usage().warning()).isEqualTo(AiProvider.ContextWarning.RESPONSE_INTERRUPTED);
          });
    }finally{server.stop(0);}
  }

  @Test
  void measuresEachCallAgainstTheSelectedRunningModelAndUsesExactThresholds() throws Exception {
    var chat = new AtomicReference<Map<String, Object>>();
    var processes = map("models", List.of(
        map("model", "other:test", "context_length", 100),
        map("model", "selected:test", "context_length", 1000)));
    var server = usageServer(chat, processes);
    try {
      var provider = stubProvider(server);
      chat.set(chatResponse(799, 0, "stop"));
      assertThat(provider.complete(usageRequest()).usage().warning()).isNull();
      chat.set(chatResponse(739, 61, "stop"));
      assertThat(provider.complete(usageRequest()).usage()).isEqualTo(new AiProvider.Usage(
          739, 61, 1000, AiProvider.CapacitySource.RUNNING_MODEL, "stop", AiProvider.ContextWarning.NEAR_CONTEXT_LIMIT));
      chat.set(chatResponse(900, 100, "length"));
      assertThat(provider.complete(usageRequest()).usage().warning())
          .isEqualTo(AiProvider.ContextWarning.CONTEXT_LIMIT_REACHED);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void toleratesMissingCountsAndDistinguishesTheOutputCapFromTheWindow() throws Exception {
    var chat = new AtomicReference<Map<String, Object>>();
    var server = usageServer(chat, map("models", List.of(map("name", "unrelated:test", "context_length", 1000))));
    try {
      var provider = stubProvider(server);
      chat.set(chatResponse(500, null, "stop"));
      assertThat(provider.complete(usageRequest()).usage()).isEqualTo(new AiProvider.Usage(
          500, null, 16384, AiProvider.CapacitySource.CONFIGURED, "stop", null));
      chat.set(chatResponse(500, 8192, "length"));
      assertThat(provider.complete(usageRequest()).usage().warning())
          .isEqualTo(AiProvider.ContextWarning.OUTPUT_LIMIT_REACHED);
      chat.set(chatResponse(8192, 8192, "length"));
      assertThat(provider.complete(usageRequest()).usage().warning())
          .isEqualTo(AiProvider.ContextWarning.RESPONSE_INTERRUPTED);
    } finally {
      server.stop(0);
    }
  }

  private Map<String, Object> chatResponse(int prompt, Integer generated, String reason) {
    return map("message", map("content", "{}"), "done_reason", reason,
        "prompt_eval_count", prompt, "eval_count", generated);
  }

  private AiProvider.Request usageRequest() {
    return new AiProvider.Request("selected:test", "Instructions", "Document", map("type", "object"), List.of());
  }

  private OllamaProvider stubProvider(HttpServer server) {
    return new OllamaProvider(new AccountingProperties(Path.of("/tmp"), "owner", "unused",
        "http://127.0.0.1:" + server.getAddress().getPort(), "http://localhost:8090", "", 3, false, 100, 1000000, 1000000));
  }

  private HttpServer usageServer(AtomicReference<Map<String, Object>> chat, Map<String, Object> processes)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/chat", exchange -> {
      exchange.getRequestBody().readAllBytes();
      respond(exchange, chat.get());
    });
    server.createContext("/api/ps", exchange -> respond(exchange, processes));
    server.start();
    return server;
  }

  private void respond(com.sun.net.httpserver.HttpExchange exchange, Map<String, Object> payload)
      throws java.io.IOException {
    try (exchange) {
      byte[] body = Json.write(payload).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    }
  }

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
                      "vision:test", "Instructions", "Document", schema, List.of("aW1hZ2U="))).content())
          .isEqualTo("{\"ok\":true}");
      var sent = Json.read(request.get());
      assertThat(sent.path("format").path("type").asText()).isEqualTo("object");
      assertThat(sent.path("messages").get(1).path("images").get(0).asText()).isEqualTo("aW1hZ2U=");
      assertThat(sent.path("options").path("num_ctx").asInt()).isEqualTo(16384);
      assertThat(sent.has("truncate")).isFalse();
      provider.complete(new AiProvider.Request("vision:test","Instructions","Full document",schema,List.of("aW1hZ2U="),true));
      var complete=Json.read(request.get());
      assertThat(complete.path("truncate").asBoolean(true)).isFalse();
      assertThat(complete.path("shift").asBoolean(true)).isFalse();
      assertInvoiceMessages(provider, properties, request);
    } finally {
      server.stop(0);
    }
  }
  private void assertInvoiceMessages(
      OllamaProvider provider, AccountingProperties properties, AtomicReference<String> rawRequest) {
    var requests = new InvoiceExtractionRequests(new PromptCatalog(properties),
        new InvoiceRepairContext(new pt.conta.document.FieldProtection(
            org.mockito.Mockito.mock(pt.conta.audit.AuditService.class))));
    var invoice = requests.automatic("vision:test", "Complete invoice OCR text");
    provider.complete(invoice);
    var invoiceBody = Json.read(rawRequest.get());
    var messages = invoiceBody.path("messages");
    assertThat(messages.size()).isEqualTo(2);
    assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
    assertThat(messages.get(0).path("content").asText()).isEqualTo(invoice.instructions());
    assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
    assertThat(messages.get(1).path("content").asText()).isEqualTo(invoice.input());
    assertThat(messages.get(1).has("images")).isFalse();
    assertThat(invoiceBody.path("format")).isEqualTo(Json.read(Json.write(invoice.schema())));
    assertThat(invoiceBody.path("truncate").asBoolean(true)).isFalse();
    assertThat(invoiceBody.path("shift").asBoolean(true)).isFalse();

    var repair = requests.repair("vision:test", "Saved OCR text", map("data", map("supplier", "Known supplier")), 50000);
    provider.complete(repair);
    var repaired = Json.read(rawRequest.get());
    assertThat(repaired.path("messages").get(0).path("content").asText())
        .startsWith("You are an expert accountant");
    var input = Json.read(repaired.path("messages").get(1).path("content").asText());
    assertThat(input.path("responseSchema")).isEqualTo(repaired.path("format"));
    assertThat(input.path("responseTemplate").path("draft").path("supplier").asText()).isEqualTo("Known supplier");
    assertThat(repaired.path("messages").get(1).has("images")).isFalse();
  }

}
