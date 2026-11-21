package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import pt.conta.document.*;
import pt.conta.invoice.*;
import pt.conta.jobs.JobQueue;
import pt.conta.ledger.BookkeepingService;
import pt.conta.shared.*;

/**
 * Bounded document/model orchestration; external calls never execute inside financial transactions.
 */
@Service
public class AiOrchestrator {
  private final pt.conta.category.CategoryAiWorker categoryWorker;
  private final RepairAiWorker repairWorker;
  private final InvoiceExtractionRequests invoiceRequests;
  private final Store store;
  private final AiModelExecution models;
  private final AutomaticReviewWorker automaticReviews;
  private final PromptCatalog prompts;
  private final Settings settings;
  private final DocumentPublisher publisher;
  private final DocumentStorage storage;
  private final BookkeepingService book;

  public AiOrchestrator(
      Store store, AiModelExecution models, PromptCatalog prompts, Settings settings,
      DocumentPublisher publisher, DocumentStorage storage, BookkeepingService book,
      pt.conta.category.CategoryAiWorker categoryWorker, RepairAiWorker repairWorker,
      InvoiceExtractionRequests invoiceRequests, AutomaticReviewWorker automaticReviews) {
    this.store = store;
    this.models = models;
    this.prompts = prompts;
    this.settings = settings;
    this.publisher = publisher;
    this.storage = storage;
    this.book = book;
    this.categoryWorker = categoryWorker;
    this.repairWorker = repairWorker;
    this.invoiceRequests = invoiceRequests;
    this.automaticReviews = automaticReviews;
  }

  /** Executes one extraction, review or explicitly requested repair task. */
  public void process(JobQueue.Claim job) throws Exception {
    if (job.kind().equals("REPAIR_ALERT")) { repairWorker.process(job); return; }
    if (Set.of("NORMALIZE_CATEGORIES", "CLASSIFY_INVOICE", "CLASSIFY_MOVEMENT", "REFINE_CATEGORIES").contains(job.kind())) {
      categoryWorker.process(job);
      return;
    }
    if (AutomaticReviewContext.Kind.forJob(job.kind()).isPresent()) {
      automaticReviews.process(job);
      return;
    }
    if (job.kind().equals("SUGGEST_RECONCILIATION")) {
      suggestReconciliation(job);
      return;
    }
    if (job.kind().equals("AI_EXTRACT_DOCUMENT")) {
      extractDocument(job);
      return;
    }
    throw DomainException.invalid("Tipo de tarefa de IA desconhecido: " + job.kind());
  }

  private void suggestReconciliation(JobQueue.Claim job) throws Exception {
    var candidates = book.candidateContext(job.target(), book.candidates());
    String fingerprint = Json.read(job.payload()).path("fingerprint").asText();
    if (candidates.isEmpty() || !Hashes.sha256(Json.write(candidates)).equals(fingerprint)) return;
    var invoice = store.get(Invoice.class, job.target());
    if (invoice.deleted || invoice.version != job.revision()) return;
    var doc = invoice.documentId == null ? null : store.get(SourceDocument.class, invoice.documentId);
    var input = new ChunkedInput("reconciliation", doc, book.invoiceView(invoice), false, candidates, fingerprint);
    executeByChunk(job, models.select(input.task()), input);
  }

  private void extractDocument(JobQueue.Claim job) throws Exception {
    var doc = store.get(SourceDocument.class, job.target());
    if (doc.deleted || doc.version != job.revision()) return;
    if (doc.kind.equals("INVOICE")) {
      String task = PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS;
      var model = models.select(task);
      var request = invoiceRequests.automatic(model.name(), doc.text);
      var response = executeRequest(job, model, task, doc.sha256, request);
      publishExtraction(job, doc, List.of(response));
      return;
    }
    if (!doc.kind.equals("STATEMENT")) return;
    var input = new ChunkedInput("extract-statement", doc, Json.read(doc.draft), true, List.of(), null);
    publishExtraction(job, doc, executeByChunk(job, models.select(input.task()), input));
  }

  private record ChunkedInput(
      String task, SourceDocument document, Object subject, boolean headerOnly,
      List<Map<String, Object>> candidates, String candidateFingerprint) {}

  private record CompletedRequest(Map<String, Object> response, ExtractionAttempt.Prompt prompt) {}

  private CompletedRequest executeRequest(
      JobQueue.Claim job, AiProvider.Model model, String task, String documentHash,
      AiProvider.Request request) {
    var response = models.execute(job, model, task, documentHash, request);
    return new CompletedRequest(response, new ExtractionAttempt.Prompt(request.instructions(), request.input()));
  }

