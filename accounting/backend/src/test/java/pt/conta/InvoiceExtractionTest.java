package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static pt.conta.shared.Inputs.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import pt.conta.ai.*;
import pt.conta.category.CategoryAiWorker;
import pt.conta.document.*;
import pt.conta.jobs.JobQueue;
import pt.conta.ledger.BookkeepingService;
import pt.conta.shared.*;

/** Exercises invoice request assembly, caching and publication gates with controlled model output. */
class InvoiceExtractionTest {
  private static final String SOURCE =
      "Emissão 2026-01-01\nFornecedor de teste\nArtigo 100,00\nTOTAL A PAGAR 100,00\n";
  @TempDir Path overrides;
  private final Store store = mock(Store.class);
  private final AiProvider provider = mock(AiProvider.class);
  private final AiResults results = mock(AiResults.class);
  private final DocumentPublisher publisher = mock(DocumentPublisher.class);
  private final DocumentStorage storage = mock(DocumentStorage.class);
  private final List<AiExecution> executions = new ArrayList<>();
  private PromptCatalog prompts;
  private InvoiceExtractionRequests requests;
  private AiOrchestrator orchestrator;
  private SourceDocument doc;

  @BeforeEach
  void setup() {
    var properties = mock(AccountingProperties.class);
    when(properties.promptDirectory()).thenReturn(overrides.toString());
    prompts = new PromptCatalog(properties);
    requests = new InvoiceExtractionRequests(prompts,
        new InvoiceRepairContext(new FieldProtection(mock(pt.conta.audit.AuditService.class))));
    var settings = mock(Settings.class);
    when(settings.get(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
    when(provider.models()).thenReturn(List.of(
        new AiProvider.Model("qwen3.5:9b", "invoice-test-digest", true, true, true)));
    when(provider.complete(any())).thenAnswer(_ -> Json.write(response()));
    when(store.all(AiExecution.class)).thenReturn(executions);
    doAnswer(call -> { executions.add(call.getArgument(0)); return null; }).when(results).record(any());
    doc = new SourceDocument();
    doc.kind = "INVOICE";
    doc.mediaType = "application/pdf";
    doc.storageKey = "must-not-be-read.pdf";
    doc.sha256 = "original-document-hash";
    doc.text = SOURCE;
    when(store.get(SourceDocument.class, doc.id)).thenReturn(doc);
    orchestrator = new AiOrchestrator(store, new AiModelExecution(store, provider, results, settings),
        prompts, settings, publisher, storage, mock(BookkeepingService.class), mock(CategoryAiWorker.class),
        mock(RepairAiWorker.class), requests, null);
  }

  private Map<String, Object> invoice() {
    return map("issuedOn", "2026-01-01", "supplier", "Fornecedor de teste", "total", "100.00",
        "currency", "EUR", "type", "INVOICE", "categories", List.of(),
        "lines", List.of(map("description", "Artigo", "total", "100.00")));
  }

  private List<Map<String, Object>> evidence() {
    return List.of(
        map("field", "issuedOn", "quote", "Emissão 2026-01-01", "page", null),
        map("field", "supplier", "quote", "Fornecedor de teste", "page", null),
        map("field", "total", "quote", "TOTAL A PAGAR 100,00", "page", null),
        map("field", "lines[0].total", "quote", "Artigo 100,00", "page", null));
  }

  private Map<String, Object> response() {
    return map("draft", invoice(), "evidence", evidence(), "issues", List.of());
  }

  private void process() throws Exception {
    orchestrator.process(new JobQueue.Claim(
        UUID.randomUUID(), "AI_EXTRACT_DOCUMENT", doc.id, doc.version, "{}", 0));
  }

  private Map<String, Object> publishedDraft() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> draft = ArgumentCaptor.forClass(Map.class);
    verify(publisher).aiExtracted(eq(doc.id), eq(doc.version), draft.capture(), anyString(), anyList());
    return draft.getValue();
  }

  @Test
  void sendsCompleteTextAndFieldInstructionsOnceWithoutReadingPdf() throws Exception {
    doc.text = SOURCE + "texto integral ".repeat(2300) + "FIM";
    doc.layout = Json.write(map("pages", List.of(map("page", 1, "text", "excerto incompleto"))));
    doc.draft = Json.write(map("supplier", "Parsed value must not reach extraction"));
    process();
    var captured = ArgumentCaptor.forClass(AiProvider.Request.class);
    verify(provider).complete(captured.capture());
    var request = captured.getValue();
    assertThat(Json.object(request.input())).containsOnlyKeys("fields", "sourceText");
    assertThat(Json.read(request.input()).path("sourceText").asText()).isEqualTo(doc.text);
    assertThat(Json.read(request.input()).path("fields").asText())
        .isEqualTo(prompts.prompt(PromptCatalog.INVOICE_EXTRACTION_FIELD_INSTRUCTIONS));
    assertThat(request.instructions())
        .isEqualTo(prompts.prompt(PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS))
        .containsSubsequence("look first to the text surrounding each of those dates",
            "same month and year", "pick the earliest one", "Otherwise, if the invoice date remains uncertain, use null")
        .contains("Explicitly identified non-invoice dates are not candidates");
    assertThat(Json.read(request.input()).path("fields").asText()).doesNotContain("dueOn");
    assertThat(Json.write(request.schema())).doesNotContain("dueOn");
    assertThat(request.schema()).isEqualTo(prompts.schema("extract-invoice"));
    assertThat(request.requireFullContext()).isTrue();
    assertThat(request.images()).isEmpty();
    verifyNoInteractions(storage);
    assertThat(publishedDraft()).containsEntry("validate", true);
  }

  @Test
  void modelOutputWithRetiredDueDateIsRejectedForRootAndIndependentReceipts() {
    var draft = invoice();
    draft.put("dueOn", "2026-02-01");
    var schema = prompts.schema(PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS);
    assertThatThrownBy(() -> StructuredOutput.parse(Json.write(
        map("draft", draft, "evidence", List.of(), "issues", List.of())), schema))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dueOn");
    var outer = invoice();
    outer.put("invoices", List.of(draft));
    assertThatThrownBy(() -> StructuredOutput.parse(Json.write(
        map("draft", outer, "evidence", List.of(), "issues", List.of())), schema))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dueOn");
  }

  @Test
  void validCacheIsReusedButSystemAndFieldOverridesInvalidateIt() throws Exception {
    process();
    process();
    verify(provider, times(1)).complete(any());
    Files.writeString(overrides.resolve("extract-invoice.md"), "Custom system instructions\n");
    process();
    Files.writeString(overrides.resolve("extract-invoice-fields.md"), "Custom requested fields\n");
    process();
    var captured = ArgumentCaptor.forClass(AiProvider.Request.class);
    verify(provider, times(3)).complete(captured.capture());
    var latest = captured.getValue();
    assertThat(latest.instructions()).isEqualTo("Custom system instructions\n");
    assertThat(Json.read(latest.input()).path("fields").asText()).isEqualTo("Custom requested fields\n");
    assertThat(executions).extracting(e -> e.inputHash).doesNotHaveDuplicates();
    var sent = captured.getAllValues();
    verify(publisher, times(2)).aiExtracted(eq(doc.id), eq(doc.version), anyMap(), anyString(),
        eq(List.of(new ExtractionAttempt.Prompt(sent.getFirst().instructions(), sent.getFirst().input()))));
    verify(publisher).aiExtracted(eq(doc.id), eq(doc.version), anyMap(), anyString(),
        eq(List.of(new ExtractionAttempt.Prompt(latest.instructions(), latest.input()))));
  }

  @Test
  void missingEvidencePreventsAutomaticValidation() throws Exception {
    var response = response();
    response.put("evidence", List.of());
    when(provider.complete(any())).thenReturn(Json.write(response));
    process();
    assertThat(publishedDraft()).containsEntry("validate", false);
  }

  @Test
  void unknownInvoiceDescriptionDoesNotPreventAutomaticValidation() throws Exception {
    doc.text = "Emissão 2026-01-01\nFornecedor de teste\nTOTAL A PAGAR 100,00\n";
    var draft = invoice();
    draft.put("description", null);
    draft.put("lines", List.of());
    var headerEvidence = evidence().subList(0, 3);
    when(provider.complete(any())).thenReturn(Json.write(
        map("draft", draft, "evidence", headerEvidence, "issues", List.of())));

    process();

    var published = publishedDraft();
    assertThat(published.get("description")).isNull();
    assertThat(published).containsEntry("validate", true).containsEntry("issues", List.of());
  }

  @Test
  void independentReceiptsKeepTheirOwnIndexedEvidenceAndValidation() throws Exception {
    var outer = invoice();
    outer.put("issuedOn", null);
    outer.put("supplier", null);
    outer.put("total", null);
    outer.put("lines", List.of());
    outer.put("invoices", List.of(invoice(), invoice()));
    var proof = new ArrayList<Map<String, Object>>();
    for (int n = 0; n < 2; n++) {
      for (var entry : evidence()) {
        var indexed = new LinkedHashMap<>(entry);
        indexed.put("field", "invoices[" + n + "]." + entry.get("field"));
        proof.add(indexed);
      }
    }
    when(provider.complete(any())).thenReturn(Json.write(
        map("draft", outer, "evidence", proof, "issues", List.of())));
    process();
    var draft = publishedDraft();
    assertThat(rows(draft, "invoices")).hasSize(2).allSatisfy(
        child -> assertThat(child).containsEntry("total", "100.00").containsEntry("validate", true));
    assertThat(draft.get("total")).isNull();
  }

  @Test
  void malformedOutputIsRecordedAndNeverPublishedOrCached() {
    when(provider.complete(any())).thenReturn("{\"draft\":\"invalid\"}");
    assertThatThrownBy(this::process).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(this::process).isInstanceOf(IllegalArgumentException.class);
    assertThat(executions).hasSize(2).allSatisfy(e -> assertThat(e.outcome).isEqualTo("INVALID"));
    verifyNoInteractions(publisher);
  }

  @Test
  void oversizedAutomaticInputFailsBeforeGenerationWithoutTruncation() {
    doc.text = "X".repeat(50000);
    assertThatThrownBy(this::process).isInstanceOf(AiProvider.ContextExceeded.class)
        .hasMessageContaining("não foi cortado");
    verify(provider, never()).complete(any());
    verifyNoInteractions(storage, publisher);
  }

  @Test
  void blankAutomaticTextFailsBeforeGeneration() {
    doc.text = " \n\t";
    assertThatThrownBy(this::process).isInstanceOf(DomainException.class);
    verify(provider, never()).complete(any());
    verifyNoInteractions(storage, publisher);
  }

  @Test
  void providerContextFailurePropagatesWithoutPublication() {
    when(provider.complete(any())).thenThrow(new AiProvider.ContextExceeded("context window"));
    assertThatThrownBy(this::process).isInstanceOf(AiProvider.ContextExceeded.class);
    verifyNoInteractions(publisher);
  }

  @Test
  void repairLimitCountsSystemInputAndSchemaAndUsesTheSameOverrides() throws Exception {
    Files.writeString(overrides.resolve("repair-invoice.md"), "Custom system\n");
    Files.writeString(overrides.resolve("extract-invoice-fields.md"), "Custom fields\n");
    var context = map("data", map("supplier", "Human supplier"), "alert", "Review this invoice");
    var request = requests.repair("test", SOURCE, context, 50000);
    int size = request.instructions().length() + request.input().length() + Json.write(request.schema()).length();
    assertThat(requests.repair("test", SOURCE, context, size)).isEqualTo(request);
    assertThatThrownBy(() -> requests.repair("test", SOURCE, context, size - 1))
        .isInstanceOf(AiProvider.ContextExceeded.class);
    assertThat(request.instructions()).isEqualTo("Custom system\n");
    var input = Json.read(request.input());
    assertThat(input.path("responseSchema")).isEqualTo(Json.read(Json.write(request.schema())));
    assertThat(input.path("responseTemplate").path("draft").path("supplier").asText())
        .isEqualTo("Human supplier");
    assertThatCode(() -> StructuredOutput.parse(input.path("responseTemplate").toString(), request.schema()))
        .doesNotThrowAnyException();
    assertThat(requests.automatic("test", SOURCE).instructions())
        .isEqualTo(prompts.prompt(PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS));
    assertThat(Json.read(request.input()).path("fields").asText()).isEqualTo("Custom fields\n");
    assertThat(Json.read(request.input()).path("sourceText").asText()).isEqualTo(SOURCE);
    assertThat(request.images()).isEmpty();
    assertThat(request.requireFullContext()).isTrue();
  }
}
