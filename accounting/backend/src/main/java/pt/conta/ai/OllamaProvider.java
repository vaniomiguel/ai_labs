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

  public OllamaProvider(AccountingProperties properties) {
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
      throw new AiUnavailable("Ollama indisponível", e);
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
            map("temperature", 0, "num_ctx", 16384, "num_predict", 8192));
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
      if (content.isBlank()) throw new IllegalArgumentException("Resposta vazia do modelo");
      return content;
    } catch (org.springframework.web.client.ResourceAccessException
        | org.springframework.web.client.HttpClientErrorException.NotFound e) {
      throw new AiUnavailable("O modelo não está disponível", e);
    }
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
