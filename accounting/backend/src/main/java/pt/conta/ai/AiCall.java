package pt.conta.ai;

import java.util.Map;
import org.springframework.stereotype.Component;
import pt.conta.jobs.JobQueue;
import pt.conta.shared.Hashes;

/** Records the outcome of a provider call before its result is applied to a business record. */
@Component
public class AiCall {
  private final AiProvider provider;
  private final AiResults results;

  public AiCall(AiProvider provider, AiResults results) {
    this.provider = provider;
    this.results = results;
  }

  public record Provenance(String task, String promptVersion, String inputHash) {}

  public record Result(AiExecution execution, Map<String, Object> output) {}

  public Result execute(JobQueue.Claim job, AiProvider.Model model, AiProvider.Request request,
      Provenance provenance) {
    var execution = execution(job, model, request, provenance);
    var completion = complete(request, execution, job);
    execution.response = completion.content();
    execution.recordUsage(completion.usage());
    Map<String, Object> output;
    try {
      if (completion.content().isBlank()) throw new IllegalArgumentException("Resposta vazia do modelo");
      output = StructuredOutput.parse(completion.content(), request.schema());
    } catch (RuntimeException e) {
      execution.outcome = "INVALID";
      execution.error = "A resposta do modelo está vazia ou não respeita o formato esperado.";
      results.record(execution, job);
      throw e;
    }
    execution.outcome = "VALID";
    results.record(execution, job);
    return new Result(execution, output);
  }

  private AiProvider.Completion complete(AiProvider.Request request, AiExecution execution, JobQueue.Claim job) {
    long started = System.nanoTime();
    try {
      var completion = provider.complete(request);
      execution.durationMs = elapsedMillis(started);
      return completion;
    } catch (RuntimeException e) {
      execution.durationMs = elapsedMillis(started);
      execution.outcome = "FAILED";
      execution.error = switch (e) {
        case AiProvider.ContextExceeded limit -> {
          execution.recordUsage(limit.usage());
          yield limit.getMessage();
        }
        case OllamaProvider.AiUnavailable unavailable -> unavailable.getMessage();
        default -> "Não foi possível obter uma resposta do modelo.";
      };
      results.record(execution, job);
      throw e;
    }
  }

  private long elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  private AiExecution execution(JobQueue.Claim job, AiProvider.Model model, AiProvider.Request request,
      Provenance provenance) {
    var execution = new AiExecution();
    execution.jobId = job.id();
    execution.targetId = job.target();
    execution.targetVersion = job.revision();
    execution.model = model.name();
    execution.modelDigest = model.digest();
    execution.task = provenance.task();
    execution.promptVersion = provenance.promptVersion() + Hashes.sha256(request.instructions());
    execution.inputHash = provenance.inputHash();
    execution.request = request.input();
    return execution;
  }
}
