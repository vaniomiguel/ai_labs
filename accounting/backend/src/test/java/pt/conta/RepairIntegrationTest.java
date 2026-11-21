package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static pt.conta.shared.Inputs.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import pt.conta.ai.*;
import pt.conta.document.*;
import pt.conta.invoice.Invoice;
import pt.conta.jobs.*;
import pt.conta.review.*;
import pt.conta.ledger.*;
import pt.conta.shared.*;

/** Real request/result transactions and source rendering with a controlled provider. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class RepairIntegrationTest {
  static EmbeddedPostgres postgres;
  @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception {
    postgres=EmbeddedPostgres.builder().setPort(0).start();
    r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));
    r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
    r.add("accounting.storage",()->System.getProperty("java.io.tmpdir")+"/conta-repair-tests");
  }
  @TestConfiguration static class Providers {@Bean @Primary FakeProvider repairProvider(){return new FakeProvider();}}
  static class FakeProvider implements AiProvider {
    int calls;boolean vision;Function<Request,String> program;
    boolean varyUsage;
    public List<Model> models(){return List.of(new Model("qwen3.5:9b","repair-test-digest",vision,true,true));}
    public Completion complete(Request request) {
      calls++;
      var usage = !varyUsage ? new Usage(700, 100, 1000, CapacitySource.RUNNING_MODEL,
          "stop", ContextWarning.NEAR_CONTEXT_LIMIT) : statementUsage();
      return new Completion(program.apply(request), usage);
    }
    private Usage statementUsage() {
      return calls == 1
          ? new Usage(900, 100, 1000, CapacitySource.RUNNING_MODEL, "stop", ContextWarning.CONTEXT_LIMIT_REACHED)
          : new Usage(700, 100, 1000, CapacitySource.RUNNING_MODEL, "stop", ContextWarning.NEAR_CONTEXT_LIMIT);
    }
  }
  @Autowired Store store;@Autowired RepairService repairs;@Autowired RepairAiWorker worker;
  @Autowired FakeProvider provider;@Autowired DocumentStorage storage;@Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;@Autowired JobQueue queue;
  @Autowired pt.conta.api.DocumentController controller;
  @Autowired AiOrchestrator orchestrator;
  @Autowired JobMonitor jobMonitor;
  @Autowired BookkeepingService book;
  @Autowired ReviewService review;
  private record Fixture(UUID doc,UUID alert){}

  @BeforeEach void setup(){provider.calls=0;provider.vision=false;provider.varyUsage=false;provider.program=_->Json.write(response(valid(),List.of()));}
  private Map<String,Object> valid(){return map("issuedOn","2026-01-01","supplier","Fornecedor de teste","total","100.00",
      "currency","EUR","type","INVOICE","categories",List.of(),"lines",List.of());}
  private Map<String,Object> response(Map<String,Object> draft,List<String> issues){return map("draft",draft,"evidence",List.of(),"issues",issues);}
  private Fixture fixture(String source,int pages,String layout)throws Exception {
    byte[] bytes;
    try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
      for(int n=0;n<pages;n++)pdf.addPage(new PDPage());pdf.save(out);bytes=out.toByteArray();
    }
    var stored=storage.put(new ByteArrayInputStream(bytes),1000000);
    var doc=new SourceDocument();doc.filename="repair-test.pdf";doc.kind="INVOICE";doc.status="OPEN";
    doc.mediaType="application/pdf";doc.storageKey=stored.key();doc.sha256=stored.sha256();doc.size=stored.size();
    doc.text=source;doc.pages=pages;doc.layout=layout;doc.draft=Json.write(map("supplier",null,"issuedOn",null,"total",null));store.add(doc);
    var alert=new Alert();alert.documentId=doc.id;alert.entityType="document";alert.entityId=doc.id;alert.code="INVALID_INVOICE";
    alert.message="Falta a data da fatura; Falta o fornecedor; Falta um total válido";store.add(alert);store.flush();
    return new Fixture(doc.id,alert.id);
  }
  private Fixture fixture()throws Exception{return fixture("Fatura 2026-01-01 Fornecedor de teste Total 100,00",1,"{}");}
  private Fixture statementFixture(String source, int pages, String layout) throws Exception {
    var fixture = fixture(source, pages, layout);
    var doc = store.get(SourceDocument.class, fixture.doc);
    doc.kind = "STATEMENT";
    doc.draft = Json.write(map("movements", List.of()));
    store.flush();
    return fixture;
  }
  private Map<String, Object> statementResponse() {
    return response(map("movements", List.of()), List.of());
  }

  private Map<String,Object> task(Map<String,Object> alert){return Json.object(Json.write(alert.get("repair")));}
  private JobQueue.Claim request(Fixture f){
    var view=repairs.request(f.alert);UUID id=UUID.fromString(task(view).get("jobId").toString());
    return new JobQueue.Claim(id,"REPAIR_ALERT",f.alert,((Number)view.get("version")).longValue(),"{}",0);
  }
  private void process(JobQueue.Claim claim)throws Exception {worker.process(claim);jdbc.update("update job set state='DONE' where id=?",claim.id());}
  private void assertNoMoney(){assertThat(store.all(LedgerEntry.class)).isEmpty();assertThat(store.all(pt.conta.invoice.Invoice.class)).isEmpty();}

  @Test void requestReturnsAcceptedAndRepeatedClicksReuseTheActiveJob()throws Exception {
    var f=fixture();var accepted=controller.repair(f.alert);
    assertThat(accepted.getStatusCode().value()).isEqualTo(202);
    var first=Objects.requireNonNull(accepted.getBody(), "Initial repair response body");
    var second=Objects.requireNonNull(controller.repair(f.alert).getBody(), "Repeated repair response body");
    assertThat(task(second).get("jobId")).isEqualTo(task(first).get("jobId"));
    assertThat(second.get("version")).isEqualTo(first.get("version"));
    assertThat(task(controller.alert(f.alert)).get("state")).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("select count(*) from job where target_id=?",Integer.class,f.alert)).isEqualTo(1);assertNoMoney();
  }

  @Test void aLateItemRepairRemainsStaleAfterHumanValidation() throws Exception {
    var doc = store.get(SourceDocument.class, fixture().doc);
    var item = book.saveInvoice(null, map("documentId", doc.id, "supplier", "Fornecedor", "total", "100.00"));
    UUID id = (UUID) item.get("id");
    var alert = review.openFor("invoice", id).orElseThrow();
    review.open(doc.id, "invoice", id, "EXTRACTION_REVIEW", "Confirma a referência");
    var claim = request(new Fixture(doc.id, alert.id));
    var snapshot = repairs.begin(claim);
    assertThat(snapshot.concerns()).extracting(Alert.Concern::message).contains("Confirma a referência");
    var invoice = store.get(Invoice.class, id);
    repairs.saveRecordDraft(alert.id, map("alertVersion", alert.version, "recordVersion", invoice.version,
        "documentVersion", doc.version, "resolve", true, "draft", valid()));
    assertThat(repairs.complete(claim, snapshot, response(valid(), List.of("Outro problema")), Map.of(),
        new ExtractionAttempt.Prompt("Sistema", "Documento"))).isFalse();
    assertThat(alert.status).isEqualTo("RESOLVED");
    assertThat(alert.proposal).isNull();
    assertThat(alert.concerns()).extracting(Alert.Concern::message).doesNotContain("Outro problema");
    assertThat(task(repairs.view(alert.id))).containsEntry("outcome", "STALE");
  }

  @Test void fullTextLongerThanOldChunksReachesTheProviderUnchanged()throws Exception {
    // Leave room for explicit schema/field state while still exceeding the former chunk boundary.
    String source="Documento "+"texto completo ".repeat(2140)+" FIM";
    assertThat(source.length()).isGreaterThan(32000);
    var f=fixture(source,1,"{\"pages\":[{\"page\":1,\"text\":\"excerto incompleto\"}]}");
    provider.program=request->{assertThat(Json.read(request.input()).path("sourceText").asText()).isEqualTo(source);
      assertThat(request.input()).contains("Falta a data da fatura");assertThat(request.images()).isEmpty();assertThat(request.requireFullContext()).isTrue();return Json.write(response(valid(),List.of()));};
    var job = request(f);
    process(job);
    assertThat(provider.calls).isEqualTo(1);
    assertThat(store.all(AiExecution.class)).singleElement().satisfies(execution -> {
      assertThat(execution.jobId).isEqualTo(job.id());
      assertThat(execution.model).isEqualTo("qwen3.5:9b");
      assertThat(execution.usage().contextTokens()).isEqualTo(1000);
    });
    assertNoMoney();
  }

  @Test void humanFieldProvenanceAndComplementOnlyInstructionsReachTheModel()throws Exception {
    var f = fixture();
    var doc = store.get(SourceDocument.class, f.doc);
    doc.draft = Json.write(map("supplier", "Nome humano", "issuedOn", null, "total", null,
        "description", "Descrição humana"));
    var human = map("origin", "HUMAN_EDIT", "protected", true, "actor", "gestor");
    doc.fieldProvenance = Json.write(map("root", map("supplier", human, "description", human)));
    store.flush();
    provider.program = request -> {
      var input = Json.read(request.input());
      assertThat(input.path("fieldProvenance").path("root").path("supplier").path("protected").asBoolean())
          .isTrue();
      assertThat(rows(Json.object(request.input()), "filledFields")).anySatisfy(field ->
          assertThat(field).containsEntry("path", "description").containsEntry("correctionAllowed", false));
      assertThat(request.instructions()).contains("Human-entered values are authoritative", "only suggestions",
          "Preserve human and unknown-origin descriptions");
      var draft = valid();
      draft.put("description", "Descrição humana");
      return Json.write(response(draft, List.of()));
    };
    process(request(f));
    assertThat(Json.object(doc.draft)).containsEntry("supplier", "Nome humano")
        .containsEntry("description", "Descrição humana");
    assertThat(Json.read(store.get(Alert.class, f.alert).proposal).path("draft").path("description").asText())
        .isEqualTo("Descrição humana");
    assertNoMoney();
  }

  @Test void copiedOcrInAnAutomaticDescriptionCanReceiveAnEvidenceBackedRepairSuggestion() throws Exception {
    String product = "Tuxera NTFS for Mac";
    String source = "Emissão 2026-01-01\nFornecedor de teste\n" + product + "\nTotal 100,00";
    String summary = "Software " + product;
    var f = fixture(source, 1, "{}");
    var doc = store.get(SourceDocument.class, f.doc);
    var saved = valid();
    saved.put("description", source);
    doc.draft = Json.write(saved);
    doc.fieldProvenance = Json.write(map("root", map(
        "description", map("origin", "AUTOMATIC", "protected", false))));
    store.flush();
    provider.program = request -> {
      var input = Json.object(request.input());
      assertThat(rows(input, "filledFields")).anySatisfy(field -> assertThat(field)
          .containsEntry("path", "description").containsEntry("value", source)
          .containsEntry("correctionAllowed", true));
      assertThat(request.instructions()).contains("Replace copied OCR or invoice boilerplate");
      var draft = valid();
      draft.put("description", summary);
      return Json.write(map("draft", draft, "issues", List.of(), "evidence",
          List.of(map("field", "description", "quote", product, "page", null))));
    };

    process(request(f));

    var proposal = Json.object(Json.write(repairs.view(f.alert).get("proposal")));
    assertThat(rows(proposal, "changes")).singleElement().satisfies(change -> assertThat(change)
        .containsEntry("field", "description").containsEntry("proposed", summary));
    assertThat(rows(proposal, "evidence")).singleElement().satisfies(evidence -> assertThat(evidence)
        .containsEntry("field", "description").containsEntry("quote", product));
    assertThat(Json.object(doc.draft)).containsEntry("description", source);
  }

  @Test void savedHumanValuesAndAutomaticCorrectionsHaveExplicitModelContext() throws Exception {
    var f = fixture("Emissão 2026-01-01 Fornecedor OCR Total 200,00", 1, "{}");
    var doc = store.get(SourceDocument.class, f.doc);
    doc.draft = Json.write(map("supplier", "Human supplier", "issuedOn", null, "total", "100.00"));
    doc.fieldProvenance = Json.write(map("root", map(
        "supplier", map("origin", "HUMAN_EDIT", "protected", true),
        "total", map("origin", "AUTOMATIC", "protected", false))));
    store.flush();
    provider.program = request -> {
      var input = Json.object(request.input());
      var supplier = rows(input, "filledFields").stream()
          .filter(field -> field.get("path").equals("supplier")).findFirst().orElseThrow();
      var total = rows(input, "filledFields").stream()
          .filter(field -> field.get("path").equals("total")).findFirst().orElseThrow();
      assertThat(supplier).containsEntry("value", "Human supplier").containsEntry("authoritative", true)
          .containsEntry("correctionAllowed", false);
      assertThat(total).containsEntry("value", "100.00").containsEntry("origin", "AUTOMATIC")
          .containsEntry("correctionAllowed", true);
      assertThat(rows(input, "fieldsToExtract").getFirst()).containsEntry("path", "issuedOn")
          .containsEntry("requiredForValidation", true);
      assertThat(input.get("responseSchema")).isEqualTo(request.schema());
      var proposed = valid();
      proposed.put("supplier", "Human supplier");
      proposed.put("total", "200.00");
      return Json.write(map("draft", proposed,
          "evidence", List.of(map("field", "total", "quote", "Total 200,00", "page", null)),
          "issues", List.of("OCR supplier differs from the authoritative human value: Fornecedor OCR")));
    };
    process(request(f));
    assertThat(Json.object(doc.draft)).containsEntry("supplier", "Human supplier").containsEntry("total", "100.00");
    var proposal = Json.read(store.get(Alert.class, f.alert).proposal);
    assertThat(proposal.path("draft").path("supplier").asText()).isEqualTo("Human supplier");
    assertThat(proposal.path("draft").path("total").asText()).isEqualTo("200.00");
    assertThat(proposal.path("issues").get(0).asText()).contains("authoritative human value");
    assertNoMoney();
  }

  @Test void aStatementVisualModelReceivesEveryRealPdfPageWithoutLayoutMetadata()throws Exception {
    var f=statementFixture("Texto de todas as páginas",4,"{}");provider.vision=true;
    var sent = new ArrayList<AiProvider.Request>();
    provider.program=request->{assertThat(request.images()).hasSize(4);
      for(var encoded:request.images())assertThat(Base64.getDecoder().decode(encoded)).startsWith((byte)137,(byte)80,(byte)78,(byte)71);
      assertThat(Json.read(request.input()).path("sourcePages").toString()).isEqualTo("[1,2,3,4]");
      sent.add(request);
      return Json.write(statementResponse());};
    process(request(f));var context=(Map<?,?>)task(repairs.view(f.alert)).get("context");
    assertThat(context.get("textComplete")).isEqualTo(true);assertThat((List<?>)context.get("pages")).hasSize(4);assertNoMoney();
    var attempt = controller.attempts(f.doc).getFirst();
    assertThat(attempt.get("engine").toString()).contains("qwen3.5:9b");
    assertThat(attempt.get("prompts")).isEqualTo(List.of(promptMessages(sent.getFirst())));
  }

  @Test void automaticStatementAttemptIncludesEachPromptInRequestOrder() throws Exception {
    String source = "A".repeat(32000) + "B";
    var f = statementFixture(source, 1, "{}");
    var doc = store.get(SourceDocument.class, f.doc);
    var historicalAttempt = new ExtractionAttempt();
    historicalAttempt.documentId = doc.id;
    historicalAttempt.engine = "PDFBOX";
    store.add(historicalAttempt);
    var sent = new ArrayList<AiProvider.Request>();
    provider.varyUsage = true;
    provider.program = request -> {
      sent.add(request);
      return Json.write(statementResponse());
    };
    queue.enqueue("AI_EXTRACT_DOCUMENT", doc.id, doc.version, "{}");
    var job = queue.claim().orElseThrow();
    orchestrator.process(job);

    assertThat(sent).hasSize(2);
    var attempts = controller.attempts(doc.id);
    assertThat(attempts).hasSize(2);
    assertThat(attempts.getFirst()).containsEntry("prompts", List.of());
    assertThat(attempts.getLast()).containsEntry("prompts", sent.stream().map(this::promptMessages).toList());
    assertStatementUsage(job);
  }

  private void assertStatementUsage(JobQueue.Claim job) {
    assertThat(store.all(AiExecution.class)).hasSize(2).allSatisfy(execution -> {
      assertThat(execution.jobId).isEqualTo(job.id());
      assertThat(execution.model).isEqualTo("qwen3.5:9b");
    }).extracting(e -> e.contextWarning).containsExactly(
        AiProvider.ContextWarning.CONTEXT_LIMIT_REACHED, AiProvider.ContextWarning.NEAR_CONTEXT_LIMIT);
    store.flush();
    var detail = Json.read(Json.write(jobMonitor.detail(job.id())));
    assertThat(detail.path("contextWarning").path("usage").path("warning").asText()).isEqualTo("CONTEXT_LIMIT_REACHED");
    queue.finish(job, null, false);
    var active = Json.read(Json.write(jobMonitor.page("active", 0, 50)));
    var latest = active.path("recentContextWarnings").get(0);
    assertThat(latest.path("jobId").asText()).isEqualTo(job.id().toString());
    assertThat(latest.path("warning").path("model").asText()).isEqualTo("qwen3.5:9b");
  }

  private Map<String, Object> promptMessages(AiProvider.Request request) {
    return map("system", request.instructions(), "user", request.input());
  }

  @Test void incompleteStatementLayoutCannotOmitOtherPdfPages()throws Exception {
    var f=statementFixture("Texto integral",3,"{\"pages\":[{\"page\":1,\"text\":\"Primeira página\"}]}");provider.vision=true;
    provider.program=request->{assertThat(request.images()).hasSize(3);return Json.write(statementResponse());};
    process(request(f));
  }

  @Test void textOnlyModelGetsAllTextWithoutImages()throws Exception {
    var f=fixture("Texto completo de três páginas",3,"{}");
    provider.program=request->{assertThat(request.images()).isEmpty();assertThat(Json.read(request.input()).path("sourceText").asText()).isEqualTo("Texto completo de três páginas");return Json.write(response(valid(),List.of()));};
    process(request(f));assertThat(((Map<?,?>)task(repairs.view(f.alert)).get("context")).get("vision")).isEqualTo(false);
  }

  @Test void excessiveStatementPageCountFailsWithoutSendingAPartialSetOfImages()throws Exception {
    var f=statementFixture("Texto completo",17,"{}");provider.vision=true;
    assertThatThrownBy(()->worker.process(request(f))).isInstanceOf(AiProvider.ContextExceeded.class).hasMessageContaining("Nenhuma página foi omitida");
    assertThat(provider.calls).isZero();assertNoMoney();
  }

  @Test void invoiceRepairNeverReadsPdfOrSendsImagesEvenForAVisualModel() throws Exception {
    String source = "Fatura completa de dezassete páginas";
    var f = fixture(source, 17, "{}");
    var doc = store.get(SourceDocument.class, f.doc);
    doc.storageKey = "unavailable-original.pdf";
    store.flush();
    provider.vision = true;
    provider.program = request -> {
      var input = Json.read(request.input());
      assertThat(input.path("sourceText").asText()).isEqualTo(source);
      assertThat(input.path("fields").asText()).contains("issuedOn", "lines[n].unitPrice", "categories: always []");
      assertThat(input.path("alert").asText()).contains("Falta a data");
      assertThat(input.has("data")).isTrue();
      assertThat(input.has("fieldProvenance")).isTrue();
      assertThat(input.has("documentVersion")).isTrue();
      assertThat(input.path("sourcePages").isEmpty()).isTrue();
      assertThat(request.images()).isEmpty();
      assertThat(request.requireFullContext()).isTrue();
      assertThat(request.instructions()).startsWith("You are an expert accountant");
      assertThat(request.instructions()).containsSubsequence("look first to the text surrounding each of those dates",
          "same month and year", "pick the earliest one", "Otherwise, if the invoice date remains uncertain, use null")
          .contains("Explicitly identified non-invoice dates are not candidates");
      assertThat(input.path("fields").asText()).doesNotContain("dueOn");
      assertThat(input.path("fieldsToExtract").toString()).doesNotContain("dueOn");
      assertThat(input.path("responseTemplate").toString()).doesNotContain("dueOn");
      assertThat(Json.write(request.schema())).doesNotContain("dueOn");
      return Json.write(response(valid(), List.of()));
    };
    process(request(f));
    assertThat(provider.calls).isEqualTo(1);
    var context = (Map<?, ?>) task(repairs.view(f.alert)).get("context");
    assertThat((List<?>) context.get("imageHashes")).isEmpty();
    assertThat((List<?>) context.get("pages")).isEmpty();
    assertNoMoney();
  }

  @Test void blankInvoiceTextIsRejectedBeforeGeneration() throws Exception {
    var f = fixture(" \n\t", 1, "{}");
    assertThatThrownBy(() -> worker.process(request(f))).isInstanceOf(DomainException.class)
        .hasMessageContaining("texto extraído");
    assertThat(provider.calls).isZero();
    assertNoMoney();
  }

  @Test void providerContextRejectionPermanentlyFailsTheRepair() throws Exception {
    var f = fixture();
    request(f);
    provider.program = _ -> { throw new AiProvider.ContextExceeded("Limite de contexto do modelo"); };
    var enabled = org.mockito.Mockito.mock(AccountingProperties.class);
    org.mockito.Mockito.when(enabled.jobsEnabled()).thenReturn(true);
    new BackgroundWorker(enabled, queue, null, null, orchestrator, null,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).tick();
    assertThat(task(repairs.view(f.alert))).containsEntry("state", "FAILED");
    assertThat(queue.claim()).isEmpty();
    assertThat(store.get(Alert.class, f.alert).proposal).isNull();
    assertNoMoney();
  }

  @Test void readyProposalDoesNotPublishBeforeExplicitConfirmation()throws Exception {
    var f=fixture();process(request(f));var view=repairs.view(f.alert);
    assertThat(task(view)).containsEntry("outcome","READY").containsEntry("canApply",true);
    assertThat(store.get(SourceDocument.class,f.doc).draft).doesNotContain("100.00");assertNoMoney();
    controller.apply(f.alert);
    assertThat(store.all(LedgerEntry.class)).hasSize(1);
    assertThat(store.get(Alert.class,f.alert).status).isEqualTo("OPEN");
  }

  @Test void missingRequiredFieldsAndWarningsProduceAnExplicitReviewResult()throws Exception {
    var f=fixture();provider.program=_-> {var draft=valid();draft.put("issuedOn",null);return Json.write(response(draft,List.of("Data ilegível")));};
    process(request(f));var task=task(repairs.view(f.alert));
    assertThat(task).containsEntry("outcome","REVIEW_REQUIRED").containsEntry("canApply",false);
    assertThat(task.get("blockingIssues").toString()).contains("Falta a data");
    assertThatThrownBy(()->controller.apply(f.alert)).isInstanceOf(DomainException.class);assertNoMoney();
  }

  @Test void unchangedBusinessDataIsReportedWithoutAnotherApplication()throws Exception {
    var f=fixture();store.get(SourceDocument.class,f.doc).draft=Json.write(valid());store.flush();
    process(request(f));assertThat(task(repairs.view(f.alert))).containsEntry("outcome","NO_CHANGES").containsEntry("canApply",false);assertNoMoney();
  }

  @Test void missingModelValuesAreNotPresentedAsApplicableDeletions()throws Exception {
    var f=fixture();store.get(SourceDocument.class,f.doc).draft=Json.write(valid());store.flush();
    provider.program=_-> {var draft=valid();draft.put("supplier",null);draft.put("total",null);return Json.write(response(draft,List.of()));};
    process(request(f));assertThat(task(repairs.view(f.alert))).containsEntry("outcome","NO_CHANGES").containsEntry("canApply",false).containsEntry("canApplyDraft",false);assertNoMoney();
  }

  @Test void editingTheSourceWhileTheModelRunsMakesTheResultStale()throws Exception {
    var f=fixture();provider.program=_-> {var doc=store.get(SourceDocument.class,f.doc);doc.text="Texto corrigido";store.flush();return Json.write(response(valid(),List.of()));};
    process(request(f));assertThat(task(repairs.view(f.alert))).containsEntry("outcome","STALE").containsEntry("canApply",false);
    assertThat(store.get(Alert.class,f.alert).proposal).isNull();assertNoMoney();
  }

  @Test void resolvingTheAlertWhileTheModelRunsCannotRestoreTheProposal()throws Exception {
    var f=fixture();provider.program=_-> {store.get(Alert.class,f.alert).status="RESOLVED";store.flush();return Json.write(response(valid(),List.of()));};
    process(request(f));assertThat(task(repairs.view(f.alert)).get("outcome")).isEqualTo("STALE");assertThat(store.get(Alert.class,f.alert).proposal).isNull();
  }

  @Test void newRequestAfterSourceEditSupersedesTheOlderRunningResult()throws Exception {
    var f=fixture();var old=request(f);UUID[] newer={null};
    provider.program=_-> {var doc=store.get(SourceDocument.class,f.doc);doc.text="Dados novos";store.flush();newer[0]=request(f).id();return Json.write(response(valid(),List.of()));};
    process(old);assertThat(task(repairs.view(f.alert)).get("jobId").toString()).isEqualTo(newer[0].toString());
    assertThat(task(repairs.view(f.alert)).get("state")).isEqualTo("QUEUED");assertThat(store.get(Alert.class,f.alert).proposal).isNull();
  }

  @Test void replayIsIdempotentButAnExplicitNewRequestMakesAFreshProviderCall()throws Exception {
    var f=fixture();var first=request(f);process(first);String proposal=store.get(Alert.class,f.alert).proposal;
    worker.process(first);assertThat(provider.calls).isEqualTo(1);assertThat(store.get(Alert.class,f.alert).proposal).isEqualTo(proposal);
    var second=request(f);assertThat(second.id()).isNotEqualTo(first.id());process(second);assertThat(provider.calls).isEqualTo(2);assertNoMoney();
  }

  @Test void oldCompletedRepairJobsAndProposalsRemainVisible()throws Exception {
    var f=fixture();var claim=request(f);var proposal=response(valid(),List.of());proposal.put("documentVersion",store.get(SourceDocument.class,f.doc).version);
    store.get(Alert.class,f.alert).proposal=Json.write(proposal);store.flush();
    jdbc.update("update job set state='DONE',payload='{}' where id=?",claim.id());
    assertThat(task(repairs.view(f.alert))).containsEntry("outcome","READY").containsEntry("canApply",true);
    jdbc.update("update job set payload='{\"outcome\":\"STALE\"}' where id=?",claim.id());
    assertThat(task(repairs.view(f.alert))).containsEntry("outcome","STALE").containsEntry("canApply",false);
  }

  @Test void modelOutageRemainsWaitingAndCanBeRetried()throws Exception {
    var f=fixture();var raw=request(f);var claim=queue.claim().orElseThrow();assertThat(claim.id()).isEqualTo(raw.id());
    provider.program=_-> {throw new OllamaProvider.AiUnavailable("Modelo indisponível",null);};
    assertThatThrownBy(()->worker.process(claim)).isInstanceOf(OllamaProvider.AiUnavailable.class);
    queue.finish(claim,"Modelo indisponível",true);
    assertThat(task(repairs.view(f.alert))).containsEntry("state","WAITING").containsEntry("attempts",0);
    queue.retry(claim.id());assertThat(task(repairs.view(f.alert)).get("state")).isEqualTo("QUEUED");assertNoMoney();
  }

  @Test void oversizedFullContextFailsBeforeAnyModelCallAndCannotLoopAutomatically()throws Exception {
    var f=fixture("X".repeat(60000),1,"{}");request(f);
    var enabled=org.mockito.Mockito.mock(AccountingProperties.class);
    org.mockito.Mockito.when(enabled.jobsEnabled()).thenReturn(true);
    new BackgroundWorker(enabled,queue,null,null,orchestrator,null,new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).tick();
    assertThat(task(repairs.view(f.alert)).get("state")).isEqualTo("FAILED");assertThat(queue.claim()).isEmpty();assertThat(provider.calls).isZero();assertNoMoney();
    assertThat(task(repairs.view(f.alert)).get("error").toString()).contains("não foi cortado");
  }

  @Test void structuredOutputFailuresStayVisibleAndNeverPublishAProposal()throws Exception {
    var f=fixture();request(f);var first=queue.claim().orElseThrow();
    var claim=new JobQueue.Claim(first.id(),first.kind(),first.target(),first.revision(),first.payload(),4,first.token());
    provider.program=_->"{\"draft\":\"inválido\"}";
    assertThatThrownBy(()->worker.process(claim)).isInstanceOf(IllegalArgumentException.class);
    queue.finish(claim,"Resposta estruturada inválida",false);
    assertThat(task(repairs.view(f.alert))).containsEntry("state","FAILED").containsEntry("error","Resposta estruturada inválida");
    assertThat(store.get(Alert.class,f.alert).proposal).isNull();assertNoMoney();
  }

  @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
  void simultaneousRequestsCreateOnlyOneJob()throws Exception {
    var f=transactions.execute(_->{try{return fixture();}catch(Exception e){throw new RuntimeException(e);}});
    try(var pool=Executors.newFixedThreadPool(2)) {
      var start=new CountDownLatch(1);
      Callable<String> request=()->{start.await();return task(repairs.request(f.alert)).get("jobId").toString();};
      var a=pool.submit(request);var b=pool.submit(request);start.countDown();
      assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
      assertThat(jdbc.queryForObject("select count(*) from job where target_id=?",Integer.class,f.alert)).isEqualTo(1);
    }finally{jdbc.update("update job set state='DONE' where target_id=?",f.alert);}
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentConcernsUseTheSameRecordAlert() throws Exception {
    UUID invoiceId = UUID.randomUUID();
    jdbc.update("insert into invoice(id, created_at, updated_at) values (?, now(), now())", invoiceId);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var extraction = pool.submit(() -> {
        start.await();
        return review.open(null, "invoice", invoiceId, "EXTRACTION_REVIEW", "Data ilegível").id;
      });
      var verification = pool.submit(() -> {
        start.await();
        return review.open(null, "invoice", invoiceId, "AI_CONCERN", "Total incerto").id;
      });
      start.countDown();
      UUID alertId = extraction.get(20, TimeUnit.SECONDS);
      assertThat(verification.get(20, TimeUnit.SECONDS)).isEqualTo(alertId);
      assertThat((List<?>) repairs.view(alertId).get("concerns")).hasSize(2);
    } finally {
      jdbc.update("delete from audit_event where entity_id in (select id from alert where entity_id=?)", invoiceId);
      jdbc.update("delete from alert where entity_id=?", invoiceId);
      jdbc.update("delete from invoice where id=?", invoiceId);
    }
  }
}