  private List<CompletedRequest> executeByChunk(
      JobQueue.Claim job, AiProvider.Model model, ChunkedInput input) throws Exception {
    var doc = input.document();
    var responses = new ArrayList<CompletedRequest>();
    var chunks = chunks(doc, doc == null ? "" : doc.text);
    int chunkNumber = 0;
    for (var chunk : chunks) {
      var images = new ArrayList<String>();
      if (model.vision() && doc != null && doc.mediaType.equals("application/pdf"))
        images.addAll(images(doc, chunk.pages()));
      var payload =
          map(
              "data",
              compact(input.subject(), input.headerOnly()),
              "sourceText",
              chunk.text(),
              "sourcePages",
              chunk.pages(),
              "part",
              ++chunkNumber,
              "parts",
              chunks.size(),
              "existingCategories",
              store.all(Category.class).stream()
                  .filter(c -> !c.deleted)
                  .map(c -> map("name", c.name, "aliases", Json.read(c.aliases)))
                  .toList(),
              "rules",
              map(
                  "currency",
                  "EUR",
                  "crossYearMonths",
                  settings.get("crossYearMonths", "3"),
                  "ambiguousMatches",
                  "SUGGEST_ONLY"),
              "candidates",
              input.candidates(),
              "candidateFingerprint",
              input.candidateFingerprint());
      String serialized = Json.write(payload);
      String prompt = prompts.prompt(input.task()) + "\n"
          + prompts.prompt(PromptCatalog.HUMAN_FIELD_AUTHORITY_INSTRUCTIONS).stripTrailing();
      if (serialized.length() > 50000)
        throw DomainException.invalid(
            "O conteúdo excede o contexto seguro; revê este documento manualmente");
      var request = new AiProvider.Request(model.name(), prompt, serialized, prompts.schema(input.task()), images);
      var completed = executeRequest(job, model, input.task(), doc == null ? "" : doc.sha256, request);
      responses.add(completed);
      if (input.candidateFingerprint() != null) validateCandidateIds(completed.response(), input.candidates());
    }
    return responses;
  }

  private void validateCandidateIds(Map<String, Object> response, List<Map<String, Object>> candidates) {
    var allowed = candidates.stream()
        .map(c -> Json.read(Json.write(c.get("movement"))).path("id").asText())
        .collect(java.util.stream.Collectors.toSet());
    for (var ranked : rows(response, "rankedCandidates"))
      if (!allowed.contains(ranked.get("movementId").toString()))
        throw DomainException.invalid("A IA sugeriu um movimento que não está nos candidatos");
  }

  private void publishExtraction(
      JobQueue.Claim job, SourceDocument doc, List<CompletedRequest> requests) {
    var merged = merge(requests.stream().map(CompletedRequest::response).toList(), doc.kind);
    String source = doc.text;
    var draft = Json.object(Json.write(merged.get("draft")));
    draft.put("issues", merged.get("issues"));
    boolean safe =
        (doc.kind.equals("INVOICE")
                ? AiEvidence.supportsInvoice(draft, rows(merged, "evidence"), source)
                : AiEvidence.supportsStatement(draft, rows(merged, "evidence"), source))
            && ((List<?>) merged.get("issues")).isEmpty();
    draft.put("validate", safe);
    if (doc.kind.equals("INVOICE") && !rows(draft, "invoices").isEmpty()) {
      int n = 0;
      for (var child : rows(draft, "invoices")) {
        String prefix = "invoices[" + (n++) + "].";
        var proof =
            rows(merged, "evidence").stream()
                .filter(e -> Objects.toString(e.get("field"), "").startsWith(prefix))
                .map(
                    e -> {
                      var copy = new LinkedHashMap<>(e);
                      copy.put("field", e.get("field").toString().substring(prefix.length()));
                      return (Map<String, Object>) copy;
                    })
                .toList();
        boolean valid =
            AiEvidence.supportsInvoice(child, proof, source)
                && ((List<?>) merged.get("issues")).isEmpty();
        child.put("validate", valid);
      }
      draft.put("validate", ((List<?>) merged.get("issues")).isEmpty());
    }
    var requestPrompts = requests.stream().map(CompletedRequest::prompt).toList();
    publisher.aiExtracted(doc.id, job.revision(), draft, Json.write(merged.get("evidence")), requestPrompts);
  }

