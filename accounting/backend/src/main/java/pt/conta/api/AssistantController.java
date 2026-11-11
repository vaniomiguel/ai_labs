package pt.conta.api;

import static pt.conta.shared.Inputs.*;

import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import pt.conta.ai.*;
import pt.conta.shared.*;

/** Installed local models, task-specific choices and inspectable execution history. */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {
  private static final List<String> TASKS =
      List.of("extract-invoice", "extract-statement", "review", "categories", "reconciliation");
  private final AiProvider provider;
  private final Settings settings;
  private final Store store;

  public AssistantController(AiProvider provider, Settings settings, Store store) {
    this.provider = provider;
    this.settings = settings;
    this.store = store;
  }

  /** Returns a usable offline state and versioned recommendations when Ollama is unavailable. */
  @GetMapping
  public Map<String, Object> assistant() throws java.io.IOException {
    List<AiProvider.Model> models;
    String error = null;
    try {
      models = provider.models();
    } catch (OllamaProvider.AiUnavailable e) {
      models = List.of();
      error = e.getMessage();
    }
    var selected = new LinkedHashMap<String, String>();
    for (String task : TASKS) selected.put(task, settings.get("model:" + task, "qwen3.5:9b"));
    return map(
        "models",
        models,
        "selected",
        selected,
        "error",
        error,
        "crossYearMonths",
        Integer.parseInt(settings.get("crossYearMonths", "3")),
        "recommendations",
        Json.value(
            new String(
                new ClassPathResource("model-recommendations.json").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8)));
  }

  /** Changes only future model tasks and the explicitly configured reconciliation window. */
  @PostMapping("/settings")
  public void configure(@RequestBody Map<String, Object> body) {
    if (body.get("models") instanceof Map<?, ?> choices) {
      var installed =
          provider.models().stream()
              .filter(AiProvider.Model::completion)
              .map(AiProvider.Model::name)
              .toList();
      for (var entry : choices.entrySet()) {
        if (!TASKS.contains(entry.getKey().toString())
            || !installed.contains(entry.getValue().toString()))
          throw DomainException.invalid(
              "Seleciona um modelo local instalado para uma tarefa válida");
        settings.set("model:" + entry.getKey(), entry.getValue().toString());
      }
    }
    if (body.containsKey("crossYearMonths")) {
      int months = Integer.parseInt(body.get("crossYearMonths").toString());
      if (months < 0 || months > 120)
        throw DomainException.invalid("A janela deve estar entre 0 e 120 meses");
      settings.set("crossYearMonths", Integer.toString(months));
    }
  }

  /** Saves a reusable Excel header/column mapping, independently of AI model configuration. */
  @PostMapping("/excel-mapping/{kind}")
  public void mapping(@PathVariable String kind, @RequestBody Map<String, Object> mapping) {
    if (!Set.of("HISTORY", "STATEMENT").contains(kind))
      throw DomainException.invalid("Tipo inválido");
    settings.set("excelMapping:" + kind, Json.write(mapping));
  }

  /**
   * Returns recent task outputs and provenance; originals remain accessible only through protected
   * APIs.
   */
  @GetMapping("/executions")
  public List<Map<String, Object>> executions() {
    return store.all(AiExecution.class).stream()
        .sorted(Comparator.comparing((AiExecution e) -> e.createdAt).reversed())
        .limit(100)
        .map(
            e ->
                map(
                    "id",
                    e.id,
                    "targetId",
                    e.targetId,
                    "targetVersion",
                    e.targetVersion,
                    "task",
                    e.task,
                    "model",
                    e.model,
                    "digest",
                    e.modelDigest,
                    "promptVersion",
                    e.promptVersion,
                    "durationMs",
                    e.durationMs,
                    "outcome",
                    e.outcome,
                    "createdAt",
                    e.createdAt,
                    "response",
                    e.response))
        .toList();
  }
}
