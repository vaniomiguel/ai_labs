package pt.conta.ai;

import static pt.conta.shared.Inputs.map;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pt.conta.shared.*;

/** Native Ollama adapter supporting local catalog, image input and JSON Schema output. */
@Component
public class OllamaProvider implements AiProvider {
  private static final int OUTPUT_LIMIT_TOKENS = 8192;
  private static final int CONTEXT_WARNING_PERCENT = 80;
  private final RestClient shortClient;
  private final RestClient modelClient;
  private final String endpoint;
  private final int contextTokens;

  public OllamaProvider(AccountingProperties properties) {
    this(properties,16384);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public OllamaProvider(AccountingProperties properties,
      @org.springframework.beans.factory.annotation.Value("${accounting.ai-context-tokens:16384}") int contextTokens) {
    if(contextTokens<=0)throw new IllegalArgumentException("A capacidade de contexto deve ser positiva");
    this.contextTokens=contextTokens;
    endpoint = endpoint(properties.ollamaUrl());
    shortClient = client(properties.ollamaUrl(), Duration.ofSeconds(3));
    modelClient = client(properties.ollamaUrl(), Duration.ofMinutes(20));
  }

  /** Filters cloud aliases so installing one never sends private documents to a remote model. */
  @Override
  public List<Model> models() {
    try {
      var result = Json.read(shortClient.get().uri("/api/tags").retrieve().body(String.class));
      var models = new ArrayList<Model>();
      for (var entry : result.path("models")) {
        String name = entry.path("name").asText();
        if (name.endsWith("-cloud") || entry.hasNonNull("remote_host")) continue;
        boolean vision = false, completion = false;
        try {
          var details =
              Json.read(
                  shortClient
                      .post()
                      .uri("/api/show")
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(Json.write(map("model", name)))
                      .retrieve()
                      .body(String.class));
          for (var capability : details.path("capabilities")) {
            if (capability.asText().equals("vision")) vision = true;
            if (capability.asText().equals("completion")) completion = true;
          }
        } catch (Exception ignored) {
          /* The catalog remains usable while capability discovery is unavailable. */
        }
        models.add(new Model(name, entry.path("digest").asText(), vision, true, completion));
      }
      return models;
    } catch (Exception e) {
      throw unavailable(e);
    }
  }

  /** Requests bounded structured output; model content is never executed as instructions or SQL. */
  @Override
  public Completion complete(Request request) {
    var body = chatBody(request);
    try {
      var response = Json.read(modelClient.post().uri("/api/chat")
          .contentType(MediaType.APPLICATION_JSON).body(Json.write(body)).retrieve().body(String.class));
      return completion(request, response);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable(e);
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
      throw new AiUnavailable("O modelo " + request.model() + " não está disponível em " + endpoint, e);
    } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
      if (e.getResponseBodyAsString().toLowerCase(Locale.ROOT)
          .matches("(?s).*(context|token limit|too many images|input.{0,20}too long).*$")) {
        var capacity = capacity(request.model());
        throw new ContextExceeded(
            "O modelo recusou o contexto integral por exceder a sua capacidade. Seleciona uma configuração com maior contexto ou revê o documento manualmente.",
            new Usage(null, null, capacity.tokens(), capacity.source(), null, ContextWarning.CONTEXT_REJECTED));
      }
      throw e;
    }
  }

  private Map<String, Object> chatBody(Request request) {
    var user = map("role", "user", "content", request.input());
    if (!request.images().isEmpty()) user.put("images", request.images());
    var body =
        map(
            "model",
            request.model(),
            "stream",
            false,
            "think",
            false,
            "messages",
            List.of(map("role", "system", "content", request.instructions()), user),
            "format",
            request.schema(),
            "options",
            map("temperature", 0, "num_ctx", contextTokens, "num_predict", OUTPUT_LIMIT_TOKENS));
    if (request.requireFullContext()) {
      body.put("truncate", false);
      body.put("shift", false);
    }
    return body;
  }

