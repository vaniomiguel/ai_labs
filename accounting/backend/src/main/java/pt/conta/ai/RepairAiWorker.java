package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pt.conta.document.DocumentStorage;
import pt.conta.document.ExtractionAttempt;
import pt.conta.jobs.JobQueue;
import pt.conta.review.RepairService;
import pt.conta.shared.*;

/** Explicit repairs analyse the complete source in one fresh, versioned model execution. */
@Service
public class RepairAiWorker {
  private final RepairService repairs;
  private final AiProvider provider;
  private final PromptCatalog prompts;
  private final Settings settings;
  private final DocumentStorage storage;
  private final AiResults results;
  private final InvoiceExtractionRequests invoiceRequests;
  private final int maxInput;
  private final int maxPages;

  public RepairAiWorker(
      RepairService repairs, AiProvider provider, PromptCatalog prompts, Settings settings,
      DocumentStorage storage, AiResults results, InvoiceExtractionRequests invoiceRequests,
      @Value("${accounting.repair-max-input-characters:50000}") int maxInput,
      @Value("${accounting.repair-max-image-pages:16}") int maxPages) {
    this.repairs = repairs;
    this.provider = provider;
    this.prompts = prompts;
    this.settings = settings;
    this.storage = storage;
    this.results = results;
    this.invoiceRequests = invoiceRequests;
    this.maxInput = maxInput;
    this.maxPages = maxPages;
  }

  private record PreparedRepair(AiProvider.Request request, Map<String, Object> context) {}

  public void process(JobQueue.Claim job) throws Exception {
    var source = repairs.begin(job);
    if (source == null) return;
    String task = switch (source.kind()) {
      case "INVOICE" -> PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS;
      case "STATEMENT" -> "extract-statement";
      default -> "review";
    };
    var model = selectModel(task);
    var prepared = source.kind().equals("INVOICE")
        ? invoiceRepair(source, model) : documentRepair(source, model, task);
    repairs.context(job, prepared.context());
    if (!source.kind().equals("INVOICE"))
      checkSize(prepared.request().input(), prepared.request().instructions(), task);
    execute(job, source, model, prepared);
  }

  private AiProvider.Model selectModel(String task) {
    String selected = settings.get("model:" + task, "qwen3.5:9b");
    return provider.models().stream().filter(AiProvider.Model::completion)
        .filter(m -> m.name().equals(selected)).findFirst()
        .orElseThrow(() -> new OllamaProvider.AiUnavailable(
            "Instala ou seleciona um modelo de geração: " + selected, null));
  }

  private PreparedRepair invoiceRepair(RepairService.Snapshot source, AiProvider.Model model) {
    var context = context(source, model, List.of(), List.of());
    var input = input(source, List.of());
    input.put("context", context);
    var request = invoiceRequests.repair(model.name(), source.text(), input, maxInput);
    return new PreparedRepair(request, context);
  }

  private PreparedRepair documentRepair(
      RepairService.Snapshot source, AiProvider.Model model, String task) throws Exception {
    String prompt = prompts.prompt(task) + "\n"
        + prompts.prompt(PromptCatalog.REPAIR_ANALYSIS_INSTRUCTIONS).stripTrailing();
    var pages = new ArrayList<Integer>();
    var images = new ArrayList<String>();
    // Reject oversized text before rendering; the model must receive the whole source.
    var input = input(source, pages);
    checkSize(Json.write(input), prompt, task);
    if (model.vision() && source.mediaType().equals("application/pdf")) renderPages(source, pages, images);
    var context = context(source, model, pages, images);
    input.put("context", context);
    String serialized = Json.write(input);
    var request = new AiProvider.Request(model.name(), prompt, serialized, prompts.schema(task), images, true);
    return new PreparedRepair(request, context);
  }

  private Map<String, Object> input(RepairService.Snapshot source, List<Integer> pages) {
    return map("data", source.draft(), "fieldProvenance", source.fieldProvenance(),
        "sourceText", source.text(), "alert", source.message(),
        "documentVersion", source.sourceVersion(), "sourcePages", pages);
  }

  private Map<String, Object> context(
      RepairService.Snapshot source, AiProvider.Model model, List<Integer> pages, List<String> images) {
    return map("model", model.name(), "modelDigest", model.digest(), "vision", model.vision(),
        "textComplete", true, "textCharacters", source.text().length(), "pages", pages,
        "imageHashes", images.stream().map(Hashes::sha256).toList(), "documentSha256", source.sha256());
  }

  private void renderPages(
      RepairService.Snapshot source, List<Integer> pages, List<String> images) throws Exception {
    try (var pdf = Loader.loadPDF(storage.path(source.storageKey()).toFile())) {
      if (pdf.getNumberOfPages() > maxPages)
        throw new AiProvider.ContextExceeded(
            "O documento tem " + pdf.getNumberOfPages() + " páginas; o limite desta análise visual é "
                + maxPages + ". Ajusta a capacidade configurada ou revê o documento manualmente. Nenhuma página foi omitida.");
      var renderer = new PDFRenderer(pdf);
      for (int n = 0; n < pdf.getNumberOfPages(); n++) {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(renderer.renderImageWithDPI(n, 110), "png", bytes);
        images.add(Base64.getEncoder().encodeToString(bytes.toByteArray()));
        pages.add(n + 1);
      }
    }
  }

  private void execute(
      JobQueue.Claim job, RepairService.Snapshot source, AiProvider.Model model, PreparedRepair prepared) {
    var request = prepared.request();
    long start = System.nanoTime();
    String raw = provider.complete(request);
    var execution = new AiExecution();
    execution.targetId = job.target();
    execution.targetVersion = job.revision();
    execution.task = "repair";
    execution.model = model.name();
    execution.modelDigest = model.digest();
    execution.promptVersion = "repair-v1:" + Hashes.sha256(request.instructions());
    execution.inputHash = Hashes.sha256(request.input() + request.instructions() + job.id());
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
    var prompt = new ExtractionAttempt.Prompt(request.instructions(), request.input());
    if (!repairs.complete(job, source, parsed, prepared.context(), prompt))
      results.categoryOutcome(execution.id, "STALE");
  }

  private void checkSize(String input, String prompt, String task) {
    if ((long) input.length() + prompt.length() + Json.write(prompts.schema(task)).length() > maxInput)
      throw new AiProvider.ContextExceeded(
          "O texto integral e as instruções excedem o contexto configurado desta análise. Seleciona um modelo/configuração com maior capacidade ou revê o documento manualmente. O conteúdo não foi cortado.");
  }
}
