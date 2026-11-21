package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import pt.conta.ai.AutomaticReviewContext.*;
import pt.conta.ai.AutomaticReviewEvidence.Finding;
import pt.conta.ai.AutomaticReviewRequests.*;
import pt.conta.jobs.JobQueue;
import pt.conta.shared.Hashes;
import pt.conta.shared.Json;

/** Runs bounded, text-only automatic reviews outside the financial write transaction. */
@Service
public class AutomaticReviewWorker {
  private final AutomaticReviewContext context;
  private final AutomaticReviewRequests requests;
  private final AutomaticReviewEvidence evidence;
  private final AiModelExecution models;
  private final AutomaticReviewResults results;

  AutomaticReviewWorker(
      AutomaticReviewContext context, AutomaticReviewRequests requests, AutomaticReviewEvidence evidence,
      AiModelExecution models, AutomaticReviewResults results) {
    this.context = context;
    this.requests = requests;
    this.evidence = evidence;
    this.models = models;
    this.results = results;
  }

  record Report(
      String verdict, String summary, List<Finding> findings, List<String> suggestions,
      boolean partial, int reviewedRequests, int skippedRequests, List<Map<String, Object>> portions) {
    Map<String, Object> view() {
      return map("verdict", verdict, "summary", summary,
          "concerns", findings.stream().map(Finding::detail).toList(), "suggestions", suggestions,
          "coverage", partial ? "PARTIAL" : "FULL", "reviewedRequests", reviewedRequests,
          "skippedRequests", skippedRequests, "portions", portions);
    }
  }

  void process(JobQueue.Claim job) {
    if (Json.read(job.payload()).hasNonNull("reviewResult")) return;
    var snapshot = context.capture(job);
    if (snapshot == null) return;
    var report = new ReviewRun();
    var batch = requests.full(snapshot);
    report.partial = snapshot.sources().stream().anyMatch(source -> !source.complete());
    if (!batch.hasEvidence()) {
      report.skip(batch, "Não há texto de origem ou dados suficientes para comparar.");
      results.complete(job, snapshot, report.finish(), null);
      return;
    }

    var model = models.select("review");
    var instructions = requests.instructions(snapshot);
    var review = new ReviewInput(job, snapshot, model, instructions);
    run(review, batch, report, true);
    results.complete(job, snapshot, report.finish(), model.name());
  }

  private record ReviewInput(JobQueue.Claim job, Snapshot snapshot, AiProvider.Model model, Instructions instructions) {}

  private void run(ReviewInput review, Batch batch, ReviewRun report, boolean mayRetryContext) {
    if (!batch.hasEvidence()) {
      report.skip(batch, "Parte dos dados não tem texto de origem disponível.");
      return;
    }
    var request = requests.assemble(review.snapshot(), batch, review.instructions(), review.model().name(), report.partial);
    if (!requests.fits(request)) {
      report.partial = true;
      // Enough room for at least half of the request to be text before splitting the text itself.
      var empty = new Batch(batch.fields(), List.of());
      var factsOnly = requests.assemble(review.snapshot(), empty, review.instructions(), review.model().name(), true);
      split(review, batch, report, mayRetryContext, factsOnly.input().length() > 24_000);
      return;
    }
    try {
      String sourceVersion = Hashes.sha256(Json.write(review.snapshot().revisions()));
      var response = models.execute(review.job(), review.model(), PromptCatalog.AUTOMATIC_REVIEW_SYSTEM_INSTRUCTIONS,
          sourceVersion, request);
      checkResponse(batch, response, report);
    } catch (AiProvider.ContextExceeded e) {
      if (mayRetryContext) {
        report.partial = true;
        split(review, batch, report, false, false);
      } else {
        report.skip(batch, "O modelo não conseguiu rever uma parte do texto dentro do limite de contexto.");
      }
    }
  }

  private void split(
      ReviewInput review, Batch batch, ReviewRun report, boolean mayRetryContext, boolean separateFieldsFirst) {
    var parts = requests.split(batch, separateFieldsFirst);
    if (parts.isEmpty()) {
      report.skip(batch, "Parte do pedido não coube no contexto sem cortar uma linha.");
      return;
    }
    for (var part : parts) run(review, part, report, mayRetryContext);
  }

  private void checkResponse(Batch batch, Map<String, Object> response, ReviewRun report) {
    String verdict = text(response, "verdict");
    report.record(batch, null);
    var concerns = rows(response, "concerns");
    if (!"PASS".equals(verdict) || !concerns.isEmpty()) report.inconclusive = true;
    if (response.get("suggestions") instanceof List<?> suggestions && !suggestions.isEmpty()) {
      report.inconclusive = true;
      suggestions.stream().map(Object::toString).filter(s -> !s.isBlank()).forEach(report.suggestions::add);
    }
    if (!"CONCERN".equals(verdict)) return;
    var fields = batch.fields().stream().collect(Collectors.toMap(Field::path, field -> field));
    var excerpts = batch.excerpts().stream().collect(Collectors.toMap(excerpt -> excerpt.source().id(), e -> e));
    for (var concern : concerns) {
      var excerpt = excerpts.get(text(concern, "sourceId"));
      if (excerpt == null) continue;
      evidence.check(concern, fields.get(text(concern, "field")), excerpt)
          .ifPresent(report::add);
    }
  }

  private static final class ReviewRun {
    final Map<List<String>, Finding> findings = new LinkedHashMap<>();
    final Set<String> suggestions = new LinkedHashSet<>();
    final List<Map<String, Object>> portions = new ArrayList<>();
    boolean partial;
    boolean inconclusive;
    int reviewed;
    int skipped;

    void add(Finding finding) {
      var detail = finding.detail();
      var key = List.of(finding.field().path(), text(detail, "sourceId"), text(detail, "sourceValue"));
      findings.merge(key, finding, (previous, current) -> current.verified() ? current : previous);
    }

    void record(Batch batch, String reason) {
      var sources = batch.excerpts().stream()
          .map(excerpt -> map("sourceId", excerpt.source().id(), "offset", excerpt.offset(),
              "length", excerpt.text().length()))
          .toList();
      portions.add(map("sources", sources, "fieldCount", batch.fields().size(),
          "status", reason == null ? "REVIEWED" : "SKIPPED", "reason", reason));
      if (reason == null) reviewed++;
      else skipped++;
    }

    void skip(Batch batch, String reason) {
      partial = true;
      record(batch, reason);
      suggestions.add(reason);
    }

    Report finish() {
      boolean verified = findings.values().stream().anyMatch(Finding::verified);
      String verdict = verified ? "CONCERN" : partial || inconclusive || reviewed == 0 ? "SUGGESTION" : "PASS";
      String summary = switch (verdict) {
        case "CONCERN" -> "Foi verificada uma divergência entre o registo e o documento.";
        case "SUGGESTION" -> "A revisão por IA foi inconclusiva.";
        default -> "A revisão por IA não encontrou divergências.";
      };
      return new Report(verdict, summary, List.copyOf(findings.values()), List.copyOf(suggestions),
          partial, reviewed, skipped, List.copyOf(portions));
    }
  }
}
