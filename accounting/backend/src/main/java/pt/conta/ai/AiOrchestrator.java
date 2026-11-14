package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import pt.conta.bank.BankMovement;
import pt.conta.document.*;
import pt.conta.invoice.*;
import pt.conta.jobs.JobQueue;
import pt.conta.ledger.BookkeepingService;
import pt.conta.reconciliation.Allocation;
import pt.conta.review.Alert;
import pt.conta.shared.*;

/**
 * Bounded document/model orchestration; external calls never execute inside financial transactions.
 */
@Service
public class AiOrchestrator {
  private final pt.conta.category.CategoryAiWorker categoryWorker;
  private final RepairAiWorker repairWorker;
  private final Store store;
  private final AiProvider provider;
  private final PromptCatalog prompts;
  private final Settings settings;
  private final AiResults results;
  private final DocumentPublisher publisher;
  private final DocumentStorage storage;
  private final BookkeepingService book;

  @org.springframework.beans.factory.annotation.Value("${accounting.ai-history-months:}")
  private String historyMonths;

  public AiOrchestrator(
      Store store,
      AiProvider provider,
      PromptCatalog prompts,
      Settings settings,
      AiResults results,
      DocumentPublisher publisher,
      DocumentStorage storage,
      BookkeepingService book, pt.conta.category.CategoryAiWorker categoryWorker, RepairAiWorker repairWorker) {
    this.repairWorker = repairWorker;
    this.categoryWorker = categoryWorker;
    this.store = store;
    this.provider = provider;
    this.prompts = prompts;
    this.settings = settings;
    this.results = results;
    this.publisher = publisher;
    this.storage = storage;
    this.book = book;
  }

  /** Executes one extraction, review or explicitly requested repair task. */
  public void process(JobQueue.Claim job) throws Exception {
    if (job.kind().equals("REPAIR_ALERT")) { repairWorker.process(job); return; }
    if (Set.of("NORMALIZE_CATEGORIES", "CLASSIFY_INVOICE", "CLASSIFY_MOVEMENT", "REFINE_CATEGORIES").contains(job.kind())) {
      categoryWorker.process(job);
      return;
    }
    SourceDocument doc = null;
    Object subject;
    BaseEntity target;
    boolean suggest = job.kind().equals("SUGGEST_RECONCILIATION");
    var candidateContext =
        suggest
            ? book.candidateContext(job.target(), book.candidates())
            : List.<Map<String, Object>>of();
    if (suggest
        && (candidateContext.isEmpty()
            || !Hashes.sha256(Json.write(candidateContext))
                .equals(Json.read(job.payload()).path("fingerprint").asText()))) return;
    if (job.kind().equals("REVIEW_INVOICE") || suggest) {
      var i = store.get(Invoice.class, job.target());
      target = i;
      if (i.deleted) return;
      subject = book.invoiceView(i);
      if (i.documentId != null) doc = store.get(SourceDocument.class, i.documentId);
    } else if (job.kind().equals("REVIEW_MOVEMENT")) {
      var m = store.get(BankMovement.class, job.target());
      target = m;
      if (m.deleted) return;
      subject = book.movementView(m);
      if (m.documentId != null) doc = store.get(SourceDocument.class, m.documentId);
    } else if (job.kind().equals("REVIEW_ALLOCATION")) {
      var a = store.get(Allocation.class, job.target());
      target = a;
      if (!a.active) return;
      var invoice = store.get(Invoice.class, a.invoiceId);
      subject =
          map(
              "allocation",
              book.allocationView(a),
              "invoice",
              book.invoiceView(invoice),
              "movement",
              book.movementView(store.get(BankMovement.class, a.movementId)));
      if (invoice.documentId != null) doc = store.get(SourceDocument.class, invoice.documentId);
    } else {
      doc = store.get(SourceDocument.class, job.target());
      target = doc;
      if (doc.deleted) return;
      subject = Json.read(doc.draft);
    }
    if (target.version != job.revision()) return;
    boolean extract =
        job.kind().equals("AI_EXTRACT_DOCUMENT")
            && doc != null
            && Set.of("INVOICE", "STATEMENT").contains(doc.kind);
    String task =
        suggest
            ? "reconciliation"
            : extract
                ? doc.kind.equals("STATEMENT") ? "extract-statement" : "extract-invoice"
                : "review";
    String selected = settings.get("model:" + task, "qwen3.5:9b");
    var model =
        provider.models().stream()
            .filter(AiProvider.Model::completion)
            .filter(m -> m.name().equals(selected))
            .findFirst()
            .orElseThrow(
                () ->
                    new OllamaProvider.AiUnavailable(
                        "Instala ou seleciona um modelo de geração: " + selected, null));
    String source = doc == null ? "" : doc.text;
    if (doc != null && doc.kind.equals("HISTORY") && !historyMonths.isBlank()) {
      subject = historyScope(Json.object(doc.draft));
      source = Json.write(subject);
    }
    var responses = new ArrayList<Map<String, Object>>();
    var chunks = chunks(doc, source);
    int chunkNumber = 0;
    for (var chunk : chunks) {
      var images = new ArrayList<String>();
      if (model.vision() && doc != null && doc.mediaType.equals("application/pdf"))
        images.addAll(images(doc, chunk.pages()));
      var payload =
          map(
              "data",
              compact(subject, extract || job.kind().equals("REVIEW_DOCUMENT")),
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
              candidateContext,
              "candidateFingerprint",
              suggest ? Json.read(job.payload()).path("fingerprint").asText() : null);
      String input = Json.write(payload), prompt = prompts.prompt(task);
      if (input.length() > 50000)
        throw DomainException.invalid(
            "O conteúdo excede o contexto seguro; revê este documento manualmente");
      String hash = hash(input + model.digest() + prompt + (doc == null ? "" : doc.sha256));
      var cached =
          store.all(AiExecution.class).stream()
              .filter(e -> e.inputHash.equals(hash) && "VALID".equals(e.outcome))
              .findFirst();
      String raw;
      long started = System.nanoTime();
      if (cached.isPresent()) raw = cached.get().response;
      else {
        raw =
            provider.complete(
                new AiProvider.Request(model.name(), prompt, input, prompts.schema(task), images));
        var execution = new AiExecution();
        execution.targetId = job.target();
        execution.targetVersion = job.revision();
        execution.task = task;
        execution.model = model.name();
        execution.modelDigest = model.digest();
        execution.promptVersion = "v1:" + hash(prompt);
        execution.inputHash = hash;
        execution.durationMs = (System.nanoTime() - started) / 1_000_000;
        execution.request = input;
        execution.response = raw;
        try {
          StructuredOutput.parse(raw, prompts.schema(task));
          execution.outcome = "VALID";
        } catch (Exception e) {
          execution.outcome = "INVALID";
          results.record(execution);
          throw e;
        }
        results.record(execution);
      }
      var parsed = StructuredOutput.parse(raw, prompts.schema(task));
      if (suggest) {
        var allowed =
            candidateContext.stream()
                .map(c -> Json.read(Json.write(c.get("movement"))).path("id").asText())
                .collect(java.util.stream.Collectors.toSet());
        for (var ranked : rows(parsed, "rankedCandidates"))
          if (!allowed.contains(ranked.get("movementId").toString()))
            throw DomainException.invalid("A IA sugeriu um movimento que não está nos candidatos");
      }
      responses.add(parsed);
    }
    if (suggest) return;
    if (extract) {
      var merged = merge(responses, doc.kind);
      var draft = Json.object(Json.write(merged.get("draft")));
      draft.put("issues", merged.get("issues"));
      boolean safe =
          (doc.kind.equals("INVOICE")
                  ? AiEvidence.supportsInvoice(draft, rows(merged, "evidence"), source)
                  : AiEvidence.supportsStatement(draft, rows(merged, "evidence"), source))
              && ((List<?>) merged.get("issues")).isEmpty();
      draft.put("validate", safe);
      if (doc.kind.equals("INVOICE") && !rows(draft, "invoices").isEmpty()) {
        boolean all = true;
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
          all &= valid;
        }
        draft.put("validate", ((List<?>) merged.get("issues")).isEmpty());
      }
      publisher.aiExtracted(doc.id, job.revision(), draft, Json.write(merged.get("evidence")));
    } else {
      var concerns = new ArrayList<Map<String, Object>>();
      var suggestions = new ArrayList<Object>();
      var summaries = new ArrayList<String>();
      for (var response : responses) {
        concerns.addAll(rows(response, "concerns"));
        if (response.get("suggestions") instanceof List<?> list) suggestions.addAll(list);
        summaries.add(Objects.toString(response.get("summary"), ""));
      }
      var combined =
          map(
              "verdict",
              concerns.isEmpty() ? "PASS" : "CONCERN",
              "concerns",
              concerns,
              "suggestions",
              suggestions,
              "summary",
              String.join("; ", summaries));
      results.reviewed(job, combined, source);
    }
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

