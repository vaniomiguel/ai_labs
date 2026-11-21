package pt.conta.ai;

import java.util.Map;
import org.springframework.stereotype.Component;
import pt.conta.jobs.JobQueue;
import pt.conta.shared.Hashes;
import pt.conta.shared.Settings;
import pt.conta.shared.Store;

/** Selects a configured model and retains schema-checked provider responses with their provenance. */
@Component
public class AiModelExecution {
  private final Store store;
  private final AiProvider provider;
  private final AiResults results;
  private final Settings settings;

  public AiModelExecution(Store store, AiProvider provider, AiResults results, Settings settings) {
    this.store = store;
    this.provider = provider;
    this.results = results;
    this.settings = settings;
  }

  AiProvider.Model select(String task) {
    String selected = settings.get("model:" + task, "qwen3.5:9b");
    return provider.models().stream()
        .filter(AiProvider.Model::completion)
        .filter(m -> m.name().equals(selected))
        .findFirst()
        .orElseThrow(() -> new OllamaProvider.AiUnavailable(
            "Instala ou seleciona um modelo de geração: " + selected, null));
  }

  Map<String, Object> execute(
      JobQueue.Claim job, AiProvider.Model model, String task, String documentHash,
      AiProvider.Request request) {
    String inputHash = Hashes.sha256(request.input() + model.digest() + request.instructions() + documentHash);
    var cached = store.all(AiExecution.class).stream()
        .filter(e -> e.inputHash.equals(inputHash) && "VALID".equals(e.outcome))
        .findFirst();
    if (cached.isPresent()) return StructuredOutput.parse(cached.get().response, request.schema());

    long started = System.nanoTime();
    String raw = provider.complete(request);
    var execution = new AiExecution();
    execution.targetId = job.target();
    execution.targetVersion = job.revision();
    execution.task = task;
    execution.model = model.name();
    execution.modelDigest = model.digest();
    execution.promptVersion = "v1:" + Hashes.sha256(request.instructions());
    execution.inputHash = inputHash;
    execution.durationMs = (System.nanoTime() - started) / 1_000_000;
    execution.request = request.input();
    execution.response = raw;
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
    return parsed;
  }
}