  /**
   * Keeps header/control data bounded; complete source pages and individual movement reviews remain
   * available.
   */
  private Object compact(Object subject, boolean headerOnly) {
    var result = Json.object(Json.write(subject));
    result.remove("aiReview");
    // Category decisions have their own model, revision and evidence. Financial reviews must
    // not be invalidated by category-only changes or learn a second assignment protocol.
    result.remove("classification");
    result.remove("categoryDetails");
    result.remove("categoryIds");
    result.remove("documentVersion");
    result.remove("fieldProvenance");
    result.remove("reviewIssues");
    result.remove("categories");
    if (headerOnly)
      for (String key : List.of("movements", "lines", "rows", "sheets")) {
        Object removed = result.remove(key);
        if (removed instanceof List<?> list) result.put(key + "Count", list.size());
      }
    if (result.get("description") instanceof String text && text.length() > 2000)
      result.put("description", text.substring(0, 2000) + " [consultar o texto da página]");
    return result;
  }

  private record Chunk(String text, List<Integer> pages) {}

  private List<Chunk> chunks(SourceDocument doc, String source) {
    var result = new ArrayList<Chunk>();
    if (doc != null && Json.read(doc.layout).path("pages").isArray()) {
      for (var page : Json.read(doc.layout).path("pages")) {
        String text = page.path("text").asText();
        int number = page.path("page").asInt(result.size() + 1);
        for (int from = 0; from < Math.max(1, text.length()); from += 32000)
          result.add(
              new Chunk(
                  text.substring(from, Math.min(from + 32000, text.length())),
                  List.of(number)));
      }
    }
    if (result.isEmpty())
      for (int from = 0; from < Math.max(1, source.length()); from += 32000)
        result.add(
            new Chunk(
                source.substring(from, Math.min(from + 32000, source.length())),
                List.of()));
    if (result.size() > 1 && result.size() <= 3 && source.length() < 32000) {
      var pages = result.stream().flatMap(c -> c.pages().stream()).distinct().toList();
      return List.of(new Chunk(source, pages));
    }
    return result;
  }

  private List<String> images(SourceDocument doc, List<Integer> pages) throws Exception {
    var images = new ArrayList<String>();
    if (pages.isEmpty()) return images;
    try (var pdf = Loader.loadPDF(storage.path(doc.storageKey).toFile())) {
      var renderer = new PDFRenderer(pdf);
      for (int page : pages) {
        if (page < 1 || page > pdf.getNumberOfPages()) continue;
        var output = new ByteArrayOutputStream();
        ImageIO.write(renderer.renderImageWithDPI(page - 1, 110), "png", output);
        images.add(Base64.getEncoder().encodeToString(output.toByteArray()));
      }
    }
    return images;
  }

  private Map<String, Object> merge(List<Map<String, Object>> responses, String kind) {
    var draft = new LinkedHashMap<String, Object>();
    var issues = new LinkedHashSet<String>();
    var evidence = new ArrayList<Map<String, Object>>();
    var lineItems = new ArrayList<Map<String, Object>>();
    var categories = new LinkedHashSet<String>();
    var invoices = new ArrayList<Map<String, Object>>();
    for (var response : responses) {
      var part = Json.object(Json.write(response.get("draft")));
      invoices.addAll(rows(part, "invoices"));
      int offset = lineItems.size();
      for (var entry : rows(response, "evidence")) {
        var e = new LinkedHashMap<>(entry);
        String field = Objects.toString(e.get("field"), "");
        var matcher =
            java.util.regex.Pattern.compile("^(lines|movements)\\[(\\d+)](.*)$").matcher(field);
        if (matcher.matches())
          e.put(
              "field",
              matcher.group(1)
                  + "["
                  + (Integer.parseInt(matcher.group(2)) + offset)
                  + "]"
                  + matcher.group(3));
        evidence.add(e);
      }
      if (response.get("issues") instanceof List<?> list)
        list.forEach(i -> issues.add(i.toString()));
      lineItems.addAll(rows(part, kind.equals("STATEMENT") ? "movements" : "lines"));
      if (part.get("categories") instanceof List<?> list)
        list.forEach(c -> categories.add(c.toString()));
      for (var entry : part.entrySet()) {
        if (Set.of("movements", "lines", "categories").contains(entry.getKey())
            || entry.getValue() == null) continue;
        if (!draft.containsKey(entry.getKey())) draft.put(entry.getKey(), entry.getValue());
        else if (!Objects.equals(draft.get(entry.getKey()), entry.getValue())
            && Set.of("total", "issuedOn", "number", "supplier").contains(entry.getKey()))
          issues.add("Páginas com valores diferentes para " + entry.getKey());
      }
    }
    draft.put(kind.equals("STATEMENT") ? "movements" : "lines", lineItems);
    if (!kind.equals("STATEMENT")) draft.put("categories", new ArrayList<>(categories));
    if (!invoices.isEmpty()) draft.put("invoices", invoices);
    return map("draft", draft, "evidence", evidence, "issues", new ArrayList<>(issues));
  }
}
