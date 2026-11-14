package pt.conta.category;

import java.util.*;
import org.springframework.stereotype.Service;
import pt.conta.ai.*;
import pt.conta.jobs.JobQueue;
import pt.conta.shared.*;

/** Runs provider calls outside transactions and applies only an unchanged, grounded snapshot. */
@Service
public class CategoryAiWorker {
  private final CategoryService categories;
  private final AiProvider provider;
  private final PromptCatalog prompts;
  private final Settings settings;
  private final AiResults results;

  public CategoryAiWorker(CategoryService categories, AiProvider provider, PromptCatalog prompts,
      Settings settings, AiResults results) {
    this.categories = categories; this.provider = provider; this.prompts = prompts;
    this.settings = settings; this.results = results;
  }

  public void process(JobQueue.Claim job) {
    String type = job.kind().equals("REFINE_CATEGORIES") ? "catalog"
        : job.kind().equals("CLASSIFY_MOVEMENT") ? "movement" : "invoice";
    var snapshot = categories.snapshot(type, job.target());
    if (snapshot == null) {
      if (!type.equals("catalog")) categories.reviewWhenSettled(job.id());
      return;
    }
    var model = provider.models().stream().filter(AiProvider.Model::completion)
        .filter(m -> m.name().equals(settings.get("model:categories", "qwen3.5:9b"))).findFirst()
        .orElseThrow(() -> new OllamaProvider.AiUnavailable("Modelo de categorias indisponível", null));
    String prompt = prompts.prompt("categories");
    var inputData = new LinkedHashMap<>(snapshot.input());
    for (int attempt = 0; attempt < 3; attempt++) {
      String input = Json.write(inputData);
      if (input.length() > 90000) throw DomainException.invalid("O contexto de categorias é demasiado extenso; reduz os exemplos do catálogo");
      long start = System.nanoTime();
      String raw = provider.complete(new AiProvider.Request(model.name(), prompt, input,
          prompts.schema("categories"), List.of()));
      var execution = new AiExecution(); execution.task = "categories"; execution.targetId = job.target();
      execution.targetVersion = job.revision(); execution.model = model.name(); execution.modelDigest = model.digest();
      execution.promptVersion = "v2:" + Hashes.sha256(prompt); execution.inputHash = Hashes.sha256(input + prompt + model.digest());
      execution.request = input; execution.response = raw; execution.durationMs = (System.nanoTime() - start) / 1_000_000;
      Map<String, Object> parsed;
      try { parsed = StructuredOutput.parse(raw, prompts.schema("categories")); execution.outcome = "VALID"; }
      catch (RuntimeException e) { execution.outcome = "INVALID"; results.record(execution); throw e; }
      results.record(execution);
      try {
        if (!categories.apply(snapshot, parsed, execution.id)) {
          results.categoryOutcome(execution.id, "STALE");
          categories.retryStale(type, job.target());
          if (type.equals("catalog")) categories.reviewWhenSettled(job.id());
        } else {
          if (categories.genericMovement(snapshot)) results.categoryOutcome(execution.id, "GUARDED_REVIEW");
          if (!type.equals("catalog")) categories.reviewWhenSettled(job.id());
        }
        return;
      } catch (DomainException e) {
        results.categoryOutcome(execution.id, "INVALID_DOMAIN");
        if (attempt == 2) throw e;
        var evidenceExamples = new ArrayList<String>();
        if (snapshot.input().get("data") instanceof Map<?, ?> data && data.get("description") instanceof String description)
          evidenceExamples.add(description);
        for (var example : Inputs.rows(snapshot.input(), "examples"))
          if (example.get("data") instanceof Map<?, ?> data && data.get("description") instanceof String description)
            evidenceExamples.add(description);
        inputData.put("validationFeedback", Inputs.map("error", e.getMessage(), "rejectedResponse", parsed,
            "literalEvidenceExamples", evidenceExamples.stream().distinct().limit(10).toList(),
            "instruction", prompts.prompt(PromptCatalog.CATEGORY_VALIDATION_RETRY_INSTRUCTIONS).stripTrailing()));
      } catch (RuntimeException e) { results.categoryOutcome(execution.id, "INVALID_DOMAIN"); throw e; }
    }
  }
}
