package pt.conta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static pt.conta.shared.Inputs.map;
import static pt.conta.shared.Inputs.rows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pt.conta.ai.AiOrchestrator;
import pt.conta.ai.OllamaProvider;
import pt.conta.category.CategoryService;
import pt.conta.jobs.BackgroundWorker;
import pt.conta.jobs.JobMonitor;
import pt.conta.jobs.JobQueue;
import pt.conta.ledger.BookkeepingService;
import pt.conta.review.ReviewService;
import pt.conta.shared.AccountingProperties;
import pt.conta.shared.DomainException;
import pt.conta.shared.Json;

/** Drives the queue with committed fixtures, so failed attempts exercise real transaction rollback. */
@SpringBootTest
@ActiveProfiles("test")
@Import(CategoryIntegrationTest.Providers.class)
class InvoiceClassificationJobsIntegrationTest {
  private static final String SOFTWARE = "Software Tuxera NTFS for Mac";
  private static final String TOP_UP = "Carregamento de telemóvel";
  private static final String DISCARDED_CATEGORY = "Utilitários de sistema";
  private static EmbeddedPostgres postgres;

  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
    postgres = EmbeddedPostgres.builder().setPort(0).start();
    var storage = Files.createTempDirectory("conta-invoice-classification-jobs-");
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("accounting.storage", storage::toString);
  }

  @AfterAll static void closeDatabase() throws Exception {
    if (postgres != null) postgres.close();
  }

  @Autowired private BookkeepingService book;
  @Autowired private CategoryService categories;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JobQueue jobs;
  @Autowired private JobMonitor monitor;
  @Autowired private AiOrchestrator orchestrator;
  @Autowired private ReviewService review;
  @Autowired private CategoryIntegrationTest.FakeProvider provider;

  @BeforeEach void resetModel() { provider.calls = 0; }

  @Test void retriesUnsupportedProposalAndRollsBackItsCatalogChange() {
    UUID books = reference("6232");
    UUID invoiceId = invoiceWithRemovedBooks(books);
    UUID jobId = request(invoiceId);
    provider.program = modelRequest -> {
      var input = Json.read(modelRequest.input());
      assertThat(input.has("blockedCategoryIds")).isFalse();
      if (provider.calls == 1) {
        return response(List.of(map("id", books, "evidence", "Manual impresso")), List.of(create("new:discarded", DISCARDED_CATEGORY)));
      }
      assertThat(input.path("validationFeedback").path("error").asText()).contains("sem evidência");
      return response(List.of(label("new:software")), List.of(create("new:software", "Licenças de software")));
    };

    run(jobId);

    assertThat(provider.calls).isEqualTo(2);
    assertThat(categories.view("invoice", invoiceId)).containsEntry("jobId", jobId)
        .containsEntry("jobState", "DONE").containsEntry("rejectedCategoryIds", List.of());
    assertThat(resultAudit(invoiceId)).containsEntry("jobId", jobId.toString())
        .containsEntry("state", "CLASSIFIED").containsEntry("categoryNames", List.of("Licenças de software"));
    assertThat(categoryNames()).doesNotContain(DISCARDED_CATEGORY);
    assertThat(rows(monitor.page("active", 0, 50, invoiceId), "items")).noneMatch(row -> row.get("id").equals(jobId));
    assertThat(rows(monitor.page("DONE", 0, 50, invoiceId), "items")).extracting(row -> row.get("id")).contains(jobId);
    assertThat(rows(monitor.page("all", 0, 50, invoiceId), "items")).allSatisfy(row ->
        assertThat(row).containsEntry("targetId", invoiceId).containsEntry("kind", "CLASSIFY_INVOICE"));
    assertThat(monitor.detail(jobId)).containsEntry("state", "DONE");
  }

  @Test void exhaustedRetriesLeaveAnUnclassifiedInvoiceForReviewWithoutCatalogChanges() {
    UUID invoiceId = invoiceWithRemovedBooks(reference("6232"));
    UUID jobId = request(invoiceId);
    provider.program = ignored -> response(List.of(), List.of(create("new:discarded", DISCARDED_CATEGORY)));

    run(jobId);

    assertThat(provider.calls).isEqualTo(3);
    assertThat(categories.view("invoice", invoiceId)).containsEntry("state", "REVIEW").containsEntry("jobState", "DONE");
    assertThat(resultAudit(invoiceId)).containsEntry("categoryNames", List.of());
    assertThat(categoryNames()).doesNotContain(DISCARDED_CATEGORY);
  }

  @Test void reviewAssessesSavedLabelsAndCanRestoreAPreviouslyRemovedCategoryOnApproval() {
    UUID telecom = reference("6262"), insurance = reference("6263"), office = reference("6233");
    UUID invoiceId = invoice(TOP_UP + " e Papel A4");
    categories.manual("invoice", invoiceId, version(invoiceId), List.of(telecom), false);
    categories.manual("invoice", invoiceId, version(invoiceId), List.of(insurance), false);
    UUID automatic = request(invoiceId);
    provider.program = ignored -> response(List.of(map("id", office, "evidence", "Papel A4")), List.of());
    run(automatic);
    var savedCategories = categories.details("invoice", invoiceId);
    long catalogRevision = categories.revision();
    UUID jobId = requestReview(invoiceId);
    assertStaleReview(invoiceId, jobId, telecom);
    provider.program = request -> reviewTelecomProposal(request.input(), insurance, office, telecom);
    run(jobId);

    var view = categories.view("invoice", invoiceId);
    assertThat(provider.calls).isEqualTo(4);
    assertThat(view).containsEntry("jobState", "DONE").containsEntry("categories", savedCategories)
        .containsEntry("catalogRevision", catalogRevision).containsEntry("rejectedCategoryIds", List.of());
    assertThat(view.get("review")).asString().contains("INSUFFICIENT_EVIDENCE", "GOOD_FIT", TOP_UP);
    assertThat(monitor.detail(jobId)).containsEntry("kind", CategoryService.INVOICE_CATEGORY_REVIEW_JOB);
    assertInvalidSelection(invoiceId, jobId, UUID.randomUUID());

    var applied = categories.applyInvoiceCategoryReview(invoiceId, version(invoiceId), jobId, List.of(office, telecom));
    assertThat(rows(applied, "categories")).extracting(tag -> tag.get("id")).containsExactlyInAnyOrder(office, telecom);
    assertThat(rows(applied, "categories")).contains(savedCategories.stream()
        .filter(category -> office.equals(category.get("id"))).findFirst().orElseThrow());
    assertThat(rows(applied, "categories")).filteredOn(tag -> telecom.equals(tag.get("id"))).singleElement()
        .satisfies(tag -> assertThat(rows(tag, "evidence")).singleElement().satisfies(evidence -> assertThat(evidence)
            .containsEntry("origin", "MANUAL").containsEntry("reason", TOP_UP)
            .hasEntrySatisfying("executionId", execution -> assertThat(execution).isNotNull())));
    assertThat(audit(invoiceId, "APPLY_AI_CATEGORY_REVIEW")).containsEntry("jobId", jobId.toString());
    assertStaleReview(invoiceId, jobId, telecom);
  }

  @Test void anAssessmentWithoutAlternativesCannotBeAppliedAfterTheCatalogOrInvoiceChanges() {
    UUID insurance = reference("6263");
    UUID invoiceId = invoice("Transferência");
    categories.manual("invoice", invoiceId, version(invoiceId), List.of(insurance), false);
    provider.program = ignored -> Json.write(map("summary", "Descrição insuficiente", "assessments",
        List.of(assessment(insurance, "INSUFFICIENT_EVIDENCE")), "suggestions", List.of()));
    UUID oldCatalogJob = requestReview(invoiceId);
    run(oldCatalogJob);
    assertThat(categories.view("invoice", invoiceId).get("review")).asString().contains("suggestions=[]");
    categories.save(map("name", "Nova categoria", "aliases", List.of()));
    assertStaleReview(invoiceId, oldCatalogJob, insurance);

    UUID oldInvoiceJob = requestReview(invoiceId);
    run(oldInvoiceJob);
    jdbc.update("update invoice set description=? where id=?", "Seguro anual", invoiceId);
    assertStaleReview(invoiceId, oldInvoiceJob, insurance);
    assertThat(categories.details("invoice", invoiceId)).extracting(tag -> tag.get("id")).containsExactly(insurance);
  }

  @Test void anAutomaticJobCanAssignPreviouslyRemovedTelecom() {
    UUID telecom = reference("6262");
    UUID invoiceId = invoice(TOP_UP);
    categories.manual("invoice", invoiceId, version(invoiceId), List.of(telecom), false);
    categories.manual("invoice", invoiceId, version(invoiceId), List.of(), false);
    jdbc.update("update invoice set description=? where id=?", TOP_UP + " pré-pago", invoiceId);
    categories.recordChanged("invoice", invoiceId, Map.of(), true);
    var queued = categories.view("invoice", invoiceId);
    assertThat(queued).containsEntry("jobKind", "CLASSIFY_INVOICE").containsEntry("jobState", "QUEUED");
    provider.program = ignored -> response(List.of(map("id", telecom, "evidence", TOP_UP)), List.of());
    run((UUID) queued.get("jobId"));
    assertThat(categories.details("invoice", invoiceId)).extracting(tag -> tag.get("id")).containsExactly(telecom);
  }

  @Test void aValidReviewResponseCompletesWithoutRetries() {
    UUID invoiceId = invoiceWithRemovedBooks(reference("6232"));
    UUID jobId = request(invoiceId);
    String reason = "O catálogo não contém uma categoria elegível sustentada pela fatura.";
    provider.program = ignored -> Json.write(map("status", "REVIEW", "categories", List.of(), "operations", List.of(),
        "explanation", reason));

    run(jobId);

    assertThat(provider.calls).isEqualTo(1);
    assertThat(categories.view("invoice", invoiceId)).containsEntry("state", "REVIEW").containsEntry("jobState", "DONE");
    assertThat(resultAudit(invoiceId)).containsEntry("reason", reason);
  }

  @Test void exhaustedEmptyResponsesKeepSavedCategoriesAndModelOutagesKeepTheJobWaiting() {
    UUID invoiceId = invoiceWithRemovedBooks(reference("6232"));
    categories.manual("invoice", invoiceId, version(invoiceId), List.of(reference("6233")), false);
    UUID jobId = request(invoiceId);
    provider.program = ignored -> response(List.of(), List.of());

    run(jobId);

    assertThat(provider.calls).isEqualTo(3);
    assertThat(resultAudit(invoiceId)).containsEntry("state", "CLASSIFIED")
        .containsEntry("categoryNames", List.of("Material de escritório"));

    UUID waitingJob = request(invoiceId);
    provider.program = ignored -> { throw new OllamaProvider.AiUnavailable("Modelo indisponível", null); };
    run(waitingJob);
    assertThat(categories.view("invoice", invoiceId)).containsEntry("jobId", waitingJob)
        .containsEntry("state", "WAITING").containsEntry("jobState", "WAITING");
    assertThat(resultAudit(invoiceId)).containsEntry("jobId", jobId.toString());
  }

  private UUID invoiceWithRemovedBooks(UUID books) {
    UUID invoiceId = invoice(SOFTWARE);
    categories.manual("invoice", invoiceId, version(invoiceId), List.of(books), false);
    categories.manual("invoice", invoiceId, version(invoiceId), List.of(), false);
    return invoiceId;
  }

  private UUID invoice(String description) {
    return (UUID) book.saveInvoice(null, map("issuedOn", "2026-01-01", "supplier", "Fornecedor",
        "number", UUID.randomUUID().toString(), "description", description, "total", "20.33", "validate", true)).get("id");
  }

  private UUID requestReview(UUID id) {
    long version = version(id);
    var first = categories.requestInvoiceCategoryReview(id, version);
    assertThat(first).containsEntry("jobState", "QUEUED").containsEntry("jobKind", CategoryService.INVOICE_CATEGORY_REVIEW_JOB);
    assertThat(categories.requestInvoiceCategoryReview(id, version)).isEqualTo(first);
    return (UUID) first.get("jobId");
  }

  private String reviewTelecomProposal(String input, UUID insurance, UUID office, UUID telecom) {
    var request = Json.read(input);
    assertThat(request.path("currentAssignments").toString()).contains(insurance.toString(), office.toString());
    var feedback = request.path("validationFeedback");
    var assessments = !feedback.isMissingNode()
        ? List.of(assessment(insurance, "INSUFFICIENT_EVIDENCE"), assessment(office, "GOOD_FIT"))
        : List.of(assessment(office, "GOOD_FIT"));
    String quote = feedback.path("error").asText().contains("sem evidência") ? TOP_UP : "Assinatura mensal";
    return Json.write(map("summary", "A fatura inclui um carregamento.", "assessments", assessments,
        "suggestions", List.of(map("categoryId", telecom, "reason", "É um serviço de comunicação.",
            "evidence", quote, "replacesCategoryIds", List.of(insurance)))));
  }

  private Map<String, Object> assessment(UUID id, String fit) {
    return map("categoryId", id, "fit", fit, "reason", "Avaliação da descrição guardada.");
  }

  private void assertInvalidSelection(UUID id, UUID jobId, UUID category) {
    assertThatThrownBy(() -> categories.applyInvoiceCategoryReview(id, version(id), jobId, List.of(category)))
        .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.status).isEqualTo(422));
  }

  private void assertStaleReview(UUID id, UUID jobId, UUID category) {
    assertThatThrownBy(() -> categories.applyInvoiceCategoryReview(id, version(id), jobId, List.of(category)))
        .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.status).isEqualTo(409));
  }

  private UUID request(UUID invoiceId) {
    long version = version(invoiceId);
    var scheduled = categories.requestInvoiceClassification(invoiceId, version);
    UUID jobId = (UUID) scheduled.get("jobId");
    assertThat(jobId).isNotNull();
    assertThat(categories.requestInvoiceClassification(invoiceId, version)).containsEntry("jobId", jobId);
    assertThat(audit(invoiceId, "REQUEST_AI_CLASSIFICATION")).containsEntry("jobId", jobId.toString())
        .containsEntry("jobState", "QUEUED");
    return jobId;
  }

  private void run(UUID jobId) {
    jdbc.update("update job set available_at=now()+interval '1 hour' where state='QUEUED' and id<>?", jobId);
    var enabled = mock(AccountingProperties.class);
    when(enabled.jobsEnabled()).thenReturn(true);
    new BackgroundWorker(enabled, jobs, null, null, orchestrator, review, new SimpleMeterRegistry()).tick();
  }

  private long version(UUID id) { return ((Number) categories.view("invoice", id).get("version")).longValue(); }

  private UUID reference(String code) {
    return jdbc.queryForObject("select id from category where reference_code=?", UUID.class, code);
  }

  private List<String> categoryNames() { return jdbc.queryForList("select name from category", String.class); }

  private Map<String, Object> resultAudit(UUID invoiceId) { return audit(invoiceId, "AI_CLASSIFICATION_RESULT"); }

  private Map<String, Object> audit(UUID invoiceId, String action) {
    return Json.object(jdbc.queryForObject("""
        select after_value from audit_event where entity_id=? and action=? order by created_at desc,id desc limit 1
        """, String.class, invoiceId, action));
  }

  private Map<String, Object> label(Object id) { return map("id", id, "evidence", SOFTWARE); }

  private Map<String, Object> create(String key, String name) {
    return map("kind", "CREATE", "key", key, "name", name, "description", name, "parentId", reference("623"), "targetId", null,
        "sourceIds", List.of(), "evidence", SOFTWARE, "reason", "A fatura identifica o software adquirido");
  }

  private String response(List<Map<String, Object>> labels, List<Map<String, Object>> operations) {
    return Json.write(map("status", "CLASSIFIED", "categories", labels, "operations", operations,
        "explanation", "Classificação baseada na descrição da fatura"));
  }
}
