package pt.conta.category;

import java.util.*;
import org.springframework.stereotype.Service;
import pt.conta.ai.*;
import pt.conta.jobs.JobQueue;
import pt.conta.shared.*;

/** Runs provider calls outside transactions and applies only an unchanged, grounded snapshot. */
@Service
public class CategoryAiWorker {
  private static final int MAX_REQUEST_CHARACTERS = 90_000;
  private final CategoryService categories;
  private final AiProvider provider;
  private final PromptCatalog prompts;
  private final Settings settings;
  private final AiResults results;

  public CategoryAiWorker(CategoryService categories, AiProvider provider, PromptCatalog prompts,
      Settings settings, AiResults results) {
    this.categories = categories;
    this.provider = provider;
    this.prompts = prompts;
    this.settings = settings;
    this.results = results;
  }

  public void process(JobQueue.Claim job) {
    String type = switch (job.kind()) {
      case "REFINE_CATEGORIES" -> "catalog";
      case "CLASSIFY_MOVEMENT" -> "movement";
      default -> "invoice";
    };
    var snapshot = categories.snapshot(type, job.target());
    if (snapshot == null || obsolete(job, snapshot)) {
      categories.reviewWhenSettled(job.id());
      return;
    }

    var model = selectModel();
    String prompt = prompts.prompt(type.equals("catalog")
        ? PromptCatalog.CATEGORY_CATALOG_REVIEW_SYSTEM_INSTRUCTIONS
        : PromptCatalog.EXPENSE_CLASSIFICATION_SYSTEM_INSTRUCTIONS);
    var schema = prompts.schema(PromptCatalog.EXPENSE_CLASSIFICATION_SYSTEM_INSTRUCTIONS);
    var input = new LinkedHashMap<>(snapshot.input());
    input.put("responseSchema", schema);
    classify(job, snapshot, model, prompt, schema, input);
  }

  private boolean obsolete(JobQueue.Claim job, CategoryService.Snapshot snapshot) {
    if (snapshot.type().equals("catalog")) return job.revision() != snapshot.catalogRevision();
    // Legacy normalization jobs carry a financial revision rather than a classification generation.
    return !job.kind().equals("NORMALIZE_CATEGORIES") && job.revision() != generation(snapshot);
  }

  private long generation(CategoryService.Snapshot snapshot) {
    return ((Number) snapshot.input().get("generation")).longValue();
  }

  private AiProvider.Model selectModel() {
    String selected = settings.get("model:categories", "qwen3.5:9b");
    return provider.models().stream().filter(AiProvider.Model::completion)
        .filter(model -> model.name().equals(selected)).findFirst()
        .orElseThrow(() -> new OllamaProvider.AiUnavailable("Modelo de categorias indisponível", null));
  }

  private void classify(JobQueue.Claim job, CategoryService.Snapshot snapshot, AiProvider.Model model,
      String prompt, Map<String, Object> schema, Map<String, Object> input) {
    for (int attempt = 1; attempt <= 3; attempt++) {
      var request = request(model.name(), prompt, schema, input);
      var response = execute(job, model, request);
      var reviewed = new CategoryService.Snapshot(snapshot.type(), snapshot.target(),
          snapshot.fingerprint(), snapshot.catalogRevision(), input);
      try {
        apply(job, reviewed, response);
        return;
      } catch (DomainException e) {
        results.categoryOutcome(response.id(), "INVALID_DOMAIN");
        if (attempt == 3) throw e;
        input.put("validationFeedback", feedback(input, e.getMessage(), response.output()));
      } catch (RuntimeException e) {
        results.categoryOutcome(response.id(), "INVALID_DOMAIN");
        throw e;
      }
    }
  }

  private AiProvider.Request request(String model, String prompt, Map<String, Object> schema,
      Map<String, Object> input) {
    int overhead = prompt.length() + Json.write(schema).length();
    String serialized = Json.write(input);
    if (overhead + serialized.length() > MAX_REQUEST_CHARACTERS) {
      input.put("examples", List.of());
      // Regenerate feedback examples too so they do not reintroduce omitted quotations.
      if (input.get("validationFeedback") instanceof Map<?, ?> previous)
        input.put("validationFeedback", feedback(input, previous.get("error").toString(),
            previous.get("rejectedResponse")));
      serialized = Json.write(input);
    }
    if (overhead + serialized.length() > MAX_REQUEST_CHARACTERS) {
      throw new AiProvider.ContextExceeded("O contexto necessário para a classificação é demasiado extenso.");
    }
    return new AiProvider.Request(model, prompt, serialized, schema, List.of(), true);
  }

  private record Response(UUID id, Map<String, Object> output) {}

  private Response execute(JobQueue.Claim job, AiProvider.Model model, AiProvider.Request request) {
    long start = System.nanoTime();
    String raw = provider.complete(request);
    var execution = new AiExecution();
    execution.task = "categories";
    execution.targetId = job.target();
    execution.targetVersion = job.revision();
    execution.model = model.name();
    execution.modelDigest = model.digest();
    execution.promptVersion = "v3:" + Hashes.sha256(request.instructions());
    execution.inputHash = Hashes.sha256(request.input() + request.instructions() + model.digest());
    execution.request = request.input();
    execution.response = raw;
    execution.durationMs = (System.nanoTime() - start) / 1_000_000;
    Map<String, Object> parsed;
    try {
      parsed = StructuredOutput.parse(raw, request.schema());
      execution.outcome = "VALID";
    } catch (RuntimeException e) {
      execution.outcome = "INVALID";
      results.record(execution);
      throw e;
    }
    results.record(execution);
    return new Response(execution.id, parsed);
  }

  private void apply(JobQueue.Claim job, CategoryService.Snapshot snapshot, Response response) {
    if (!categories.apply(snapshot, response.output(), response.id())) {
      results.categoryOutcome(response.id(), "STALE");
      categories.retryStale(snapshot.type(), job.target(), generation(snapshot));
      if (snapshot.type().equals("catalog")) categories.reviewWhenSettled(job.id());
      return;
    }
    if (categories.genericMovement(snapshot)) results.categoryOutcome(response.id(), "GUARDED_REVIEW");
    if (!snapshot.type().equals("catalog")) categories.reviewWhenSettled(job.id());
  }

  private Map<String, Object> feedback(Map<String, Object> input, String error, Object rejected) {
    var evidence = new ArrayList<>(CategoryService.evidenceFields(input.get("data")));
    for (var example : Inputs.rows(input, "examples")) {
      evidence.addAll(CategoryService.evidenceFields(example.get("data")));
    }
    return Inputs.map("error", error, "rejectedResponse", rejected,
        "literalEvidenceExamples", evidence.stream().distinct().limit(10)
            .map(value -> value.substring(0, Math.min(value.length(), 300))).toList(),
        "instruction", prompts.prompt(PromptCatalog.CATEGORY_VALIDATION_RETRY_INSTRUCTIONS).stripTrailing());
  }
}