  private Completion completion(Request request, JsonNode response) {
    var capacity = capacity(request.model());
    Integer prompt = tokenCount(response.path("prompt_eval_count"));
    Integer generated = tokenCount(response.path("eval_count"));
    String reason = response.path("done_reason").asText(null);
    var warning = warning(prompt, generated, capacity.tokens(), reason);
    var usage = new Usage(prompt, generated, capacity.tokens(), capacity.source(), reason, warning);
    if (request.requireFullContext() && "length".equals(reason))
      throw new ContextExceeded(
          "O modelo atingiu o limite de contexto ou da resposta antes de concluir a proposta. Seleciona uma configuração com maior capacidade ou revê o documento manualmente.",
          usage);
    return new Completion(response.path("message").path("content").asText(), usage);
  }

  private ContextWarning warning(Integer prompt, Integer generated, int budget, String reason) {
    Long total = prompt == null || generated == null ? null : (long) prompt + generated;
    boolean interrupted = "length".equals(reason);
    boolean atOutputCap = generated != null && generated >= OUTPUT_LIMIT_TOKENS;
    if (total != null && total >= budget) {
      return interrupted && atOutputCap ? ContextWarning.RESPONSE_INTERRUPTED : ContextWarning.CONTEXT_LIMIT_REACHED;
    }
    if (interrupted) {
      return atOutputCap && total != null ? ContextWarning.OUTPUT_LIMIT_REACHED : ContextWarning.RESPONSE_INTERRUPTED;
    }
    return total != null && total * 100 >= (long) budget * CONTEXT_WARNING_PERCENT
        ? ContextWarning.NEAR_CONTEXT_LIMIT : null;
  }

  private record Capacity(int tokens, CapacitySource source) {}

  private Capacity capacity(String model) {
    try {
      var running = Json.read(shortClient.get().uri("/api/ps").retrieve().body(String.class));
      for (var entry : running.path("models")) {
        Integer tokens = tokenCount(entry.path("context_length"));
        if (tokens != null && tokens > 0 && tokens <= contextTokens
            && (model.equals(entry.path("model").asText()) || model.equals(entry.path("name").asText()))) {
          return new Capacity(tokens, CapacitySource.RUNNING_MODEL);
        }
      }
    } catch (org.springframework.web.client.RestClientException | IllegalArgumentException ignored) {
      // Capacity discovery is optional and must not invalidate a chat response.
    }
    return new Capacity(contextTokens, CapacitySource.CONFIGURED);
  }

  private Integer tokenCount(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 ? value.intValue() : null;
  }

  /** Omits credentials and query strings from connection diagnostics. */
  public static String endpoint(String url) {
    var uri = java.net.URI.create(url);
    try {
      return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
    } catch (java.net.URISyntaxException e) {
      throw new IllegalArgumentException("Endereço do Ollama inválido", e);
    }
  }

  private AiUnavailable unavailable(Exception error) {
    String reason = "não foi possível estabelecer a ligação";
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.net.UnknownHostException || cause instanceof java.nio.channels.UnresolvedAddressException) {
        reason = "o endereço não foi encontrado";
        break;
      }
      if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
        reason = "o tempo de espera da resposta foi excedido";
        break;
      }
      if (cause instanceof java.net.ConnectException) reason = "a ligação foi recusada";
      if (cause instanceof org.springframework.web.client.RestClientResponseException response) {
        reason = "o servidor devolveu HTTP " + response.getStatusCode().value();
      }
    }
    return new AiUnavailable("Ollama indisponível em " + endpoint + ": " + reason, error);
  }

  private RestClient client(String url, Duration timeout) {
    var factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(timeout);
    return RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /**
   * Marks transient outages separately from invalid model output and permanent processing failures.
   */
  public static class AiUnavailable extends RuntimeException {
    public AiUnavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
