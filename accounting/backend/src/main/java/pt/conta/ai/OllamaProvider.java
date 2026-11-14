package pt.conta.ai;

import static pt.conta.shared.Inputs.map;

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
  public String complete(Request request) {
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
            map("temperature", 0, "num_ctx", contextTokens, "num_predict", 8192));
    if(request.requireFullContext()) { body.put("truncate",false); body.put("shift",false); }
    try {
      var response =
          Json.read(
              modelClient
                  .post()
                  .uri("/api/chat")
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(Json.write(body))
                  .retrieve()
                  .body(String.class));
      String content = response.path("message").path("content").asText();
      if(request.requireFullContext()&&"length".equals(response.path("done_reason").asText()))
        throw new AiProvider.ContextExceeded("O modelo atingiu o limite de contexto ou da resposta antes de concluir a proposta. Seleciona uma configuração com maior capacidade ou revê o documento manualmente.");
      if (content.isBlank()) throw new IllegalArgumentException("Resposta vazia do modelo");
      return content;
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable(e);
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
      throw new AiUnavailable("O modelo " + request.model() + " não está disponível em " + endpoint, e);
    } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
      if(e.getResponseBodyAsString().toLowerCase(java.util.Locale.ROOT).matches("(?s).*(context|token limit|too many images|input.{0,20}too long).*$"))
        throw new AiProvider.ContextExceeded("O modelo recusou o contexto integral por exceder a sua capacidade. Seleciona uma configuração com maior contexto ou revê o documento manualmente.");
      throw e;
    }
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
