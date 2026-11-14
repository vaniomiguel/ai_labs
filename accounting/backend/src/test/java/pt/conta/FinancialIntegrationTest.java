package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static pt.conta.shared.Inputs.map;
import static pt.conta.shared.Inputs.rows;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pt.conta.document.*;
import pt.conta.invoice.Invoice;
import pt.conta.jobs.*;
import pt.conta.ledger.*;
import pt.conta.reconciliation.Allocation;
import pt.conta.reconciliation.ExclusionRequest;
import pt.conta.reconciliation.ReconciliationService;
import pt.conta.bank.BankMovement;
import pt.conta.shared.*;

/** Exercises real PostgreSQL 18 migrations, transactional money invariants and session security. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class FinancialIntegrationTest {
  static EmbeddedPostgres postgres;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws IOException {
    postgres = EmbeddedPostgres.builder().setPort(0).start();
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add(
        "accounting.storage",
        () -> System.getProperty("java.io.tmpdir") + "/conta-integration-documents");
  }

  @Autowired BookkeepingService book;
  @Autowired LedgerService ledger;
  @Autowired Store store;
  @Autowired TransactionTemplate transactions;
  @Autowired DocumentService documents;
  @Autowired ProcessingService processing;
  @Autowired DocumentStorage storage;
  @Autowired JobQueue queue;
  @Autowired JobMonitor jobMonitor;
  @Autowired pt.conta.api.ProgressController progress;
  @Autowired JdbcTemplate jdbc;
  @Autowired DocumentPublisher publisher;
  @Autowired pt.conta.ai.AiResults aiResults;
  @Autowired pt.conta.api.FinanceController finance;
  @Autowired pt.conta.history.ZipImporter zipImporter;

  @Value("${local.server.port}")
  int port;

  UUID account;

  @BeforeEach
  void account() {
    transactions.executeWithoutResult(
        status -> {
          var a = new pt.conta.bank.BankAccount();
          a.name = "Conta de teste";
          account = store.add(a).id;
        });
  }

  private Map<String, Object> invoice(String total, String date, String number) {
    return book.saveInvoice(
        null,
        map(
            "issuedOn",
            date,
            "supplier",
            "Fornecedor Exemplo Lda",
            "number",
            number,
            "total",
            total,
            "currency",
            "EUR",
            "type",
            "INVOICE",
            "validate",
            true));
  }

  private Map<String, Object> movement(
      String amount, String date, String description, String kind, String direction) {
    return book.saveMovement(
        null,
        map(
            "accountId",
            account.toString(),
            "bookedOn",
            date,
            "description",
            description,
            "amount",
            amount,
            "currency",
            "EUR",
            "direction",
            direction,
            "kind",
            kind,
            "validate",
            true));
  }

  private Map<String, Object> allocate(
      Map<String, Object> invoice, Map<String, Object> movement, String amount) {
    return book.allocate(
        map(
            "invoiceId",
            invoice.get("id").toString(),
            "movementId",
            movement.get("id").toString(),
            "amount",
            amount));
  }

  private String closing(int year, int month) {
    return ledger.year(year).get(month - 1).get("closing").toString();
  }

  @Test
  void decemberInvoiceAndFebruaryPartialPaymentUseDifferentAnnualLedgers() {
    var invoice = invoice("100.00", "2025-12-10", "FT-2025-1");
    var payment =
        movement("60.00", "2026-02-12", "Transferência por confirmar", "UNCLASSIFIED", "DEBIT");
    allocate(invoice, payment, "60.00");
    assertThat(closing(2025, 12)).isEqualTo("100.00");
    assertThat(closing(2026, 1)).isEqualTo("0.00");
    assertThat(closing(2026, 2)).isEqualTo("-60.00");
    assertThat(book.remaining(store.get(Invoice.class, (UUID) invoice.get("id"))))
        .isEqualByComparingTo("40.00");
  }

  @Test
  void legacyThreeFourTwoProducesThreeFourSix() {
    var one = invoice("3.00", "2026-05-01", "LEG-1");
    var two = invoice("4.00", "2026-05-02", "LEG-2");
    var three = invoice("2.00", "2026-05-03", "LEG-3");
    var payment =
        movement("3.00", "2026-05-02", "Transferência por confirmar", "UNCLASSIFIED", "DEBIT");
    allocate(two, payment, "3.00");
    assertThat(closing(2026, 5)).isEqualTo("6.00");
    assertThat(ledger.year(2026).get(5).get("opening")).isEqualTo("6.00");
  }

  @Test
  void equalAmountsAndAmbiguousSuppliersNeverAutoMatch() {
    var a = invoice("25.00", "2026-04-01", "AMB-1");
    var b = invoice("25.00", "2026-04-02", "AMB-2");
    movement("25.00", "2026-04-03", "Pagamento Fornecedor Exemplo", "UNCLASSIFIED", "DEBIT");
    assertThat(store.all(Allocation.class)).isEmpty();
    assertThat(book.candidates()).hasSize(2);
    movement("25.00", "2026-04-04", "Descrição genérica", "UNCLASSIFIED", "DEBIT");
    assertThat(store.all(Allocation.class)).isEmpty();
  }

  @Test
  void explicitReferenceSupportsAutomaticPartialPayment() {
    var invoice = invoice("100.00", "2026-04-01", "FT2026/100");
    movement("60.00", "2026-08-03", "Pagamento FT2026/100", "UNCLASSIFIED", "DEBIT");
    assertThat(store.all(Allocation.class)).hasSize(1);
    assertThat(store.all(Allocation.class).getFirst().origin).isEqualTo("RULES");
    assertThat(closing(2026, 8)).isEqualTo("40.00");
  }

  @Test
  void groupedPaymentsAndMultiplePaymentsRespectAvailableAmounts() {
    var a = invoice("60.00", "2026-04-01", "GRP-1");
    var b = invoice("40.00", "2026-04-02", "GRP-2");
    var payment = movement("100.00", "2026-04-03", "Pagamento agrupado", "UNCLASSIFIED", "DEBIT");
    allocate(a, payment, "60.00");
    allocate(b, payment, "40.00");
    assertThat(closing(2026, 4)).isEqualTo("0.00");
    assertThatThrownBy(() -> allocate(a, payment, "0.01")).isInstanceOf(DomainException.class);
  }

  @Test
  void transfersHaveExplicitSignsAndCannotAlsoPayInvoices() {
    var invoice = invoice("100.00", "2026-01-10", "TRANS-1");
    var reimbursement =
        movement("100.00", "2026-01-12", "Reembolso ao gestor", "REIMBURSEMENT", "DEBIT");
    assertThat(closing(2026, 1)).isEqualTo("0.00");
    movement("15.00", "2026-02-01", "Devolução", "RETURN", "CREDIT");
    assertThat(closing(2026, 2)).isEqualTo("15.00");
    assertThatThrownBy(() -> allocate(invoice, reimbursement, "100.00"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void draftsAndDeletionReverseEffectsWithoutDeletingHistory() {
    var invoice = invoice("100.00", "2026-01-10", "DRAFT-1");
    var original = store.get(Invoice.class, (UUID) invoice.get("id"));
    var draft = new LinkedHashMap<>(book.invoiceView(original));
    draft.put("total", null);
    draft.put("validate", false);
    book.saveInvoice(original.id, draft);
    assertThat(closing(2026, 1)).isEqualTo("0.00");
    assertThat(store.all(LedgerEntry.class)).hasSize(2);
    var corrected = new LinkedHashMap<>(book.invoiceView(original));
    corrected.put("total", "120.00");
    corrected.put("validate", true);
    book.saveInvoice(original.id, corrected);
    assertThat(closing(2026, 1)).isEqualTo("120.00");
    assertThat(ledger.year(2026).getFirst().get("increases")).isEqualTo("120.00");
    assertThat(ledger.year(2026).getFirst().get("reductions")).isEqualTo("0.00");
    assertThat((List<?>) ledger.year(2026).getFirst().get("entries")).hasSize(1);
    book.delete("invoice", original.id, original.version);
    assertThat(closing(2026, 1)).isEqualTo("0.00");
    assertThat(store.all(LedgerEntry.class)).hasSize(4);
    assertThat(ledger.year(2026).getFirst().get("increases")).isEqualTo("0.00");
    assertThat((List<?>) ledger.year(2026).getFirst().get("entries")).isEmpty();
  }

  @Test
  void refundReversalAllowsAReplacementPayment() {
    var invoice = invoice("100.00", "2026-03-01", "REV-1");
    var debit = movement("100.00", "2026-03-02", "Pagamento", "UNCLASSIFIED", "DEBIT");
    var original = allocate(invoice, debit, "100.00");
    var credit = movement("40.00", "2026-03-03", "Estorno", "REFUND", "CREDIT");
    book.allocate(
        map(
            "invoiceId",
            invoice.get("id").toString(),
            "movementId",
            credit.get("id").toString(),
            "amount",
            "40.00",
            "reversesId",
            original.get("id").toString()));
    assertThat(closing(2026, 3)).isEqualTo("40.00");
    var replacement =
        movement("40.00", "2026-03-04", "Pagamento corrigido", "UNCLASSIFIED", "DEBIT");
    allocate(invoice, replacement, "40.00");
    assertThat(closing(2026, 3)).isEqualTo("0.00");
  }

  @Test
  void staleVersionAndWrongYearWindowAreRejected() {
    var invoice = invoice("30.00", "2025-12-01", "WINDOW-1");
    var payment = movement("30.00", "2026-04-01", "Transferência", "UNCLASSIFIED", "DEBIT");
    assertThatThrownBy(() -> allocate(invoice, payment, "30.00"))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("período");
    var edit = new LinkedHashMap<>(invoice);
    edit.put("version", 999);
    edit.put("validate", true);
    assertThatThrownBy(() -> book.saveInvoice((UUID) invoice.get("id"), edit))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("alterado");
  }

  @Test
  void apiRequiresSessionAndCsrf() throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String base = "http://127.0.0.1:" + port;
    for (String path : List.of("/jobs/page", "/jobs/" + UUID.randomUUID(), "/events", "/reconciliation/entries")) {
      assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1" + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
    assertThat(
            client
                .send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/invoices")).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(401);
    var csrf =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String token = Json.read(csrf.body()).path("token").asText();
    var login =
        HttpRequest.newBuilder(URI.create(base + "/api/v1/session"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-XSRF-TOKEN", token)
            .POST(
                HttpRequest.BodyPublishers.ofString("username=gestor&password=test-password-long"))
            .build();
    assertThat(client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(200);
    String nextToken = Json.read(client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/csrf")).GET().build(),
        HttpResponse.BodyHandlers.ofString()).body()).path("token").asText();
    for (String invalid : List.of("{}", "{\"excluded\":true,\"items\":[]}",
        "{\"excluded\":true,\"items\":[{\"type\":\"other\",\"id\":\"" + UUID.randomUUID() + "\",\"version\":0}]}")) {
      assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/reconciliation/exclusions"))
          .header("Content-Type", "application/json").header("X-XSRF-TOKEN", nextToken)
          .POST(HttpRequest.BodyPublishers.ofString(invalid)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }
    assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/reconciliation/exclusions"))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    var page = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/jobs/page")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(Json.read(page.body()).path("size").asInt()).isEqualTo(50);
    assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/jobs/" + UUID.randomUUID())).GET().build(),
        HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    var stream = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/events")).GET().build(),
        HttpResponse.BodyHandlers.ofInputStream());
    assertThat(stream.statusCode()).isEqualTo(200);
    try (var lines = new BufferedReader(new InputStreamReader(stream.body()))) {
      assertThat(lines.readLine()).isEqualTo("event:progress");
      String data = lines.readLine();
      assertThat(data).startsWith("data:");
      assertThat(Json.read(data.substring(5)).path("jobStates").has("WAITING")).isTrue();
    }
    assertThat(
            client
                .send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/invoices")).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(200);
    assertThat(
            client
                .send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/invoices"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(403);
    client.close();
  }

  @Test
  void processingExplainsTwoMovementsAndOneDocumentWithoutFinancialFilters() {
    jdbc.update("delete from job");
    var first = movement("138.21", "2016-01-11", "FIDELIDADE COMPANHI", "UNCLASSIFIED", "DEBIT");
    movement("100.00", "2016-01-29", "Contabilidade Janeiro", "UNCLASSIFIED", "DEBIT");
    var doc = new SourceDocument();
    doc.filename = "ExtratoMensalJan.pdf";
    doc.kind = "STATEMENT";
    store.add(doc);
    store.flush();
    queue.enqueue("REVIEW_DOCUMENT", doc.id, doc.version, "{}");
    jdbc.update("update job set state='WAITING',error='Ollama indisponível'");
    var page = jobMonitor.page("active", 0, 50);
    assertThat(page.get("total")).isEqualTo(5L);
    assertThat((Map<String, Object>) page.get("counts")).containsEntry("WAITING", 5L);
    var items = (List<?>) page.get("items");
    assertThat(items).hasSize(5);
    assertThat(items.stream().map(item -> ((Map<?, ?>) item).get("kind").toString()).toList())
        .containsExactlyInAnyOrder("REVIEW_MOVEMENT", "REVIEW_MOVEMENT", "REVIEW_DOCUMENT", "CLASSIFY_MOVEMENT", "CLASSIFY_MOVEMENT");
    var jobId = jdbc.queryForObject("select id from job where target_id=? and kind='REVIEW_MOVEMENT'", UUID.class, first.get("id"));
    var detail = jobMonitor.detail(jobId);
    assertThat((Map<String, Object>) detail.get("target")).containsEntry("label", "FIDELIDADE COMPANHI");
    assertThat(detail.get("attempts")).isEqualTo(0);
    assertThat(progress.snapshot().get("jobs")).isEqualTo(5L);
    jdbc.update("update job set state='DONE',error=null,updated_at=now() where id=?", jobId);
    assertThat(jobMonitor.page("active", 0, 50).get("total")).isEqualTo(4L);
    assertThat(jobMonitor.detail(jobId).get("state")).isEqualTo("DONE");
    assertThat(book.movementView(store.get(pt.conta.bank.BankMovement.class, (UUID) first.get("id"))).get("kind"))
        .hasToString("UNCLASSIFIED");
  }

  @Test
  void processingPagesFiltersAndHandlesMissingOrDeletedRecords() {
    jdbc.update("delete from job");
    assertThat(jobMonitor.page("active", 0, 50).get("total")).isEqualTo(0L);
    jdbc.update("""
        insert into job(id,version,created_at,updated_at,kind,target_id,target_version,state,attempts,available_at)
        select gen_random_uuid(),0,now(),now(),'EXTRACT',gen_random_uuid(),0,'DONE',0,now()
        from generate_series(1,55)
        """);
    for (String state : List.of("QUEUED", "RUNNING", "WAITING", "FAILED")) {
      UUID target = UUID.randomUUID();
      queue.enqueue("REVIEW_MOVEMENT", target, 0, "{}");
      jdbc.update("update job set state=? where target_id=?", state, target);
      assertThat(jobMonitor.page(state, 0, 50).get("total")).isEqualTo(1L);
    }
    assertThat(jobMonitor.page("active", 0, 50).get("total")).isEqualTo(3L);
    assertThat(jobMonitor.page("all", 0, 50).get("total")).isEqualTo(59L);
    var firstPage = (List<Object>) jobMonitor.page("all", 0, 50).get("items");
    var secondPage = (List<Object>) jobMonitor.page("all", 1, 50).get("items");
    assertThat(firstPage).hasSize(50);
    assertThat(secondPage).hasSize(9).doesNotContainAnyElementsOf(firstPage);
    assertThat((List<?>) jobMonitor.page("all", 2, 50).get("items")).isEmpty();
    UUID orphan = jdbc.queryForObject("select id from job where state='WAITING'", UUID.class);
    assertThat((Map<String, Object>) jobMonitor.detail(orphan).get("target")).containsEntry("available", false);
    var m = movement("10.00", "2016-01-01", "Eliminado", "UNCLASSIFIED", "DEBIT");
    store.get(pt.conta.bank.BankMovement.class, (UUID) m.get("id")).deleted = true;
    store.flush();
    UUID deleted = jdbc.queryForObject("select id from job where target_id=? and kind='REVIEW_MOVEMENT'", UUID.class, m.get("id"));
    assertThat((Map<String, Object>) jobMonitor.detail(deleted).get("target"))
        .containsEntry("available", false).containsEntry("label", "Eliminado");
    assertThatThrownBy(() -> jobMonitor.page("unexpected", 0, 50)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> jobMonitor.page("all", -1, 50)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> jobMonitor.page("all", 0, 101)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> jobMonitor.detail(UUID.randomUUID())).isInstanceOf(DomainException.class);
  }

  @Test
  void processingResolvesInvoicesAllocationsAndAlerts() {
    jdbc.update("delete from job");
    var i = invoice("25.00", "2016-02-01", "MONITOR-25");
    var m = movement("25.00", "2016-02-02", "Pagamento monitorizado", "UNCLASSIFIED", "DEBIT");
    var allocation = new Allocation();
    allocation.invoiceId = (UUID) i.get("id");
    allocation.movementId = (UUID) m.get("id");
    allocation.amount = new java.math.BigDecimal("25.00");
    allocation.evidence = "Associação confirmada";
    store.add(allocation);
    var alert = new pt.conta.review.Alert();
    alert.message = "Confirmar documento";
    store.add(alert);
    store.flush();
    queue.enqueue("REVIEW_ALLOCATION", allocation.id, allocation.version, "{}");
    queue.enqueue("REPAIR_ALERT", alert.id, alert.version, "{}");
    UUID allocationJob = jdbc.queryForObject("select id from job where target_id=?", UUID.class, allocation.id);
    var target = (Map<String, Object>) jobMonitor.detail(allocationJob).get("target");
    assertThat(target).containsEntry("type", "allocation").containsEntry("available", true)
        .containsEntry("invoiceId", i.get("id")).containsEntry("movementId", m.get("id"))
        .containsEntry("amount", "25.00").containsEntry("evidence", "Associação confirmada");
    var items = (List<Map<String, Object>>) jobMonitor.page("all", 0, 50).get("items");
    assertThat(items.stream().map(item -> ((Map<?, ?>) item.get("target")).get("type").toString()).toList())
        .contains("invoice", "movement", "allocation", "alert");
  }

  @Test
  void nativePdfIsArchivedDeduplicatedAndPublishedWithExactValues() throws Exception {
    byte[] bytes =
        pdf(
            "Fornecedor: Exemplo Documento",
            "Data: 11/11/2026",
            "Fatura: FT2026/999",
            "Total: 123,45");
    var doc =
        documents.upload(
            new ByteArrayInputStream(bytes),
            "20261111_Transportes.pdf",
            "INVOICE",
            null,
            null,
            null);
    var again =
        documents.upload(
            new ByteArrayInputStream(bytes), "outro-nome.pdf", "INVOICE", null, null, null);
    assertThat(again.get("id")).isEqualTo(doc.get("id"));
    processing.extract(
        new JobQueue.Claim(UUID.randomUUID(), "EXTRACT", (UUID) doc.get("id"), 0, "{}", 0));
    var invoice = store.all(Invoice.class).getFirst();
    assertThat(invoice.total).isEqualByComparingTo("123.45");
    assertThat(invoice.issuedOn).isEqualTo(LocalDate.of(2026, 11, 11));
    assertThat(invoice.reviewStatus).isEqualTo(pt.conta.shared.Types.ReviewStatus.AUTOMATIC);
    assertThat(closing(2026, 11)).isEqualTo("123.45");
    assertThat(
            java.nio.file.Files.readAllBytes(
                storage.path(store.get(SourceDocument.class, (UUID) doc.get("id")).storageKey)))
        .containsExactly(bytes);
    assertThat(documents.view(store.get(SourceDocument.class, (UUID) doc.get("id"))).get("draft"))
        .isInstanceOf(Map.class);
  }

  @Test
  void incompleteArticlesPreserveKnownHeaderButExcludeTheInvoice() throws Exception {
    var doc =
        documents.upload(
            new ByteArrayInputStream(
                pdf(
                    "Fornecedor: Exemplo Documento",
                    "Data: 11/11/2026",
                    "Fatura: FT2026/998",
                    "Descricao Quantidade Artigos",
                    "Total: 123,45")),
            "fatura.pdf",
            "INVOICE",
            null,
            null,
            null);
    processing.extract(
        new JobQueue.Claim(UUID.randomUUID(), "EXTRACT", (UUID) doc.get("id"), 0, "{}", 0));
    var invoice = store.all(Invoice.class).getFirst();
    assertThat(invoice.total).isEqualByComparingTo("123.45");
    assertThat(invoice.reviewStatus).isEqualTo(pt.conta.shared.Types.ReviewStatus.OPEN);
    assertThat(closing(2026, 11)).isEqualTo("0.00");
  }

  @Test
  void durableQueueIsIdempotentAndOfflineDoesNotConsumeRetries() {
    UUID target = UUID.randomUUID();
    queue.enqueue("TEST", target, 0, "{}");
    queue.enqueue("TEST", target, 0, "{}");
    assertThat(store.all(Job.class)).hasSize(1);
    var first = queue.claim().orElseThrow();
    queue.finish(first, "Modelo offline", true);
    assertThat(
            jdbc.queryForObject("select attempts from job where id=?", Integer.class, first.id()))
        .isZero();
    assertThat(jdbc.queryForObject("select state from job where id=?", String.class, first.id()))
        .isEqualTo("WAITING");
    queue.retry(first.id());
    var next = queue.claim().orElseThrow();
    queue.finish(next, null, false);
    assertThat(jdbc.queryForObject("select state from job where id=?", String.class, first.id()))
        .isEqualTo("DONE");
  }

  @Test
  void aReclaimedJobCannotBeCompletedByItsPreviousOwner() {
    queue.enqueue("TEST", UUID.randomUUID(), 0, "{}");
    var first = queue.claim().orElseThrow();
    jdbc.update("update job set leased_until=now()-interval '1 second' where id=?", first.id());
    var second = queue.claim().orElseThrow();
    assertThat(second.token()).isNotEqualTo(first.token());
    queue.finish(first, null, false);
    assertThat(jdbc.queryForObject("select state from job where id=?", String.class, first.id()))
        .isEqualTo("RUNNING");
    queue.finish(second, null, false);
    assertThat(jdbc.queryForObject("select state from job where id=?", String.class, first.id()))
        .isEqualTo("DONE");
  }

  @Test
  void creditNoteAndSupplierRefundHaveOppositeEffects() {
    var original = invoice("100.00", "2026-01-01", "CREDIT-BASE");
    var payment = movement("100.00", "2026-01-02", "Pagamento", "UNCLASSIFIED", "DEBIT");
    allocate(original, payment, "100.00");
    var note =
        book.saveInvoice(
            null,
            map(
                "issuedOn",
                "2026-01-03",
                "supplier",
                "Fornecedor Exemplo Lda",
                "number",
                "NC-2026-1",
                "total",
                "20.00",
                "currency",
                "EUR",
                "type",
                "CREDIT_NOTE",
                "relatedInvoiceId",
                original.get("id").toString(),
                "validate",
                true));
    assertThat(closing(2026, 1)).isEqualTo("-20.00");
    allocate(
        note, movement("20.00", "2026-01-04", "Reembolso fornecedor", "REFUND", "CREDIT"), "20.00");
    assertThat(closing(2026, 1)).isEqualTo("0.00");
  }

  @Test
  void statementIgnoresMissingOrInvalidBankBalancesWhenValidatingAndEditingMovements()
      throws Exception {
    var doc =
        documents.upload(
            new ByteArrayInputStream(pdf("Extrato de teste")),
            "extrato.pdf",
            "STATEMENT",
            account,
            null,
            null);
    var draft =
        map(
            "openingBalance",
            "não interessa",
            "closingBalance",
            "saldo irrelevante",
            "movements",
            List.of(
                map(
                    "bookedOn",
                    "2026-01-02",
                    "description",
                    "Pagamento de teste",
                    "amount",
                    "60.00",
                    "direction",
                    "DEBIT",
                    "currency",
                    "EUR")),
            "validate",
            true);
    publisher.extracted(
        (UUID) doc.get("id"),
        0,
        new DocumentConverter.Converted("Extrato", 1, map("pages", List.of()), "TEST"),
        draft);
    var m = store.all(pt.conta.bank.BankMovement.class).getFirst();
    assertThat(m.reviewStatus).isEqualTo(pt.conta.shared.Types.ReviewStatus.AUTOMATIC);
    assertThat(m.amount).isEqualByComparingTo("60.00");
    assertThat(closing(2026, 1)).isEqualTo("-60.00");
    var statement = store.get(pt.conta.bank.BankStatement.class, m.statementId);
    statement.openingBalance = new java.math.BigDecimal("100.00");
    statement.closingBalance = new java.math.BigDecimal("30.00");
    var edit = new LinkedHashMap<>(book.movementView(m));
    edit.put("amount", "70.00");
    edit.put("validate", true);
    book.saveMovement(m.id, edit);
    assertThat(m.amount).isEqualByComparingTo("70.00");
    assertThat(closing(2026, 1)).isEqualTo("-70.00");
    assertThat(m.reviewStatus).isEqualTo(pt.conta.shared.Types.ReviewStatus.EDITED);
  }

  @Test
  void quotedAiConcernCannotOverrideAnOwnerDecisionOrAStaleRevision() {
    var invoice = invoice("100.00", "2026-01-01", "AI-GUARD");
    var i = store.get(Invoice.class, (UUID) invoice.get("id"));
    var result =
        map(
            "verdict",
            "CONCERN",
            "summary",
            "Total diferente",
            "concerns",
            List.of(map("field", "total", "quote", "Total: 120,00", "reason", "Verificar total")));
    aiResults.reviewed(
        new JobQueue.Claim(UUID.randomUUID(), "REVIEW_INVOICE", i.id, i.version, "{}", 0),
        result,
        "Total: 120,00");
    assertThat(closing(2026, 1)).isEqualTo("100.00");
    assertThat(i.manuallyConfirmed).isTrue();
    var input = new LinkedHashMap<>(book.invoiceView(i));
    input.put("total", "120.00");
    input.put("validate", true);
    long old = i.version;
    book.saveInvoice(i.id, input);
    aiResults.reviewed(
        new JobQueue.Claim(UUID.randomUUID(), "REVIEW_INVOICE", i.id, old, "{}", 0),
        result,
        "Total: 120,00");
    assertThat(closing(2026, 1)).isEqualTo("120.00");
  }

  @Test
  void postgresSearchFindsInvoiceAndBankDescription() {
    invoice("5.00", "2026-01-01", "SEARCH-LISBOA");
    movement("5.00", "2026-01-02", "Compra em Lisboa", "UNCLASSIFIED", "DEBIT");
    assertThat(finance.invoices(Map.of("q", "LISBOA")).get("total")).isEqualTo(1);
    assertThat(finance.movements(Map.of("q", "Lisboa")).get("total")).isEqualTo(1);
  }

  @Test
  void laterInvoiceWithdrawsAnEarlierWeakAutomaticMatch() {
    var first = invoice("100.00", "2026-01-01", "LATE-1");
    var payment =
        movement("100.00", "2026-02-01", "Pagamento Fornecedor Exemplo", "UNCLASSIFIED", "DEBIT");
    assertThat(store.all(Allocation.class).stream().filter(a -> a.active)).hasSize(1);
    var second = invoice("100.00", "2026-03-01", "LATE-2");
    assertThat(store.all(Allocation.class).stream().filter(a -> a.active)).isEmpty();
    assertThat(closing(2026, 3)).isEqualTo("100.00");
    allocate(second, payment, "100.00");
    assertThat(
            store.all(pt.conta.review.Alert.class).stream()
                .filter(a -> a.code.equals("AMBIGUOUS_AUTO_MATCH") && a.status.equals("OPEN")))
        .isEmpty();
    invoice("100.00", "2026-04-01", "LATE-3");
    assertThat(store.all(Allocation.class).stream().filter(a -> a.active))
        .allMatch(a -> a.origin.equals("MANUAL"));
  }

  @Test
  void explicitReferenceRemainsValidWhenAnUnreferencedInvoiceArrives() {
    invoice("100.00", "2026-01-01", "EXPLICIT-1");
    movement("100.00", "2026-02-01", "Fornecedor Exemplo EXPLICIT-1", "UNCLASSIFIED", "DEBIT");
    invoice("100.00", "2026-03-01", "EXPLICIT-2");
    assertThat(store.all(Allocation.class).stream().filter(a -> a.active)).hasSize(1);
  }

  @Test
  void referencedGroupCanBeAllocatedAutomaticallyAsOneExactBatch() {
    invoice("40.00", "2026-01-01", "GROUP-1001");
    invoice("60.00", "2026-01-02", "GROUP-1002");
    movement(
        "100.00",
        "2026-01-03",
        "Fornecedor Exemplo GROUP-1001 GROUP-1002",
        "UNCLASSIFIED",
        "DEBIT");
    assertThat(store.all(Allocation.class).stream().filter(a -> a.active)).hasSize(2);
    assertThat(closing(2026, 1)).isEqualTo("0.00");
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentAllocationsCannotSpendTheSameCents() throws Exception {
    var invoice = invoice("100.00", "2098-01-01", "CONCURRENT-1");
    var payment =
        movement("100.00", "2098-01-02", "Pagamento sem referência", "UNCLASSIFIED", "DEBIT");
    var start = new CountDownLatch(1);
    var successes = new java.util.concurrent.atomic.AtomicInteger();
    var rejected = new java.util.concurrent.atomic.AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<?>>();
      for (int n = 0; n < 2; n++)
        futures.add(
            executor.submit(
                () -> {
                  try {
                    start.await();
                    allocate(invoice, payment, "70.00");
                    successes.incrementAndGet();
                  } catch (DomainException e) {
                    rejected.incrementAndGet();
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }));
      start.countDown();
      for (var future : futures) future.get(15, TimeUnit.SECONDS);
      assertThat(successes.get()).isEqualTo(1);
      assertThat(rejected.get()).isEqualTo(1);
    } finally {
      jdbc.execute(
          "truncate"
              + " invoice,invoice_line,invoice_category,bank_account,bank_movement,allocation,ledger_entry,audit_event,job,alert"
              + " cascade");
    }
  }

  private byte[] pdf(String... lines) throws Exception {
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      pdf.addPage(page);
      try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
        content.beginText();
        content.setFont(
            new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
            12);
        content.newLineAtOffset(40, 750);
        for (String line : lines) {
          content.showText(line);
          content.newLineAtOffset(0, -20);
        }
        content.endText();
      }
      var output = new ByteArrayOutputStream();
      pdf.save(output);
      return output.toByteArray();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void historicalExcelAndZipReconcileInEitherImportOrder(boolean excelFirst) throws Exception {
    if (excelFirst) importLegacyWorkbook();
    var archiveBytes = new ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(archiveBytes)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("2016/01/despesas/20160104_Transportes.pdf"));
      zip.write(
          pdf(
              "Fornecedor: Exemplo Documento",
              "Data: 04/01/2016",
              "Fatura: FT2016/04",
              "Total: 123,45"));
      zip.closeEntry();
      zip.putNextEntry(new java.util.zip.ZipEntry("2016/01/extrato.pdf"));
      zip.write(pdf("Extrato bancario de janeiro", "05/01/2016 Pagamento FT2016/04 -123,45"));
      zip.closeEntry();
    }
    var archive =
        documents.upload(
            new ByteArrayInputStream(archiveBytes.toByteArray()),
            "2016.zip",
            "ARCHIVE",
            account,
            null,
            null);
    zipImporter.run((UUID) archive.get("id"));
    zipImporter.run((UUID) archive.get("id"));
    assertThat(store.all(SourceDocument.class)).hasSize(excelFirst ? 4 : 3);
    var invoiceDoc =
        store.all(SourceDocument.class).stream()
            .filter(d -> d.kind.equals("INVOICE"))
            .findFirst()
            .orElseThrow();
    var statementDoc =
        store.all(SourceDocument.class).stream()
            .filter(d -> d.kind.equals("STATEMENT"))
            .findFirst()
            .orElseThrow();
    processing.extract(
        new JobQueue.Claim(
            UUID.randomUUID(), "EXTRACT", invoiceDoc.id, invoiceDoc.version, "{}", 0));
    processing.extract(
        new JobQueue.Claim(
            UUID.randomUUID(), "EXTRACT", statementDoc.id, statementDoc.version, "{}", 0));
    assertThat(closing(2016, 1)).isEqualTo("0.00");
    if (!excelFirst) importLegacyWorkbook();
    assertThat(store.all(pt.conta.history.LegacyRow.class)).hasSize(1);
    assertThat(store.all(pt.conta.history.LegacyRow.class).getFirst().status)
        .isEqualTo("AUTOMATIC");
    assertThat(closing(2016, 1)).isEqualTo("0.00");
    assertThat(
            store.all(pt.conta.review.Alert.class).stream()
                .filter(a -> a.entityType.equals("legacy") && a.status.equals("OPEN")))
        .isEmpty();
  }

  /** Builds a real legacy workbook with one fully paid invoice and its original formula. */
  private void importLegacyWorkbook() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
      var sheet = workbook.createSheet("Janeiro");
      var headers = sheet.createRow(0);
      String[] labels = {"Data", "Descrição", "Recebido", "Pagamento", "Saldo"};
      for (int n = 0; n < labels.length; n++) headers.createCell(n).setCellValue(labels[n]);
      var row = sheet.createRow(1);
      row.createCell(0).setCellValue("04/01/2016");
      row.createCell(1).setCellValue("Exemplo Documento FT2016/04");
      row.createCell(2).setCellValue(123.45);
      row.createCell(3).setCellValue(123.45);
      row.createCell(4).setCellFormula("C2-D2");
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      workbook.write(bytes);
    }
    var document =
        documents.upload(
            new ByteArrayInputStream(bytes.toByteArray()),
            "contabilidade-2016.xlsx",
            "HISTORY",
            null,
            null,
            null);
    var source = store.get(SourceDocument.class, (UUID) document.get("id"));
    processing.extract(
        new JobQueue.Claim(UUID.randomUUID(), "EXTRACT", source.id, source.version, "{}", 0));
    assertThat(
            store.all(Job.class).stream()
                .anyMatch(j -> j.targetId.equals(source.id) && j.kind.equals("REVIEW_DOCUMENT")))
        .isTrue();
  }

  @Test
  void independentReceiptsKeepSeparateIdentitiesAndSavingDraftSuspendsBoth() throws Exception {
    var original =
        documents.upload(
            new ByteArrayInputStream(pdf("Comprovativos independentes")),
            "comprovativos.pdf",
            "INVOICE",
            null,
            null,
            null);
    UUID id = (UUID) original.get("id");
    var source = store.get(SourceDocument.class, id);
    var one =
        map(
            "issuedOn",
            "2016-03-04",
            "supplier",
            "Transportes de teste",
            "total",
            "4.50",
            "currency",
            "EUR",
            "type",
            "INVOICE",
            "lines",
            List.of());
    var two =
        map(
            "issuedOn",
            "2016-03-06",
            "supplier",
            "Transportes de teste",
            "total",
            "13.50",
            "currency",
            "EUR",
            "type",
            "INVOICE",
            "lines",
            List.of());
    var draft = map("invoices", List.of(one, two));
    publisher.edit(id, map("version", source.version, "draft", draft, "resolve", true), documents);
    assertThat(store.all(Invoice.class)).hasSize(2);
    var ids = store.all(Invoice.class).stream().map(i -> i.id).toList();
    assertThat(closing(2016, 3)).isEqualTo("18.00");
    publisher.edit(id, map("version", source.version, "draft", draft, "resolve", true), documents);
    assertThat(store.all(Invoice.class).stream().map(i -> i.id)).containsExactlyElementsOf(ids);
    assertThat(closing(2016, 3)).isEqualTo("18.00");
    publisher.edit(id, map("version", source.version, "draft", draft, "resolve", false), documents);
    assertThat(closing(2016, 3)).isEqualTo("0.00");
  }

  @Test
  void disablingAiKeepsReviewQueuedWhileDocumentProcessingCanRun() {
    queue.enqueue("REVIEW_DOCUMENT", UUID.randomUUID(), 0, "{}");
    queue.enqueue("EXTRACT", UUID.randomUUID(), 0, "{}");
    var rulesQueue = new JobQueue(jdbc, false);
    var claim = rulesQueue.claim().orElseThrow();
    assertThat(claim.kind()).isEqualTo("EXTRACT");
    rulesQueue.finish(claim, null, false);
    assertThat(rulesQueue.claim()).isEmpty();
    assertThat(
            jdbc.queryForObject("select state from job where kind='REVIEW_DOCUMENT'", String.class))
        .isEqualTo("QUEUED");
  }

  @Test
  void anUnknownAiFieldNeverErasesAnAlreadyReadInvoiceField() throws Exception {
    var upload =
        documents.upload(
            new ByteArrayInputStream(pdf("Fatura: FT-KEEP-1", "Total: 37,82")),
            "preservar.pdf",
            "INVOICE",
            null,
            null,
            null);
    var source = store.get(SourceDocument.class, (UUID) upload.get("id"));
    processing.extract(
        new JobQueue.Claim(UUID.randomUUID(), "EXTRACT", source.id, source.version, "{}", 0));
    publisher.aiExtracted(
        source.id,
        source.version,
        map(
            "total",
            null,
            "number",
            null,
            "supplier",
            null,
            "issuedOn",
            null,
            "currency",
            "EUR",
            "type",
            "INVOICE",
            "lines",
            List.of(),
            "validate",
            false),
        "[]");
    assertThat(Json.object(source.draft))
        .containsEntry("total", "37.82")
        .containsEntry("number", "FT-KEEP-1");
    assertThat(store.all(Invoice.class).getFirst().total).isEqualByComparingTo("37.82");
  }

  @Test
  void bankBalanceConcernsCannotSuspendValidMovementFacts() {
    var data =
        map(
            "accountId",
            account.toString(),
            "bookedOn",
            "2026-01-02",
            "description",
            "Compra de teste",
            "amount",
            "7.00",
            "direction",
            "DEBIT",
            "validate",
            true);
    var view = book.saveMovement(null, data, "RULES", false);
    var m = store.get(pt.conta.bank.BankMovement.class, (UUID) view.get("id"));
    var claim = new JobQueue.Claim(UUID.randomUUID(), "REVIEW_MOVEMENT", m.id, m.version, "{}", 0);
    aiResults.reviewed(
        claim,
        map(
            "verdict",
            "CONCERN",
            "summary",
            "Saldo divergente",
            "concerns",
            List.of(map("field", "openingBalance", "quote", "Saldo inicial 999,00"))),
        "Saldo inicial 999,00");
    assertThat(m.reviewStatus).isEqualTo(pt.conta.shared.Types.ReviewStatus.AUTOMATIC);
    assertThat(
            store.all(pt.conta.review.Alert.class).stream()
                .filter(a -> a.code.equals("AI_CONCERN")))
        .isEmpty();
    aiResults.reviewed(
        claim,
        map(
            "verdict",
            "CONCERN",
            "summary",
            "Montante divergente",
            "concerns",
            List.of(map("field", "amount", "quote", "Montante -8,00 EUR"))),
        "Montante -8,00 EUR");
    assertThat(m.reviewStatus).isEqualTo(pt.conta.shared.Types.ReviewStatus.OPEN);
  }

  private ExclusionRequest.Item selection(String type, Map<String, Object> row) {
    UUID id = (UUID) row.get("id");
    var entity = type.equals("invoice") ? store.get(Invoice.class, id) : store.get(BankMovement.class, id);
    return new ExclusionRequest.Item(type, id, entity.version);
  }

  private void participation(boolean excluded, ExclusionRequest.Item... items) {
    book.exclude(new ExclusionRequest(excluded, List.of(items), null));
  }

  private ReconciliationService.Page entries() {
    return finance.entries(Map.of("year", "2026"));
  }

  @Test
  void everyUnmatchedSourceAppearsAndContributesItsFullSignedValue() {
    invoice("100.00", "2026-01-01", "ISOLATED-INVOICE");
    movement("60.00", "2026-01-02", "Débito sem referência", "OTHER", "DEBIT");
    movement("25.00", "2026-01-03", "Crédito sem referência", "UNCLASSIFIED", "CREDIT");
    assertThat(closing(2026, 1)).isEqualTo("65.00");
    assertThat(entries().items()).hasSize(3);
    assertThat(entries().items()).extracting(ReconciliationService.Entry::balanceImpact)
        .containsExactly("100.00", "-60.00", "25.00");
    assertThat(entries().items()).extracting(ReconciliationService.Entry::status)
        .containsExactly("UNMATCHED_INVOICE", "UNMATCHED_MOVEMENT", "UNMATCHED_MOVEMENT");
  }

  @ParameterizedTest
  @CsvSource({"60.00,40.00,true", "100.00,0.00,true", "120.00,-20.00,true",
      "60.00,40.00,false", "100.00,0.00,false", "120.00,-20.00,false"})
  void referencedPairsMatchInEitherArrivalOrderIncludingOverpayments(
      String amount, String expected, boolean invoiceFirst) {
    if (invoiceFirst) invoice("100.00", "2026-01-01", "REFERENCE-1001");
    movement(amount, "2026-01-02", "Pagamento REFERENCE-1001", "UNCLASSIFIED", "DEBIT");
    if (!invoiceFirst) invoice("100.00", "2026-01-01", "REFERENCE-1001");
    var page = entries();
    assertThat(page.items()).hasSize(1);
    var row = page.items().getFirst();
    assertThat(row.invoices()).hasSize(1);
    assertThat(row.movements()).hasSize(1);
    assertThat(row.allocations()).hasSize(1);
    assertThat(row.balanceImpact()).isEqualTo(expected);
    assertThat(row.differences().dates()).isTrue();
    assertThat(row.differences().amounts()).isEqualTo(!amount.equals("100.00"));
    assertThat(closing(2026, 1)).isEqualTo(expected);
    int postings = store.all(LedgerEntry.class).size();
    finance.reconcile();
    finance.reconcile();
    assertThat(store.all(LedgerEntry.class)).hasSize(postings);
    assertThat(entries().items().getFirst().id()).isEqualTo(row.id());
    book.unallocate((UUID) row.allocations().getFirst().get("id"));
    assertThat(entries().items()).hasSize(2);
    assertThat(closing(2026, 1)).isEqualTo(expected);
  }

  @Test
  void exactPairHasNoDifferencesAndGroupedSourcesAreNeverDuplicated() {
    var a = invoice("60.00", "2026-01-01", "PAIR-A");
    var b = invoice("40.00", "2026-01-01", "PAIR-B");
    var m = movement("100.00", "2026-01-01", "Pagamento agrupado", "UNCLASSIFIED", "DEBIT");
    allocate(a, m, "60.00");
    allocate(b, m, "40.00");
    var group = entries().items().getFirst();
    assertThat(entries().total()).isEqualTo(1);
    assertThat(group.invoices()).hasSize(2);
    assertThat(group.movements()).hasSize(1);
    assertThat(group.status()).isEqualTo("MATCHED");
    assertThat(group.differences()).isEqualTo(new ReconciliationService.Differences(false, false));
    assertThat(group.balanceImpact()).isEqualTo("0.00");
  }

  @Test
  void filteringAndPagingKeepTheWholeCrossYearGroupAndItsPeriodEffect() {
    var i = invoice("100.00", "2025-12-10", "DATES-1001");
    var m = movement("60.00", "2026-02-12", "Pagamento DATES-1001", "UNCLASSIFIED", "DEBIT");
    invoice("7.00", "2026-02-13", "UNRELATED-1001");
    var february = finance.entries(Map.of("year", "2026", "month", "2", "size", "1"));
    assertThat(february.total()).isEqualTo(2);
    var row = february.items().getFirst();
    assertThat(row.invoices().getFirst().get("id")).isEqualTo(i.get("id"));
    assertThat(row.movements().getFirst().get("id")).isEqualTo(m.get("id"));
    assertThat(row.balanceImpact()).isEqualTo("40.00");
    assertThat(row.periodImpact()).isEqualTo("-60.00");
    assertThat(row.spansPeriods()).isTrue();
    var december = finance.entries(Map.of("year", "2025", "month", "12")).items().getFirst();
    assertThat(december.id()).isEqualTo(row.id());
    assertThat(december.periodImpact()).isEqualTo("100.00");
    assertThat(finance.entries(Map.of("year", "2026", "month", "2", "page", "1", "size", "1")).items().getFirst().id()).isNotEqualTo(row.id());
    assertThat(finance.entries(Map.of("q", "DATES-1001")).items()).hasSize(1);
    assertThat(finance.entries(Map.of("year", "2026", "month", "1")).items()).isEmpty();
    assertThat(closing(2025, 12)).isEqualTo("100.00");
    assertThat(closing(2026, 2)).isEqualTo("-53.00");
  }

  @ParameterizedTest
  @ValueSource(strings = {"invoice", "movement", "both"})
  void excludingEitherSideOrBothDetachesAndReincludingUsesCurrentEvidence(String side) {
    var i = invoice("100.00", "2026-01-01", "EXCLUDE-1001");
    var m = movement("100.00", "2026-01-02", "Pagamento EXCLUDE-1001", "UNCLASSIFIED", "DEBIT");
    var selected = new ArrayList<ExclusionRequest.Item>();
    if (!side.equals("movement")) selected.add(selection("invoice", i));
    if (!side.equals("invoice")) selected.add(selection("movement", m));
    book.exclude(new ExclusionRequest(true, selected, "Desconsiderado pelo gestor"));
    assertThat(entries().items()).hasSize(2);
    assertThat(entries().items().stream().filter(ReconciliationService.Entry::excluded)).hasSize(selected.size());
    assertThat(entries().items().stream().filter(ReconciliationService.Entry::excluded))
        .allSatisfy(row -> assertThat(row.balanceImpact()).isEqualTo("0.00"));
    assertThat(closing(2026, 1)).isEqualTo(side.equals("both") ? "0.00" : side.equals("invoice") ? "-100.00" : "100.00");
    assertThat(store.all(Allocation.class).stream().filter(a -> a.active)).isEmpty();
    assertThat(book.candidates()).isEmpty();
    assertThat(store.all(AppSetting.class).stream().filter(s -> s.name.startsWith("rejected:"))).isEmpty();
    assertThat(store.all(pt.conta.review.Alert.class).stream().filter(a -> a.status.equals("OPEN") && a.entityType.equals("allocation"))).isEmpty();
    participation(false, selection("invoice", i), selection("movement", m));
    assertThat(entries().items()).hasSize(1);
    assertThat(entries().items().getFirst().excluded()).isFalse();
    assertThat(closing(2026, 1)).isEqualTo("0.00");
    assertThat(store.all(Allocation.class)).hasSize(2);
  }

  @Test
  void excludedInvoiceReleasesMovementToAnotherInvoiceImmediately() {
    var first = invoice("100.00", "2026-01-01", "PRIMARY-1001");
    movement("100.00", "2026-01-02", "Fornecedor Exemplo PRIMARY-1001", "UNCLASSIFIED", "DEBIT");
    var next = invoice("100.00", "2026-01-03", "SECONDARY-1001");
    participation(true, selection("invoice", first));
    var active = store.all(Allocation.class).stream().filter(a -> a.active).toList();
    assertThat(active).hasSize(1);
    assertThat(active.getFirst().invoiceId).isEqualTo(next.get("id"));
    assertThat(closing(2026, 1)).isEqualTo("0.00");
    assertThat(finance.summary(2026, 1).get("outstanding")).isEqualTo("0.00");
  }

  @Test
  void partialGroupExclusionKeepsUnaffectedManualAllocations() {
    var a = invoice("60.00", "2026-01-01", "GROUP-EX-A");
    var b = invoice("40.00", "2026-01-02", "GROUP-EX-B");
    var m = movement("100.00", "2026-01-03", "Pagamento genérico", "UNCLASSIFIED", "DEBIT");
    allocate(a, m, "60.00");
    var kept = allocate(b, m, "40.00");
    participation(true, selection("invoice", a));
    assertThat(store.get(Allocation.class, (UUID) kept.get("id")).active).isTrue();
    assertThat(entries().items()).hasSize(2);
    assertThat(entries().items().stream().filter(row -> !row.excluded()).findFirst().orElseThrow().balanceImpact()).isEqualTo("-60.00");
    assertThat(closing(2026, 1)).isEqualTo("-60.00");
  }

  @Test
  void excludingOriginalPaymentDetachesItsReversalButCreditStillCounts() {
    var i = invoice("100.00", "2026-01-01", "REV-EX-1001");
    var debit = movement("100.00", "2026-01-02", "Débito", "UNCLASSIFIED", "DEBIT");
    var original = allocate(i, debit, "100.00");
    var credit = movement("40.00", "2026-01-03", "Estorno", "REFUND", "CREDIT");
    book.allocate(map("invoiceId", i.get("id").toString(), "movementId", credit.get("id").toString(),
        "amount", "40.00", "reversesId", original.get("id").toString()));
    participation(true, selection("movement", debit));
    assertThat(store.all(Allocation.class).stream().filter(a -> a.active)).isEmpty();
    assertThat(closing(2026, 1)).isEqualTo("140.00");
    assertThat(entries().items()).hasSize(3);
  }

  @Test
  void editsAndPublicationPreserveExclusionAndDraftReinclusionDoesNotValidate() {
    var i = invoice("100.00", "2026-01-01", "PRESERVE-EX");
    participation(true, selection("invoice", i));
    var entity = store.get(Invoice.class, (UUID) i.get("id"));
    var edit = new LinkedHashMap<>(book.invoiceView(entity));
    edit.put("excluded", false); // Only the dedicated versioned action can remove the marker.
    edit.put("total", "120.00");
    edit.put("validate", true);
    book.saveInvoice(entity.id, edit, "RULES", false);
    assertThat(entity.excluded).isTrue();
    assertThat(closing(2026, 1)).isEqualTo("0.00");
    edit = new LinkedHashMap<>(book.invoiceView(entity));
    edit.put("validate", false);
    book.saveInvoice(entity.id, edit);
    participation(false, selection("invoice", i));
    assertThat(entity.reviewStatus).isEqualTo(pt.conta.shared.Types.ReviewStatus.OPEN);
    assertThat(entries().items().getFirst().status()).isEqualTo("PENDING_REVIEW");
    assertThat(entries().items().getFirst().balanceImpact()).isNull();
    assertThat(closing(2026, 1)).isEqualTo("0.00");
  }

  @Test
  void staleSelectionRejectsTheEntireExclusionBeforeChangingAnyRecord() {
    var i = invoice("100.00", "2026-01-01", "STALE-EX");
    var m = movement("100.00", "2026-01-02", "Pagamento STALE-EX", "UNCLASSIFIED", "DEBIT");
    var wrong = new ExclusionRequest.Item("movement", (UUID) m.get("id"), 999L);
    assertThatThrownBy(() -> participation(true, selection("invoice", i), wrong)).isInstanceOf(DomainException.class);
    assertThat(store.get(Invoice.class, (UUID) i.get("id")).excluded).isFalse();
    assertThat(store.get(BankMovement.class, (UUID) m.get("id")).excluded).isFalse();
    assertThat(store.all(Allocation.class).stream().filter(a -> a.active)).hasSize(1);
  }

  @Test
  void statementRepublicationKeepsTheExcludedMovementAndItsSource() throws Exception {
    var doc = documents.upload(new ByteArrayInputStream(pdf("Extrato com exclusão")), "exclusao.pdf", "STATEMENT", account, null, null);
    var source = store.get(SourceDocument.class, (UUID) doc.get("id"));
    var draft = map("movements", List.of(map("bookedOn", "2026-01-02", "description", "Comissão bancária", "amount", "5.00", "direction", "DEBIT", "currency", "EUR")), "validate", true);
    publisher.extracted(source.id, source.version, new DocumentConverter.Converted("Comissão bancária 5,00", 1, map("pages", List.of()), "TEST"), draft);
    var movement = store.all(BankMovement.class).getFirst();
    participation(true, selection("movement", book.movementView(movement)));
    var oldId = movement.id;
    store.flush();
    publisher.extracted(source.id, source.version, new DocumentConverter.Converted("Comissão bancária 5,00", 1, map("pages", List.of()), "TEST"), draft);
    assertThat(store.all(BankMovement.class)).hasSize(1);
    assertThat(movement.id).isEqualTo(oldId);
    assertThat(movement.excluded).isTrue();
    assertThat(movement.documentId).isEqualTo(source.id);
    assertThat(closing(2026, 1)).isEqualTo("0.00");
    participation(false, selection("movement", book.movementView(movement)));
    assertThat(closing(2026, 1)).isEqualTo("-5.00");
  }

  @Test
  void reorderingStatementRowsDoesNotMoveTheHumanExclusionEvenWithRepeatedBankReferences() throws Exception {
    var doc = documents.upload(new ByteArrayInputStream(pdf("Extrato reordenado")), "ordem.pdf", "STATEMENT", account, null, null);
    var source = store.get(SourceDocument.class, (UUID) doc.get("id"));
    var fee = map("bookedOn", "2026-01-02", "description", "Comissão", "amount", "5.00", "direction", "DEBIT", "bankReference", "SHARED");
    var other = map("bookedOn", "2026-01-03", "description", "Outra despesa", "amount", "10.00", "direction", "DEBIT", "bankReference", "SHARED");
    var converted = new DocumentConverter.Converted("Extrato", 1, map("pages", List.of()), "TEST");
    publisher.extracted(source.id, source.version, converted, map("movements", List.of(fee, other), "validate", true));
    var movement = store.all(BankMovement.class).stream().filter(m -> m.description.equals("Comissão")).findFirst().orElseThrow();
    UUID originalId = movement.id;
    participation(true, selection("movement", book.movementView(movement)));
    store.flush();
    publisher.extracted(source.id, source.version, converted, map("movements", List.of(other, fee), "validate", true));
    assertThat(store.all(BankMovement.class)).hasSize(2);
    assertThat(store.get(BankMovement.class, originalId)).satisfies(m -> {
      assertThat(m.description).isEqualTo("Comissão");
      assertThat(m.amount).isEqualByComparingTo("5.00");
      assertThat(m.excluded).isTrue();
    });
    assertThat(closing(2026, 1)).isEqualTo("-10.00");
    assertThat(rows(Json.object(source.draft), "movements")).allSatisfy(row -> assertThat(row.get("id")).isNotNull());
  }

  @Test
  void reorderingIndependentInvoicesPreservesExcludedIdentity() throws Exception {
    var doc = documents.upload(new ByteArrayInputStream(pdf("Comprovativos reordenados")), "comprovativos-ordem.pdf", "INVOICE", null, null, null);
    var source = store.get(SourceDocument.class, (UUID) doc.get("id"));
    var a = map("issuedOn", "2026-01-01", "supplier", "Papelaria Fonte", "number", "ORDER-A", "total", "5.00", "validate", true);
    var b = map("issuedOn", "2026-01-02", "supplier", "Papelaria Fonte", "number", "ORDER-B", "total", "10.00", "validate", true);
    var converted = new DocumentConverter.Converted("Comprovativos", 1, map("pages", List.of()), "TEST");
    publisher.extracted(source.id, source.version, converted, map("invoices", List.of(a, b), "validate", true));
    var invoice = store.all(Invoice.class).stream().filter(i -> i.number.equals("ORDER-A")).findFirst().orElseThrow();
    UUID originalId = invoice.id;
    participation(true, selection("invoice", book.invoiceView(invoice)));
    store.flush();
    publisher.extracted(source.id, source.version, converted, map("invoices", List.of(b, a), "validate", true));
    assertThat(store.all(Invoice.class)).hasSize(2);
    assertThat(store.get(Invoice.class, originalId)).satisfies(i -> {
      assertThat(i.number).isEqualTo("ORDER-A");
      assertThat(i.excluded).isTrue();
      assertThat(i.total).isEqualByComparingTo("5.00");
    });
    assertThat(closing(2026, 1)).isEqualTo("10.00");
  }

  @Test
  void unidentifiedChangedExtractionCannotTransferAnExclusionToAnotherRecord() throws Exception {
    var doc = documents.upload(new ByteArrayInputStream(pdf("Extrato alterado")), "alterado.pdf", "STATEMENT", account, null, null);
    var source = store.get(SourceDocument.class, (UUID) doc.get("id"));
    var converted = new DocumentConverter.Converted("Extrato", 1, map("pages", List.of()), "TEST");
    publisher.extracted(source.id, source.version, converted, map("movements", List.of(map("bookedOn", "2026-01-02", "description", "Comissão", "amount", "5.00", "direction", "DEBIT")), "validate", true));
    var movement = store.all(BankMovement.class).getFirst();
    participation(true, selection("movement", book.movementView(movement)));
    store.flush();
    assertThatThrownBy(() -> publisher.extracted(source.id, source.version, converted,
        map("movements", List.of(map("bookedOn", "2026-01-03", "description", "Novo movimento", "amount", "20.00", "direction", "DEBIT")), "validate", true)))
        .isInstanceOf(DomainException.class).hasMessageContaining("exclusão não foi transferida");
    assertThat(movement.excluded).isTrue();
    assertThat(movement.amount).isEqualByComparingTo("5.00");
    assertThat(store.all(BankMovement.class)).hasSize(1);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentExclusionAndAllocationCannotLeaveAnExcludedRecordMatched() throws Exception {
    var i = invoice("100.00", "2097-01-01", "CONCURRENT-EXCLUDE");
    var m = movement("100.00", "2097-01-02", "Pagamento genérico", "UNCLASSIFIED", "DEBIT");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var exclude = executor.submit(() -> {
        try {
          start.await();
          participation(true, new ExclusionRequest.Item("invoice", (UUID) i.get("id"), ((Number) i.get("version")).longValue()));
        } catch (Exception e) { throw new RuntimeException(e); }
      });
      var associate = executor.submit(() -> {
        try { start.await(); allocate(i, m, "100.00"); }
        catch (DomainException expected) { /* Exclusion may acquire the financial lock first. */ }
        catch (Exception e) { throw new RuntimeException(e); }
      });
      start.countDown();
      exclude.get(15, TimeUnit.SECONDS);
      associate.get(15, TimeUnit.SECONDS);
      transactions.executeWithoutResult(status -> {
        assertThat(store.get(Invoice.class, (UUID) i.get("id")).excluded).isTrue();
        assertThat(store.all(Allocation.class).stream().filter(a -> a.active)).isEmpty();
        assertThat(closing(2097, 1)).isEqualTo("-100.00");
      });
    } finally {
      jdbc.execute("truncate invoice,invoice_line,invoice_category,bank_account,bank_movement,allocation,ledger_entry,audit_event,job,alert cascade");
    }
  }
}
