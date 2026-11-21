package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static pt.conta.shared.Inputs.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.ai.*;
import pt.conta.bank.BankMovement;
import pt.conta.document.SourceDocument;
import pt.conta.invoice.Invoice;
import pt.conta.invoice.InvoiceLine;
import pt.conta.jobs.JobQueue;
import pt.conta.reconciliation.Allocation;
import pt.conta.review.Alert;
import pt.conta.shared.*;
import pt.conta.shared.Types.ReviewStatus;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AutomaticReviewIntegrationTest {
  private static final String TOTAL = "TOTAL A PAGAR 120,00";
  private static final String INVOICE = "Fatura nº FT 2026/001\nFornecedor: Fornecedor de teste\n"
      + "Data de emissão: 02/01/2026\n" + TOTAL + "\n";
  private static final String BANK_ROW = "03/01/2026 Compra de teste -8,00";
  private static EmbeddedPostgres postgres;
  private static Path promptOverrides;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    postgres = EmbeddedPostgres.builder().setPort(0).start();
    promptOverrides = Files.createTempDirectory("conta-automatic-review-overrides");
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("accounting.ai-history-months", () -> "1");
    registry.add("accounting.prompt-directory", () -> promptOverrides.toString());
  }

  @AfterAll
  static void closeDatabase() throws Exception {
    if (postgres != null) postgres.close();
    if (promptOverrides != null) Files.deleteIfExists(promptOverrides);
  }

  @TestConfiguration
  static class ProviderConfiguration {
    @Bean @Primary TestProvider reviewProvider() {
      return new TestProvider();
    }
  }

  static class TestProvider implements AiProvider {
    final List<Request> requests = new ArrayList<>();
    Function<Request, String> program;
    public List<Model> models() {
      return List.of(new Model("qwen3.5:9b", "review-test-digest", true, true, true));
    }
    public Completion complete(Request request) {
      requests.add(request);
      return new Completion(program.apply(request), new Usage(700, 100, 1000, CapacitySource.CONFIGURED,
          "stop", ContextWarning.NEAR_CONTEXT_LIMIT));
    }
  }

  @Autowired Store store;
  @Autowired JdbcTemplate jdbc;
  @Autowired JobQueue queue;
  @Autowired AiOrchestrator orchestrator;
  @Autowired TestProvider provider;
  @Autowired AiReviewStatus status;

  @BeforeEach
  void setup() {
    provider.requests.clear();
    provider.program = request -> result("PASS");
  }

  @Test
  void verifiesLabelledTotalAndReopensOnlyTheAutomaticInvoice() throws Exception {
    var doc = document("INVOICE", INVOICE);
    var invoice = invoice(doc);
    var sibling = invoice(document("INVOICE", INVOICE));
    var line = new InvoiceLine();
    line.invoiceId = invoice.id;
    line.description = "Artigo";
    line.total = new BigDecimal("100.00");
    store.add(line);
    invoice.fieldProvenance = Json.write(map("total", map("origin", "AUTOMATIC", "protected", false)));
    provider.program = request -> {
      var input = captured(request, "INVOICE", "supplier and supplier tax ID");
      assertThat(sources(input).getFirst()).containsEntry("text", INVOICE).containsEntry("coverage", "FULL");
      assertThat(field(input, "total")).containsEntry("origin", "AUTOMATIC").containsEntry("protected", false);
      assertThat(field(input, "lines[0].total")).containsEntry("recordedValue", "100.00");
      return result("CONCERN", finding("total", "100.00", "120.00", "invoice", TOTAL));
    };
    var job = claim("REVIEW_INVOICE", invoice);
    orchestrator.process(job);
    queue.finish(job, null, false);
    var report = report(job);
    assertThat(report).containsEntry("verdict", "CONCERN").containsEntry("coverage", "FULL");
    assertThat(rows(report, "concerns").getFirst()).containsEntry("verified", true).containsEntry("page", null);
    assertThat(invoice.reviewStatus).isEqualTo(ReviewStatus.OPEN);
    assertThat(invoice.total).isEqualByComparingTo("100.00");
    assertThat(sibling.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
    assertThat(store.all(Alert.class)).anyMatch(alert -> alert.entityId.equals(invoice.id) && alert.code.equals("AI_CONCERN"));
    assertThat(store.all(AiExecution.class)).anySatisfy(execution -> {
      assertThat(execution.task).isEqualTo("automatic-review");
      assertThat(execution.jobId).isEqualTo(job.id());
      assertThat(execution.model).isEqualTo("qwen3.5:9b");
      assertThat(execution.usage().warning()).isEqualTo(AiProvider.ContextWarning.NEAR_CONTEXT_LIMIT);
      assertThat(execution.response).contains(TOTAL);
    });
  }

  @Test
  void protectedFieldAndManualConfirmationKeepTheirAccountingStatus() throws Exception {
    var first = invoice(document("INVOICE", INVOICE));
    first.manuallyConfirmed = true;
    first.reviewStatus = ReviewStatus.CORRECTED;
    var second = invoice(document("INVOICE", INVOICE));
    second.fieldProvenance = Json.write(map("_legacy", map("protected", true)));
    provider.program = request -> {
      var input = captured(request, "INVOICE", "earliest");
      assertThat(field(input, "total")).containsEntry("protected", true);
      return result("CONCERN", finding("total", "100.00", "120.00", "invoice", TOTAL));
    };
    assertThat(run("REVIEW_INVOICE", first)).containsEntry("verdict", "CONCERN");
    assertThat(run("REVIEW_INVOICE", second)).containsEntry("verdict", "CONCERN");
    assertThat(first.manuallyConfirmed).isTrue();
    assertThat(first.reviewStatus).isEqualTo(ReviewStatus.CORRECTED);
    assertThat(second.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
    assertThat(store.all(Alert.class).stream().filter(a -> a.code.equals("AI_CONCERN"))).hasSize(2);
  }

  @Test
  void dateHeuristicAndAmbiguousTotalsRemainAdvisory() throws Exception {
    String date = "Data: 01/01/2026";
    var source = document("INVOICE", INVOICE.replace("Data de emissão: 02/01/2026", date)
        + "TOTAL COM IVA 119,00\nVencimento: 15/01/2026");
    var invoice = invoice(source);
    provider.program = request -> result("CONCERN",
        finding("issuedOn", "2026-01-03", "2026-01-01", "invoice", date),
        finding("total", "100.00", "120.00", "invoice", TOTAL));
    var report = run("REVIEW_INVOICE", invoice);
    assertThat(report).containsEntry("verdict", "SUGGESTION");
    assertThat(rows(report, "concerns")).hasSize(2).allMatch(c -> Boolean.FALSE.equals(c.get("verified")));
    assertThat(invoice.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
    assertThat(status.describe(invoice.id, invoice.version)).containsEntry("verdict", "SUGGESTION");
  }

  @Test
  void onlyAUniquelyLabelledIssueDateCanBeVerified() throws Exception {
    String labelled = "Data de emissão: 02/01/2026";
    var invoice = invoice(document("INVOICE", INVOICE + "Vencimento: 01/01/2026\n"));
    provider.program = request -> result("CONCERN", finding("issuedOn", "2026-01-03", "2026-01-02", "invoice", labelled));
    assertThat(run("REVIEW_INVOICE", invoice)).containsEntry("verdict", "CONCERN");
    assertThat(invoice.reviewStatus).isEqualTo(ReviewStatus.OPEN);
  }

  @Test
  void indexedStatementReviewReopensOnlyTheUniquelyIdentifiedChild() throws Exception {
    var doc = document("STATEMENT", BANK_ROW + "\n03/01/2026 Outro movimento -9,00\n");
    var movement = movement(doc, "Compra de teste");
    var sibling = movement(doc, "Outro movimento");
    doc.draft = Json.write(map("movements", List.of(movementRow(movement), movementRow(sibling))));
    provider.program = request -> {
      var input = captured(request, "DOCUMENT", "indexed saved items");
      assertThat(field(input, "movements[0].amount")).containsEntry("recordedValue", "7.00");
      assertThat(field(input, "movements[1].description")).containsEntry("recordedValue", "Outro movimento");
      return result("CONCERN", finding("movements[0].amount", "7.00", "8.00", "document", BANK_ROW));
    };
    assertThat(run("REVIEW_DOCUMENT", doc)).containsEntry("verdict", "CONCERN");
    assertThat(movement.reviewStatus).isEqualTo(ReviewStatus.OPEN);
    assertThat(sibling.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
    assertThat(doc.status).isEqualTo("PROCESSED");
  }

  @Test
  void documentReviewCanVerifyAnIndexedReceiptWhenTheSourceSeparatesReceipts() throws Exception {
    String secondReceipt = INVOICE.replace("2026/001", "2026/002").replace("120,00", "100,00");
    var doc = document("INVOICE", INVOICE + "\n" + secondReceipt);
    doc.layout = Json.write(map("pages", List.of(map("page", 1, "text", INVOICE),
        map("page", 2, "text", secondReceipt))));
    var first = invoice(doc);
    var second = invoice(doc);
    second.number = "FT 2026/002";
    doc.draft = Json.write(map("invoices", List.of(
        map("id", first.id, "number", first.number, "total", "100.00"),
        map("id", second.id, "number", second.number, "total", "100.00"))));
    provider.program = request -> {
      var input = captured(request, "DOCUMENT", "indexed saved items");
      assertThat(field(input, "invoices[1].number")).containsEntry("recordedValue", second.number);
      return result("CONCERN", finding("invoices[0].total", "100.00", "120.00", "document", TOTAL));
    };
    var report = run("REVIEW_DOCUMENT", doc);
    assertThat(report).containsEntry("verdict", "CONCERN");
    assertThat(rows(report, "concerns").getFirst()).containsEntry("page", 1);
    assertThat(first.reviewStatus).isEqualTo(ReviewStatus.OPEN);
    assertThat(second.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
  }

  @Test
  void explicitBankDirectionCanReopenAnAutomaticMovement() throws Exception {
    String credit = BANK_ROW.replace("-8,00", "+7,00");
    var movement = movement(document("STATEMENT", credit), "Compra de teste");
    provider.program = request -> result("CONCERN", finding("direction", "DEBIT", "CREDIT", "statement", credit));
    assertThat(run("REVIEW_MOVEMENT", movement)).containsEntry("verdict", "CONCERN");
    assertThat(movement.reviewStatus).isEqualTo(ReviewStatus.OPEN);
  }

  @Test
  void wrongSourceWrongValueAndUnrelatedFieldsAreDiscarded() throws Exception {
    var movement = movement(document("STATEMENT", BANK_ROW + "\nSaldo inicial 900,00"), "Compra de teste");
    provider.program = request -> {
      var input = captured(request, "MOVEMENT", "booking date from the value date");
      assertThat(sources(input).getFirst()).containsEntry("sourceId", "statement");
      return result("CONCERN",
          finding("amount", "7.00", "8.00", "invoice", BANK_ROW),
          finding("amount", "9.00", "8.00", "statement", BANK_ROW),
          finding("amount", "7.00", "8.00", "statement", BANK_ROW.replace("Compra de", "Compra   de")),
          finding("openingBalance", "700.00", "900.00", "statement", "Saldo inicial 900,00"),
          finding("categories", "A", "B", "statement", BANK_ROW));
    };
    var report = run("REVIEW_MOVEMENT", movement);
    assertThat(report).containsEntry("verdict", "SUGGESTION").containsEntry("concerns", List.of());
    assertThat(store.all(Alert.class)).isEmpty();
    assertThat(movement.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
  }

  @Test
  void duplicateBankRowsAndRunningBalancesDoNotVerify() throws Exception {
    var duplicate = movement(document("STATEMENT", BANK_ROW + "\n" + BANK_ROW), "Compra de teste");
    String balanceRow = "03-01-2026 03-01-2026 Compra de teste -8,00 300,00";
    var withBalance = movement(document("STATEMENT",
        "SALDO CONTABILISTICO APOS MOVIMENTO\n" + balanceRow), "Compra de teste");
    provider.program = request -> result("CONCERN", finding("amount", "7.00", "8.00", "statement", BANK_ROW));
    assertThat(run("REVIEW_MOVEMENT", duplicate)).containsEntry("verdict", "SUGGESTION");
    provider.program = request -> result("CONCERN", finding("amount", "7.00", "300.00", "statement", balanceRow));
    assertThat(run("REVIEW_MOVEMENT", withBalance)).containsEntry("verdict", "SUGGESTION");
    assertThat(duplicate.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
    assertThat(withBalance.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
  }

  @Test
  void allocationReviewsUseBothSourcesAndKeepPartialGroupedPaymentsActive() throws Exception {
    var invoice = invoice(document("INVOICE", INVOICE));
    String quote = "03/01/2026 Transferência FT 2026/003 e FT 2026/004 -160,00";
    var movement = movement(document("STATEMENT", quote), "Transferência FT 2026/003 e FT 2026/004");
    var allocation = new Allocation();
    allocation.invoiceId = invoice.id;
    allocation.movementId = movement.id;
    allocation.amount = new BigDecimal("4.00");
    allocation.origin = "RULES";
    store.add(allocation);
    provider.program = request -> {
      var input = captured(request, "ALLOCATION", "Partial payments, grouped payments");
      assertThat(sources(input)).extracting(source -> source.get("sourceId")).containsExactly("invoice", "statement");
      assertThat(field(input, "allocation.invoiceReference")).containsEntry("recordedValue", invoice.number);
      return result("CONCERN", finding("allocation.invoiceReference", invoice.number, "FT 2026/003", "statement", quote));
    };
    assertThat(run("REVIEW_ALLOCATION", allocation)).containsEntry("verdict", "SUGGESTION");
    assertThat(allocation.active).isTrue();
    assertThat(invoice.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
    assertThat(movement.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
  }

  @Test
  void fullTextPassPreservesTheRecordAndMissingSourcesAreInconclusive() throws Exception {
    String full = INVOICE + "texto integral ".repeat(2400);
    var invoice = invoice(document("INVOICE", full));
    provider.program = request -> {
      assertThat(sources(captured(request, "INVOICE", "earliest")).getFirst()).containsEntry("text", full);
      return result("PASS");
    };
    assertThat(run("REVIEW_INVOICE", invoice)).containsEntry("verdict", "PASS");
    assertThat(invoice.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
    assertThat(status.describe(invoice.id, invoice.version)).containsEntry("verdict", "PASS");
    var withoutSource = invoice(null);
    assertThat(run("REVIEW_INVOICE", withoutSource)).containsEntry("verdict", "SUGGESTION");
    assertThat(provider.requests).hasSize(1);
  }

  @Test
  void boundedExcerptsPreserveTheSourceAndCannotCiteOtherExcerpts() throws Exception {
    String original = ("Linha de OCR " + "x".repeat(680) + "\r\n").repeat(82) + INVOICE;
    var invoice = invoice(document("INVOICE", original));
    var parts = new TreeMap<Integer, String>();
    provider.program = request -> {
      var input = captured(request, "INVOICE", "earliest");
      assertThat(input).containsEntry("coverage", "PARTIAL");
      var excerpt = sources(input).getFirst();
      String text = (String) excerpt.get("text");
      parts.put(((Number) excerpt.get("offset")).intValue(), text);
      return text.contains(TOTAL) ? result("PASS")
          : result("CONCERN", finding("total", "100.00", "120.00", "invoice", TOTAL));
    };
    assertThat(run("REVIEW_INVOICE", invoice)).containsEntry("verdict", "SUGGESTION")
        .containsEntry("coverage", "PARTIAL").containsEntry("concerns", List.of());
    assertThat(provider.requests).hasSizeGreaterThan(1);
    assertThat(String.join("", parts.values())).isEqualTo(original);
  }

  @Test
  void providerContextFailureRetriesOnlyOneLevelAndReportsSkippedCoverage() throws Exception {
    var invoice = invoice(document("INVOICE", INVOICE));
    provider.program = request -> { throw new AiProvider.ContextExceeded("Test context limit"); };
    var report = run("REVIEW_INVOICE", invoice);
    assertThat(provider.requests).hasSize(3);
    assertThat(report).containsEntry("verdict", "SUGGESTION").containsEntry("skippedRequests", 2)
        .containsEntry("coverage", "PARTIAL");
    assertThat(rows(report, "portions")).hasSize(2)
        .allMatch(part -> "SKIPPED".equals(part.get("status")) && part.get("reason") != null);
  }

  @Test
  void staleSourceTargetAndLeaseCannotApplyAResult() throws Exception {
    assertStaleReview((job, invoice, doc) -> { doc.text = INVOICE + "updated"; store.flush(); });
    assertStaleReview((job, invoice, doc) -> { invoice.total = new BigDecimal("120.00"); store.flush(); });
    assertStaleReview((job, invoice, doc) -> jdbc.update("update job set lease_token=? where id=?", UUID.randomUUID(), job.id()));
    assertThat(store.all(Alert.class)).isEmpty();
  }

  @Test
  void malformedResponsesRemainRetryableAndHistoricalStatusStillLoads() throws Exception {
    var invoice = invoice(document("INVOICE", INVOICE));
    var claim = claim("REVIEW_INVOICE", invoice);
    provider.program = request -> "{";
    assertThatThrownBy(() -> orchestrator.process(claim)).isInstanceOf(IllegalArgumentException.class);
    assertThat(report(claim)).isEmpty();
    assertThat(store.all(AiExecution.class)).anyMatch(execution -> execution.outcome.equals("INVALID"));
    queue.finish(claim, "invalid response", false);
    assertThat(jdbc.queryForObject("select state from job where id=?", String.class, claim.id())).isEqualTo("QUEUED");

    var historical = new AiExecution();
    historical.task = "review";
    historical.targetId = UUID.randomUUID();
    historical.targetVersion = 0;
    historical.model = "historical";
    historical.outcome = "VALID";
    historical.response = result("PASS");
    store.add(historical);
    store.flush();
    assertThat(status.describe(historical.targetId, 0)).containsEntry("verdict", "PASS")
        .containsEntry("state", "REVIEWED");
  }

  @Test
  void historicalDocumentReviewKeepsIndexedFieldsInTheConfiguredPeriod() throws Exception {
    var january = map("name", "Janeiro", "cells", List.of(List.of("Janeiro", "100,00")));
    var february = map("name", "Fevereiro", "cells", List.of(List.of("Fevereiro", "200,00")));
    var doc = document("HISTORY", Json.write(List.of(january, february)));
    doc.draft = Json.write(map("sheets", List.of(january, february), "rows", List.of(
        map("sheet", "Janeiro", "description", "Janeiro"),
        map("sheet", "Fevereiro", "description", "Fevereiro"))));
    provider.program = request -> {
      var input = captured(request, "DOCUMENT", "historical workbooks");
      assertThat(field(input, "rows[0].description")).containsEntry("recordedValue", "Janeiro");
      assertThat(request.input()).doesNotContain("Fevereiro", "rows[1]");
      return result("PASS");
    };
    assertThat(run("REVIEW_DOCUMENT", doc)).containsEntry("verdict", "PASS");
  }

  @Test
  void dedicatedSystemAndChecklistCanBeOverriddenIndependently() throws Exception {
    Path system = promptOverrides.resolve("automatic-review.md");
    Path checklist = promptOverrides.resolve("automatic-review-invoice.md");
    try {
      Files.writeString(system, "Automatic review system override.");
      Files.writeString(checklist, "Invoice checklist override.");
      provider.program = request -> {
        assertThat(request.instructions()).isEqualTo("Automatic review system override.");
        assertThat(Json.object(request.input())).containsEntry("checklist", "Invoice checklist override.")
            .containsEntry("responseSchema", request.schema());
        return result("PASS");
      };
      assertThat(run("REVIEW_INVOICE", invoice(document("INVOICE", INVOICE)))).containsEntry("verdict", "PASS");
    } finally {
      Files.deleteIfExists(system);
      Files.deleteIfExists(checklist);
    }
  }

  @Test
  void longHistoricalWorkbooksSplitBetweenCompleteRows() throws Exception {
    var matrix = Collections.nCopies(90, List.of("Janeiro", "descrição " + "a".repeat(600)));
    String full = Json.write(List.of(map("name", "Janeiro", "cells", matrix)));
    var doc = document("HISTORY", full);
    doc.draft = Json.write(map("sheets", List.of(map("name", "Janeiro"))));
    var parts = new TreeMap<Integer, String>();
    provider.program = request -> {
      var source = sources(captured(request, "DOCUMENT", "historical workbooks")).getFirst();
      parts.put(((Number) source.get("offset")).intValue(), (String) source.get("text"));
      return result("PASS");
    };
    assertThat(run("REVIEW_DOCUMENT", doc)).containsEntry("verdict", "SUGGESTION");
    assertThat(parts).hasSizeGreaterThan(1);
    assertThat(String.join("", parts.values())).isEqualTo(full);
    assertThat(parts.tailMap(0, false).values()).allMatch(part -> part.startsWith("[\"Janeiro\","));
  }

  private void assertStaleReview(ReviewedMutation mutation) throws Exception {
    var doc = document("INVOICE", INVOICE);
    var invoice = invoice(doc);
    var job = claim("REVIEW_INVOICE", invoice);
    provider.program = request -> {
      mutation.run(job, invoice, doc);
      return result("CONCERN", finding("total", "100.00", "120.00", "invoice", TOTAL));
    };
    orchestrator.process(job);
    queue.finish(job, null, false);
    assertThat(report(job)).isEmpty();
    assertThat(invoice.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC);
  }

  private interface ReviewedMutation {
    void run(JobQueue.Claim job, Invoice invoice, SourceDocument document);
  }

  private SourceDocument document(String kind, String text) {
    var doc = new SourceDocument();
    doc.kind = kind;
    doc.text = text;
    doc.filename = UUID.randomUUID() + ".pdf";
    doc.storageKey = "unused-" + doc.id;
    doc.sha256 = Hashes.sha256(doc.filename);
    doc.mediaType = "application/pdf";
    doc.status = "PROCESSED";
    return store.add(doc);
  }

  private Invoice invoice(SourceDocument document) {
    var invoice = new Invoice();
    invoice.documentId = document == null ? null : document.id;
    invoice.issuedOn = LocalDate.parse("2026-01-03");
    invoice.number = "FT 2026/001";
    invoice.supplier = "Fornecedor de teste";
    invoice.total = new BigDecimal("100.00");
    invoice.origin = "AI";
    invoice.reviewStatus = ReviewStatus.AUTOMATIC;
    return store.add(invoice);
  }

  private BankMovement movement(SourceDocument document, String description) {
    var movement = new BankMovement();
    movement.documentId = document.id;
    movement.bookedOn = LocalDate.parse("2026-01-03");
    movement.description = description;
    movement.amount = new BigDecimal("7.00");
    movement.origin = "RULES";
    movement.reviewStatus = ReviewStatus.AUTOMATIC;
    return store.add(movement);
  }

  private Map<String, Object> movementRow(BankMovement movement) {
    return map("id", movement.id, "bookedOn", movement.bookedOn, "description", movement.description,
        "amount", Money.text(movement.amount), "direction", movement.direction);
  }

  private JobQueue.Claim claim(String kind, BaseEntity target) {
    store.flush();
    queue.enqueue(kind, target.id, target.version, "{}");
    var job = queue.claim().orElseThrow();
    assertThat(job.target()).isEqualTo(target.id);
    return job;
  }

  private Map<String, Object> run(String kind, BaseEntity target) throws Exception {
    var job = claim(kind, target);
    orchestrator.process(job);
    queue.finish(job, null, false);
    return report(job);
  }

  private Map<String, Object> report(JobQueue.Claim job) {
    String payload = jdbc.queryForObject("select payload from job where id=?", String.class, job.id());
    var report = Json.read(payload).path("reviewResult");
    return report.isMissingNode() ? Map.of() : Json.object(report.toString());
  }

  private Map<String, Object> captured(AiProvider.Request request, String kind, String checklist) {
    assertThat(request.instructions()).contains("experienced accountant", "Human-entered");
    assertThat(request.images()).isEmpty();
    assertThat(request.requireFullContext()).isTrue();
    assertThat(request.instructions().length() + request.input().length() + Json.write(request.schema()).length())
        .isLessThanOrEqualTo(50000);
    var input = Json.object(request.input());
    assertThat(input).containsEntry("reviewType", kind).containsEntry("responseSchema", request.schema())
        .containsKey("responseExample");
    assertThat(text(input, "checklist")).contains(checklist);
    return input;
  }

  private Map<String, Object> field(Map<String, Object> input, String path) {
    return rows(input, "fieldsToCheck").stream().filter(field -> path.equals(field.get("path")))
        .findFirst().orElseThrow();
  }

  private List<Map<String, Object>> sources(Map<String, Object> input) {
    return rows(input, "sources");
  }

  private static Map<String, Object> finding(String field, String recorded, String actual, String source, String quote) {
    return map("field", field, "recordedValue", recorded, "sourceValue", actual, "sourceId", source,
        "quote", quote, "page", 3, "reason", "O valor impresso diverge do registo.");
  }

  @SafeVarargs
  private static String result(String verdict, Map<String, Object>... concerns) {
    return Json.write(map("verdict", verdict, "concerns", List.of(concerns), "summary", "Resultado", "suggestions", List.of()));
  }
}