  /** Restricts optional evaluation history reviews by original sheet, never by invoice date. */
  private Map<String, Object> historyScope(Map<String, Object> draft) {
    var monthNames =
        List.of(
            "JANEIRO",
            "FEVEREIRO",
            "MARCO",
            "ABRIL",
            "MAIO",
            "JUNHO",
            "JULHO",
            "AGOSTO",
            "SETEMBRO",
            "OUTUBRO",
            "NOVEMBRO",
            "DEZEMBRO");
    var allowed = new HashSet<String>();
    for (String part : historyMonths.split(",")) {
      int number = Integer.parseInt(part.trim());
      if (number < 1 || number > 12)
        throw DomainException.invalid("Mês de revisão histórica inválido");
      allowed.add(monthNames.get(number - 1));
    }
    var rows =
        rows(draft, "rows").stream()
            .filter(
                r ->
                    allowed.contains(
                        pt.conta.reconciliation.MatchingRules.normalize(
                            Objects.toString(r.get("sheet"), ""))))
            .toList();
    var sheets =
        rows(draft, "sheets").stream()
            .filter(
                s ->
                    allowed.contains(
                        pt.conta.reconciliation.MatchingRules.normalize(
                            Objects.toString(s.get("name"), ""))))
            .map(s -> map("name", s.get("name"), "controls", s.get("controls")))
            .toList();
    return map(
        "rows", rows, "sheets", sheets, "reviewMonths", historyMonths, "year", draft.get("year"));
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
                  text.substring(
                      Math.min(from, text.length()), Math.min(from + 32000, text.length())),
                  List.of(number)));
      }
    }
    if (result.isEmpty())
      for (int from = 0; from < Math.max(1, source.length()); from += 32000)
        result.add(
            new Chunk(
                source.substring(
                    Math.min(from, source.length()), Math.min(from + 32000, source.length())),
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
            java.util.regex.Pattern.compile("^(lines|movements)\\[(\\d+)\\](.*)$").matcher(field);
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

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
