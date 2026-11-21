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
  private final AiCall calls;
  private final Settings settings;

  public AiModelExecution(Store store, AiProvider provider, AiCall calls, Settings settings) {
    this.store = store;
    this.provider = provider;
    this.calls = calls;
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

    return calls.execute(job, model, request, new AiCall.Provenance(task, "v1:", inputHash)).output();
  }
}
