package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static pt.conta.shared.Inputs.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.ai.*;
import pt.conta.bank.*;
import pt.conta.category.*;
import pt.conta.invoice.*;
import pt.conta.jobs.*;
import pt.conta.ledger.*;
import pt.conta.reconciliation.ExclusionRequest;
import pt.conta.review.RepairService;
import pt.conta.review.ReviewService;
import pt.conta.shared.*;

/** Exercises the actual schema, independent revisions, taxonomy inverses and provider contract. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class CategoryIntegrationTest {
  static EmbeddedPostgres postgres;
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
    postgres = EmbeddedPostgres.builder().setPort(0).start();
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "postgres"); registry.add("spring.datasource.password", () -> "postgres");
    registry.add("accounting.storage", () -> System.getProperty("java.io.tmpdir") + "/conta-category-tests");
  }
  @TestConfiguration static class Providers {
    @Bean @Primary FakeProvider categoryProvider() { return new FakeProvider(); }
  }
  static class FakeProvider implements AiProvider {
    int calls;
    Function<Request, String> program;
    public List<Model> models() { return List.of(new Model("qwen3.5:9b", "test-categories", false, true, true)); }
    public Completion complete(Request request) {
      calls++;
      return new Completion(program.apply(request), new Usage(100, 30, 1000, CapacitySource.CONFIGURED, "stop", null));
    }
  }
  @Autowired BookkeepingService book;
  @Autowired CategoryService categories;
  @Autowired CategoryAiWorker worker;
  @Autowired Store store;
  @Autowired LedgerService ledger;
  @Autowired JdbcTemplate jdbc;
  @Autowired FakeProvider provider;
  @Autowired pt.conta.api.FinanceController finance;
  @Autowired JobQueue jobs;
  @Autowired RepairService repairs;
  @Autowired ReviewService review;
  UUID account;

  @BeforeEach void setup() {
    var a = new BankAccount(); a.name = "Categorias"; account = store.add(a).id;
    provider.calls = 0; provider.program = request -> Json.write(result(List.of(), List.of()));
  }
  private UUID invoice(String description) { return invoice(description, "100.00"); }
  private UUID invoice(String description, String amount) {
    return (UUID) book.saveInvoice(null, map("issuedOn", "2026-01-01", "supplier", "Fornecedor de teste",
        "number", UUID.randomUUID().toString(), "description", description, "total", amount,
        "validate", true)).get("id");
  }
  private UUID movement(String description, String amount) {
    return (UUID) book.saveMovement(null, map("accountId", account.toString(), "bookedOn", "2026-01-02",
        "description", description, "amount", amount, "direction", "DEBIT", "validate", true)).get("id");
  }
  private JobQueue.Claim classificationJob(String kind, UUID id) {
    var row = jdbc.queryForMap(
        "select id,target_version,payload from job where kind=? and target_id=? order by target_version desc limit 1",
        kind, id);
    return new JobQueue.Claim((UUID) row.get("id"), kind, id, ((Number) row.get("target_version")).longValue(),
        (String) row.get("payload"), 0);
  }
  private UUID reference(String code) { return store.all(Category.class).stream().filter(c -> code.equals(c.referenceCode)).findFirst().orElseThrow().id; }
  private long version(String type, UUID id) { return ((Number) categories.view(type,id).get("version")).longValue(); }
  private Map<String,Object> op(String kind, List<UUID> sources, String key, String name, UUID parent, UUID target, String evidence) {
    return map("kind",kind,"sourceIds",sources.stream().map(UUID::toString).toList(),"key",key,"name",name,
        "parentId",parent==null?null:parent.toString(),"targetId",target==null?null:target.toString(),
        "description",name,"evidence",evidence,"reason","Conceito abrangente sustentado pelos artigos");
  }
  private Map<String,Object> result(List<Map<String,Object>> labels, List<Map<String,Object>> ops) {
    return map("status","CLASSIFIED","explanation","Artigos e descrição verificados","categories",labels,"operations",ops);
  }
  private Map<String,Object> label(String id,String evidence) { return map("id",id,"evidence",evidence); }
  private void classify(String type, UUID id, UUID category, String evidence) {
    assertThat(categories.apply(categories.snapshot(type,id), result(List.of(label(category.toString(),evidence)),List.of()),UUID.randomUUID())).isTrue();
  }
  private List<String> names(String type,UUID id) { return categories.details(type,id).stream().map(c->c.get("name").toString()).toList(); }
  private UUID automaticUsbC(UUID invoice) {
    categories.apply(categories.snapshot("invoice",invoice), result(List.of(label("new:usbc","cabo USB-C")),
        List.of(op("CREATE",List.of(),"new:usbc","Cabos USB-C",reference("623"),null,"cabo USB-C"))),UUID.randomUUID());
    return (UUID) categories.details("invoice",invoice).getFirst().get("id");
  }

  @Test void usbVariantsPromoteHistoryAndUndoPreservesLaterUsbAWithoutTouchingMoney() {
    UUID first=invoice("Compra de cabo USB-C"); long financialVersion=store.get(Invoice.class,first).version;
    UUID original=automaticUsbC(first);
    UUID second=invoice("Compra de cabo USB-A");
    int postings=store.all(LedgerEntry.class).size();
    categories.apply(categories.snapshot("invoice",second), result(List.of(label("new:usb","cabo USB-A")),
        List.of(op("GENERALIZE",List.of(original),"new:usb","Cabos USB",reference("623"),null,"cabo USB-A"))),UUID.randomUUID());
    UUID broad=(UUID) categories.details("invoice",second).getFirst().get("id");
    assertThat(names("invoice",first)).containsExactly("Cabos USB");
    assertThat(names("invoice",second)).containsExactly("Cabos USB");
    assertThat(store.get(Category.class,original).mergedIntoId).isEqualTo(broad);
    assertThat(store.all(Category.class).stream().filter(c->c.name.equals("Cabos USB-A"))).isEmpty();
    assertThat(store.get(Invoice.class,first).version).isEqualTo(financialVersion);
    assertThat(store.all(LedgerEntry.class)).hasSize(postings);
    assertThat(ledger.year(2026).getFirst().get("closing")).isEqualTo("200.00");
    var change=store.all(CategoryChange.class).stream().filter(c->c.kind.equals("GENERALIZE")).findFirst().orElseThrow();
    categories.undo(change.id,change.version);
    assertThat(names("invoice",first)).containsExactly("Cabos USB-C");
    assertThat(names("invoice",second)).containsExactly("Cabos USB");
    assertThat(store.get(Category.class,original).protectedCategory).isTrue();
    assertThat(store.get(Category.class,broad).protectedCategory).isTrue();
    assertThat(store.all(LedgerEntry.class)).hasSize(postings);
  }

  @Test void familiesFilterDescendantsAndMultipleLabelsNeverDuplicateARecord() {
    UUID id=invoice("Papel A4 e seguro multirriscos");
    categories.apply(categories.snapshot("invoice",id),result(List.of(label(reference("6233").toString(),"Papel A4"),
        label(reference("6263").toString(),"seguro multirriscos")),List.of()),UUID.randomUUID());
    assertThat(names("invoice",id)).containsExactlyInAnyOrder("Material de escritório","Seguros");
    assertThat(finance.invoices(Map.of("category",reference("623").toString())).get("total")).isEqualTo(1);
    assertThat(finance.entries(Map.of("category",reference("626").toString())).items()).hasSize(1);
    assertThat(store.get(Invoice.class,id).manuallyConfirmed).isTrue();
  }

  @Test void groupedInheritanceManualRejectionsAndUnallocationRespectTheirOwnSources() {
    UUID a=invoice("Seguro","50.00"),b=invoice("Papel A4","50.00"),m=movement("Pagamento agrupado","100.00");
    classify("invoice",a,reference("6263"),"Seguro"); classify("invoice",b,reference("6233"),"Papel A4");
    var aa=book.allocate(map("invoiceId",a.toString(),"movementId",m.toString(),"amount","50.00"));
    var bb=book.allocate(map("invoiceId",b.toString(),"movementId",m.toString(),"amount","50.00"));
    assertThat(names("movement",m)).containsExactlyInAnyOrder("Seguros","Material de escritório");
    assertThat(categories.view("movement",m).get("state")).isEqualTo("INHERITED");
    var validation = finance.movement(m);
    validation.remove("categories");
    validation.put("validate", true);
    assertThat(rows(finance.editMovement(m, validation), "categoryDetails")).allSatisfy(tag ->
        assertThat(tag).containsEntry("origins", List.of("INHERITED")));
    UUID project=(UUID)categories.save(map("name","Projeto interno","aliases",List.of())).get("id");
    categories.manual("movement",m,version("movement",m),List.of(project),false);
    assertThat(names("movement",m)).containsExactly("Projeto interno");
    book.unallocate((UUID)aa.get("id"));book.unallocate((UUID)bb.get("id"));
    assertThat(names("movement",m)).containsExactly("Projeto interno");
    assertThat(categories.details("movement",m).getFirst().get("origins")).isEqualTo(List.of("MANUAL"));
    assertThat(ledger.year(2026).getFirst().get("closing")).isEqualTo("0.00");
  }

  @Test void rejectedLabelsStayRejectedUntilExplicitlyAllowingAi() {
    UUID id=movement("Seguro anual","100.00");
    classify("movement",id,reference("6263"),"Seguro anual");
    categories.manual("movement",id,version("movement",id),List.of(),false);
    classify("movement",id,reference("6263"),"Seguro anual");
    assertThat(names("movement",id)).isEmpty();
    categories.manual("movement",id,version("movement",id),List.of(),true);
    classify("movement",id,reference("6263"),"Seguro anual");
    assertThat(names("movement",id)).containsExactly("Seguros");
  }

  @Test void manualNamesAreProtectedAndStaleAiResultsAreDiscarded() {
    UUID id=invoice("Compra de cabo USB-C");UUID category=automaticUsbC(id);
    var snapshot=categories.snapshot("catalog",CategoryCatalog.ID);
    var current=store.get(Category.class,category);
    categories.save(map("id",category.toString(),"version",current.version,"name","Cablagem USB"));
    assertThat(categories.apply(snapshot,result(List.of(),List.of(op("GENERALIZE",List.of(category),"new:all","Material informático",reference("623"),null,"cabo USB-C"))),UUID.randomUUID())).isFalse();
    assertThat(current.name).isEqualTo("Cablagem USB");assertThat(current.protectedCategory).isTrue();
  }

  @Test void protectedReferenceCategoriesCannotBeMovedByAi() {
    UUID id=invoice("Papel A4");var snapshot=categories.snapshot("invoice",id);
    assertThatThrownBy(()->categories.apply(snapshot,result(List.of(),List.of(op("MOVE",List.of(reference("6233")),null,null,reference("626"),null,"Papel A4"))),UUID.randomUUID()))
        .isInstanceOf(DomainException.class).hasMessageContaining("protegidas");
    assertThat(store.get(Category.class,reference("6233")).parentId).isEqualTo(reference("623"));
  }

  @Test void excludedAndOwnerValidatedRecordsStillGetAiCategories() {
    UUID id=movement("Contabilidade Janeiro","100.00");var m=store.get(BankMovement.class,id);
    book.exclude(new ExclusionRequest(true,List.of(new ExclusionRequest.Item("movement",id,m.version)),null));
    classify("movement",id,reference("6221"),"Contabilidade Janeiro");
    assertThat(names("movement",id)).containsExactly("Trabalhos especializados");
    assertThat(categories.view("movement",id).get("state")).isEqualTo("PROVISIONAL");
    assertThat(m.excluded).isTrue();assertThat(m.manuallyConfirmed).isTrue();
    assertThat(ledger.year(2026).getFirst().get("closing")).isEqualTo("0.00");
  }

  @Test void categoryChangesDoNotInvalidateOwnerFinancialEditsAndCyclesAreRejected() {
    UUID id=invoice("Seguro anual");var invoice=store.get(Invoice.class,id);long version=invoice.version;
    categories.manual("invoice",id,version("invoice",id),List.of(reference("6263")),false);
    assertThat(invoice.version).isEqualTo(version);
    var input=new LinkedHashMap<>(book.invoiceView(invoice));input.remove("categories");input.put("validate",true);input.put("description","Seguro corrigido");
    book.saveInvoice(id,input);assertThat(invoice.description).isEqualTo("Seguro corrigido");
    var parent=store.get(Category.class,reference("623"));
    assertThatThrownBy(()->categories.save(map("id",parent.id.toString(),"version",parent.version,"parentId",reference("6233").toString())))
        .isInstanceOf(DomainException.class).hasMessageContaining("ciclo");
  }

  @Test void validatingAnInvoiceKeepsConfirmedLabelsAndEvidenceThroughReclassification() {
    UUID id = invoice("Papel A4 e Seguro anual");
    UUID insurance = reference("6263");
    UUID manual = reference("6221");
    UUID materials = reference("623");
    classify("invoice", id, insurance, "Seguro anual");
    categories.manual("invoice", id, version("invoice", id), List.of(manual), false);
    classify("invoice", id, materials, "Papel A4");
    requestInvoiceCategoryReview(id);
    var stale = categories.snapshot("invoice", id);
    var input = finance.invoice(id);
    input.put("validate", true);
    input.put("description", "Papel A4 e Seguro anual corrigido");
    input.put("categoryConfirmation", confirmation("invoice", id, materials));

    var saved = finance.editInvoice(id, input);
    assertThat(saved).containsEntry("description", "Papel A4 e Seguro anual corrigido");
    assertThat(rows(saved, "categoryDetails")).filteredOn(tag -> materials.equals(tag.get("id")))
        .singleElement().satisfies(tag -> assertThat(tag.get("origins")).isEqualTo(List.of("AI", "MANUAL")));
    assertThat(categories.apply(stale, result(List.of(), List.of()), UUID.randomUUID())).isFalse();

    provider.program = request -> Json.write(result(List.of(label(reference("6233").toString(), "Papel A4"),
        label(insurance.toString(), "Seguro anual")), List.of()));
    worker.process(classificationJob("CLASSIFY_INVOICE", id));
    var labels = rows(finance.invoice(id), "categoryDetails");
    assertThat(labels).extracting(tag -> tag.get("id")).containsExactlyInAnyOrder(materials, manual, insurance);
    assertThat(labels).filteredOn(tag -> materials.equals(tag.get("id"))).singleElement().satisfies(tag -> {
      assertThat(tag).containsEntry("origins", List.of("MANUAL"));
      assertThat(rows(tag, "evidence")).singleElement().satisfies(evidence -> assertThat(evidence)
          .containsEntry("reason", "Papel A4").containsEntry("origin", "MANUAL")
          .hasEntrySatisfying("executionId", execution -> assertThat(execution).isNotNull()));
    });
    assertThat(categories.view("invoice", id)).containsEntry("rejectedCategoryIds", List.of());
  }

  @Test void aMovementDraftLeavesAiLabelsAutomaticUntilValidation() {
    UUID id = (UUID) finance.createMovement(map("accountId", account, "bookedOn", "2026-01-02",
        "description", "Seguro anual", "amount", "100.00", "direction", "DEBIT")).get("id");
    UUID insurance = reference("6263");
    classify("movement", id, insurance, "Seguro anual");
    var input = finance.movement(id);
    input.put("categoryConfirmation", confirmation("movement", id, insurance));
    var draft = finance.editMovement(id, input);
    assertThat(draft).containsEntry("manuallyConfirmed", false);
    assertThat(categories.view("movement", id)).containsEntry("state", "PROVISIONAL");

    draft.put("validate", true);
    draft.put("categoryConfirmation", confirmation("movement", id, insurance));
    assertThat(finance.editMovement(id, draft)).containsEntry("manuallyConfirmed", true);
    var snapshot = categories.snapshot("movement", id);
    assertThat(categories.apply(snapshot, result(List.of(), List.of()), UUID.randomUUID())).isTrue();
    assertThat(rows(finance.movement(id), "categoryDetails")).singleElement().satisfies(tag ->
        assertThat(tag).containsEntry("id", insurance).containsEntry("origins", List.of("MANUAL")));
  }

  @Test void aStaleCategoryConfirmationLeavesCurrentRecordAndLabelsIntact() {
    UUID id = invoice("Papel A4 e Seguro anual");
    UUID insurance = reference("6263");
    UUID office = reference("6233");
    classify("invoice", id, insurance, "Seguro anual");
    var before = finance.invoice(id);
    var edit = new LinkedHashMap<>(before);
    edit.put("validate", true);
    edit.put("description", "Editado no editor");
    edit.put("categoryConfirmation", confirmation("invoice", id, insurance));
    requestInvoiceCategoryReview(id);
    classify("invoice", id, office, "Papel A4");

    assertThatThrownBy(() -> finance.editInvoice(id, edit)).isInstanceOfSatisfying(DomainException.class,
        error -> assertThat(error.status).isEqualTo(409));
    assertThat(finance.invoice(id)).containsEntry("description", before.get("description"))
        .containsEntry("version", before.get("version"));
    assertThat(categories.details("invoice", id)).singleElement().satisfies(tag ->
        assertThat(tag).containsEntry("id", office).containsEntry("origins", List.of("AI")));
  }

  @Test void resolvingAnInvoiceAlertConfirmsItsVisibleAiLabel() {
    var draft = finance.createInvoice(map("supplier", "Fornecedor de teste", "description", "Seguro anual",
        "total", "100.00"));
    UUID id = (UUID) draft.get("id");
    var alert = review.openFor("invoice", id).orElseThrow();
    UUID insurance = reference("6263");
    classify("invoice", id, insurance, "Seguro anual");
    draft.put("issuedOn", "2026-01-01");

    var saved = repairs.saveRecordDraft(alert.id, map("alertVersion", alert.version,
        "recordVersion", draft.get("version"), "resolve", true, "draft", draft,
        "categoryConfirmation", confirmation("invoice", id, insurance)));
    assertThat(alert.status).isEqualTo("RESOLVED");
    assertThat(saved).containsEntry("manuallyConfirmed", true);
    assertThat(rows(saved, "categoryDetails")).singleElement().satisfies(tag ->
        assertThat(tag).containsEntry("id", insurance).containsEntry("origins", List.of("AI", "MANUAL")));
  }

  private Map<String, Object> confirmation(String type, UUID id, UUID categoryId) {
    return map("version", version(type, id), "categoryIds", List.of(categoryId.toString()));
  }

  private void requestInvoiceCategoryReview(UUID id) {
    jdbc.update("update job set state='DONE' where id=?", classificationJob("CLASSIFY_INVOICE", id).id());
    categories.requestInvoiceClassification(id, version("invoice", id));
  }

  @Test void archivePreservesHistoryAndPreventsNewAssignments() {
    UUID id=invoice("Projeto");var c=categories.save(map("name","Projeto arquivado","aliases",List.of()));UUID category=(UUID)c.get("id");
    categories.manual("invoice",id,version("invoice",id),List.of(category),false);
    categories.archive(category,store.get(Category.class,category).version,false);
    assertThat(categories.details("invoice",id).getFirst().get("archived")).isEqualTo(true);
    assertThat(categories.catalog(false)).noneMatch(row->row.get("id").equals(category));
  }

  @Test void workerUsesVersionedReferenceAndGroundedSchemaForManualInvoices() {
    UUID id=invoice("Seguro anual");
    provider.program=request->{
      assertThat(request.instructions()).contains("USB-A","protected=true");
      assertThat(Json.read(request.input()).path("reference").path("version").asText()).isEqualTo("OCC-SNC-2019-02-EXPENSES-2");
      return Json.write(result(List.of(label(reference("6263").toString(),"Seguro anual")),List.of()));
    };
    var job = classificationJob("CLASSIFY_INVOICE", id);
    worker.process(job);
    assertThat(names("invoice",id)).containsExactly("Seguros");assertThat(provider.calls).isEqualTo(1);
    assertThat(store.all(AiExecution.class)).singleElement().satisfies(execution -> {
      assertThat(execution.jobId).isEqualTo(job.id());
      assertThat(execution.model).isEqualTo("qwen3.5:9b");
      assertThat(execution.usage().responseTokens()).isEqualTo(30);
    });
    worker.process(new JobQueue.Claim(UUID.randomUUID(),"NORMALIZE_CATEGORIES",id,0,"{}",0));
    assertThat(provider.calls).isEqualTo(1);
  }

  @Test void insufficientEvidenceAndInvalidJsonDoNotInventLabels() {
    UUID id=movement("Transferência","100.00");
    provider.program=request->Json.write(map("status","REVIEW","explanation","Descrição insuficiente","categories",List.of(),"operations",List.of()));
    worker.process(classificationJob("CLASSIFY_MOVEMENT", id));
    assertThat(names("movement",id)).isEmpty();assertThat(categories.view("movement",id).get("state")).isEqualTo("REVIEW");
    UUID another=invoice("Papel");provider.program=request->"{\"categories\":\"inventado\"}";
    assertThatThrownBy(()->worker.process(classificationJob("CLASSIFY_INVOICE", another))).isInstanceOf(IllegalArgumentException.class);
    assertThat(names("invoice",another)).isEmpty();
    assertThat(store.all(AiExecution.class).stream().anyMatch(e->"INVALID".equals(e.outcome))).isTrue();
  }

  @Test void obsoleteModelOutputCannotOverrideConcurrentManualChoices() {
    UUID id=invoice("Seguro anual e Papel");
    provider.program=request->{categories.manual("invoice",id,version("invoice",id),List.of(reference("6233")),false);
      return Json.write(result(List.of(label(reference("6263").toString(),"Seguro anual")),List.of()));};
    worker.process(classificationJob("CLASSIFY_INVOICE", id));
    assertThat(names("invoice",id)).containsExactly("Material de escritório");
    assertThat(store.all(AiExecution.class).getFirst().outcome).isEqualTo("STALE");
  }

  @Test void genericBankReferencesNeverBecomeInventedExpenseCategories() {
    UUID id=movement("TRANSFERENCIA 001","100.00");
    provider.program=request->Json.write(result(List.of(label(reference("681").toString(),"TRANSFERENCIA 001")),List.of()));
    worker.process(classificationJob("CLASSIFY_MOVEMENT", id));
    assertThat(names("movement",id)).isEmpty();
    assertThat(categories.view("movement",id).get("state")).isEqualTo("REVIEW");
    assertThat(store.all(AiExecution.class).getFirst().outcome).isEqualTo("GUARDED_REVIEW");
  }

  @Test void workerRepairsBroadCategoryCreationBeforeApplyingAnyCatalogChange() {
    UUID first=invoice("Compra de cabo USB-C"),original=automaticUsbC(first);
    UUID id=invoice("Compra de cabo USB-A");
    provider.program=request->{
      boolean repair=Json.read(request.input()).has("validationFeedback");
      if(repair) assertThat(request.input()).contains("GENERALIZE_REQUIRED",original.toString(),
          "covers existing categories", "Correct the indicated error");
      return Json.write(result(List.of(label("new:usb","cabo USB-A")),List.of(
          op(repair?"GENERALIZE":"CREATE",repair?List.of(original):List.of(),"new:usb","Cabos USB",reference("623"),null,"cabo USB-A"))));
    };
    worker.process(classificationJob("CLASSIFY_INVOICE", id));
    assertThat(provider.calls).isEqualTo(2);
    assertThat(names("invoice",first)).containsExactly("Cabos USB");
    assertThat(names("invoice",id)).containsExactly("Cabos USB");
    assertThat(store.all(Category.class).stream().filter(c->c.name.equals("Cabos USB"))).hasSize(1);
    assertThat(store.all(AiExecution.class).stream().map(e->e.outcome)).containsExactlyInAnyOrder("INVALID_DOMAIN","VALID");
  }

  @Test void workerSuppliesLiteralEvidenceWhenTheModelJoinsSeparateDescriptions() {
    UUID first=invoice("Compra de cabo USB-C"),original=automaticUsbC(first);
    UUID id=invoice("Compra de cabo USB-A");
    provider.program=request->{
      var feedback=Json.read(request.input()).path("validationFeedback");
      boolean repair=!feedback.isMissingNode();
      if(repair) assertThat(feedback.path("literalEvidenceExamples").toString()).contains("Compra de cabo USB-A");
      return Json.write(result(List.of(label("new:usb","cabo USB-A")),List.of(
          op("GENERALIZE",List.of(original),"new:usb","Cabos USB",reference("623"),null,
              repair?"Compra de cabo USB-A":"cabo USB-C e cabo USB-A"))));
    };
    worker.process(classificationJob("CLASSIFY_INVOICE", id));
    assertThat(provider.calls).isEqualTo(2);
    assertThat(names("invoice",first)).containsExactly("Cabos USB");
    assertThat(names("invoice",id)).containsExactly("Cabos USB");
  }

  @Test void aDifferentConnectorVariantCannotBeAssignedTheOldSpecificCategory() {
    UUID first=invoice("Compra de cabo USB-C"),original=automaticUsbC(first),id=invoice("Compra de cabo USB-A");
    provider.program=request->{
      if(!Json.read(request.input()).has("validationFeedback"))
        return Json.write(result(List.of(label(original.toString(),"cabo USB-A")),List.of()));
      assertThat(request.input()).contains("VARIANT_MISMATCH","Cabos USB", "Do not assign", "assign categories.id='new:general'");
      return Json.write(result(List.of(label("new:usb","cabo USB-A")),List.of(
          op("GENERALIZE",List.of(original),"new:usb","Cabos USB",reference("623"),null,"cabo USB-A"))));
    };
    worker.process(classificationJob("CLASSIFY_INVOICE", id));
    assertThat(provider.calls).isEqualTo(2);
    assertThat(names("invoice",first)).containsExactly("Cabos USB");
    assertThat(names("invoice",id)).containsExactly("Cabos USB");
  }

  @Test void creatingACableFamilyKeepsUsefulChildrenInsteadOfMergingThem() {
    UUID first=invoice("Compra de cabo USB-C"),original=automaticUsbC(first),id=invoice("Compra de cabo HDMI");
    var family=op("CREATE",List.of(),"new:family","Cabos",reference("623"),null,"cabo HDMI");
    var moved=op("MOVE",List.of(original),null,null,null,null,"cabo USB-C");moved.put("parentId","new:family");
    var hdmi=op("CREATE",List.of(),"new:hdmi","Cabos HDMI",null,null,"cabo HDMI");hdmi.put("parentId","new:family");
    categories.apply(categories.snapshot("invoice",id),result(List.of(label("new:hdmi","cabo HDMI")),List.of(family,moved,hdmi)),UUID.randomUUID());
    assertThat(names("invoice",first)).containsExactly("Cabos USB-C");
    assertThat(names("invoice",id)).containsExactly("Cabos HDMI");
    assertThat(store.get(Category.class,original).mergedIntoId).isNull();
    assertThat(categories.details("invoice",first).getFirst().get("path")).isEqualTo("Fornecimentos e serviços externos › Materiais › Cabos › Cabos USB-C");
  }

  @Test void aGenericServicesFamilyIsNotAnExpenseClassificationByItself() {
    UUID id=movement("Fornecedor não identificado","100.00");
    provider.program=request->{
      if(Json.read(request.input()).has("validationFeedback")) {
        assertThat(request.input()).contains("GENERIC_FAMILY", "Choose a specific expense type");
        return Json.write(map("status","REVIEW","explanation","Não há natureza identificável","categories",List.of(),"operations",List.of()));
      }
      return Json.write(result(List.of(label(reference("626").toString(),"Fornecedor não identificado")),List.of()));
    };
    worker.process(classificationJob("CLASSIFY_MOVEMENT", id));
    assertThat(provider.calls).isEqualTo(2);
    assertThat(names("movement",id)).isEmpty();
    assertThat(categories.view("movement",id).get("state")).isEqualTo("REVIEW");
  }

  @Test void unavailableAndFailedJobsHaveVisibleClassificationStates() {
    UUID waiting=store.add(new Invoice()).id,failed=store.add(new BankMovement()).id;
    var si=new Classification();si.invoiceId=waiting;store.add(si);
    var sm=new Classification();sm.movementId=failed;store.add(sm);store.flush();
    jobs.enqueue("CLASSIFY_INVOICE",waiting,0,"{}");jobs.enqueue("CLASSIFY_MOVEMENT",failed,0,"{}");
    jdbc.update("update job set state='WAITING' where kind='CLASSIFY_INVOICE' and target_id=?",waiting);
    jdbc.update("update job set state='FAILED' where kind='CLASSIFY_MOVEMENT' and target_id=?",failed);
    UUID retried=store.add(new Invoice()).id;var sr=new Classification();sr.invoiceId=retried;sr.generation=2;store.add(sr);store.flush();
    jobs.enqueue("CLASSIFY_INVOICE",retried,1,"{}");jobs.enqueue("CLASSIFY_INVOICE",retried,2,"{}");
    jdbc.update("update job set state='WAITING' where target_id=? and target_version=1",retried);
    assertThat(categories.view("invoice",waiting).get("state")).isEqualTo("WAITING");
    assertThat(categories.view("movement",failed).get("state")).isEqualTo("REVIEW");
    assertThat(categories.view("invoice",retried).get("state")).isEqualTo("PENDING");
  }

  @Test void aManualMergeProtectsItsPreviouslyAutomaticDestination() {
    UUID id=invoice("Compra de cabo USB-C"),target=automaticUsbC(id);
    UUID source=(UUID)categories.save(map("name","Cabos personalizados","aliases",List.of())).get("id");
    categories.merge(map("sourceIds",List.of(source.toString()),"targetId",target.toString(),"items",List.of(
        map("id",source.toString(),"version",store.get(Category.class,source).version),
        map("id",target.toString(),"version",store.get(Category.class,target).version))));
    assertThat(store.get(Category.class,target).protectedCategory).isTrue();
    assertThat(store.get(Category.class,source).protectedCategory).isTrue();
    assertThat(names("invoice",id)).containsExactly("Cabos USB-C");
  }

  @Test void undoPreservesManualRemovalAndBroaderChoicesMadeAfterTheEvolution() {
    UUID first=invoice("Compra de cabo USB-C"),original=automaticUsbC(first);
    UUID third=invoice("Compra de cabo USB-C");classify("invoice",third,original,"cabo USB-C");
    UUID later=invoice("Compra de cabo USB-A");
    categories.apply(categories.snapshot("invoice",later),result(List.of(label("new:usb","cabo USB-A")),List.of(
        op("GENERALIZE",List.of(original),"new:usb","Cabos USB",reference("623"),null,"cabo USB-A"))),UUID.randomUUID());
    UUID broad=(UUID)categories.details("invoice",later).getFirst().get("id");
    categories.manual("invoice",first,version("invoice",first),List.of(),false);
    categories.manual("invoice",third,version("invoice",third),List.of(broad),false);
    var change=store.all(CategoryChange.class).stream().filter(c->c.kind.equals("GENERALIZE")).findFirst().orElseThrow();
    categories.undo(change.id,change.version);
    assertThat(names("invoice",first)).isEmpty();
    assertThat(names("invoice",third)).containsExactly("Cabos USB");
    assertThat(names("invoice",later)).containsExactly("Cabos USB");
  }

  @Test void parallelCatalogSnapshotsCannotCreateDuplicateGeneralizations() {
    UUID first=invoice("Compra de cabo USB-C"),original=automaticUsbC(first);
    UUID a=invoice("Compra de cabo USB-A"),b=invoice("Compra de cabo USB-A");
    var sa=categories.snapshot("invoice",a);var sb=categories.snapshot("invoice",b);
    var proposal=result(List.of(label("new:usb","cabo USB-A")),List.of(
        op("GENERALIZE",List.of(original),"new:usb","Cabos USB",reference("623"),null,"cabo USB-A")));
    assertThat(categories.apply(sa,proposal,UUID.randomUUID())).isTrue();
    assertThat(categories.apply(sb,proposal,UUID.randomUUID())).isFalse();
    UUID general=(UUID)categories.details("invoice",a).getFirst().get("id");
    classify("invoice",b,general,"cabo USB-A");
    assertThat(store.all(Category.class).stream().filter(c->c.name.equals("Cabos USB"))).hasSize(1);
    assertThat(names("invoice",first)).containsExactly("Cabos USB");
    assertThat(names("invoice",b)).containsExactly("Cabos USB");
  }

  @Test void catalogEditDuringProviderCallRequeuesPendingClassification() {
    UUID id=invoice("Seguro anual");
    long before=jdbc.queryForObject("select count(*) from job where kind='CLASSIFY_INVOICE' and target_id=?",Long.class,id);
    provider.program=request->{
      categories.save(map("name","Projeto manual","aliases",List.of()));
      return Json.write(result(List.of(label(reference("6263").toString(),"Seguro anual")),List.of()));
    };
    worker.process(classificationJob("CLASSIFY_INVOICE", id));
    assertThat(names("invoice",id)).isEmpty();
    assertThat(store.all(AiExecution.class).getFirst().outcome).isEqualTo("STALE");
    assertThat(jdbc.queryForObject("select count(*) from job where kind='CLASSIFY_INVOICE' and target_id=?",Long.class,id)).isEqualTo(before+1);
  }

  @Test void batchRequestsOnlyOneCatalogReview() {
    invoice("Seguro anual");
    categories.reviewWhenSettled(UUID.randomUUID());
    assertThat(jdbc.queryForObject("select count(*) from job where kind='REFINE_CATEGORIES'",Long.class)).isZero();
    jdbc.update("update job set state='DONE' where kind='CLASSIFY_INVOICE'");
    categories.reviewWhenSettled(UUID.randomUUID());categories.reviewWhenSettled(UUID.randomUUID());
    assertThat(jdbc.queryForObject("select count(*) from job where kind='REFINE_CATEGORIES'",Long.class)).isEqualTo(1);

  }

  @Test void catalogAndExpenseRequestsHaveSeparatePromptsAndTheSameStructuredSchema() {
    UUID id = invoice("Papel A4 e seguro multirriscos");
    provider.program = request -> {
      var input = Json.object(request.input());
      assertThat(request.images()).isEmpty();
      assertThat(request.requireFullContext()).isTrue();
      assertThat(input.get("responseSchema")).isEqualTo(request.schema());
      if ("REVIEW_CATALOG".equals(input.get("task"))) {
        assertThat(request.instructions()).contains("maintaining a Portuguese expense-label catalog");
        assertThat(Json.read(request.input()).path("examples").size()).isGreaterThan(0);
        return Json.write(result(List.of(), List.of()));
      }
      assertThat(request.instructions()).contains("experienced Portuguese accountant");
      assertThat(input).containsEntry("task", "CLASSIFY_EXPENSE").containsKeys("currentAssignments", "manualDecisions");
      return Json.write(result(List.of(label(reference("6233").toString(), "Papel A4"),
          label(reference("6263").toString(), "seguro multirriscos")), List.of()));
    };
    worker.process(classificationJob("CLASSIFY_INVOICE", id));
    worker.process(new JobQueue.Claim(UUID.randomUUID(), "REFINE_CATEGORIES", CategoryCatalog.ID,
        categories.revision(), "{}", 0));
    assertThat(names("invoice", id)).containsExactlyInAnyOrder("Material de escritório", "Seguros");
    assertThat(provider.calls).isEqualTo(2);
  }

  @Test void retryCoalescesRequestsAndCanRestoreARemovedCategory() {
    UUID id = invoice("Seguro anual");
    UUID insurance = reference("6263");
    classify("invoice", id, insurance, "Seguro anual");
    categories.manual("invoice", id, version("invoice", id), List.of(), false);
    long expected = version("invoice", id);
    var requested = categories.requestInvoiceClassification(id, expected);
    var job = classificationJob("CLASSIFY_INVOICE", id);
    categories.requestInvoiceClassification(id, expected);
    assertThat(classificationJob("CLASSIFY_INVOICE", id)).isEqualTo(job);
    assertThat(requested).containsEntry("state", "PENDING").containsEntry("jobState", "QUEUED");
    provider.program = request -> Json.write(result(List.of(label(insurance.toString(), "Seguro anual")), List.of()));
    worker.process(job);
    assertThat(categories.view("invoice", id)).containsEntry("state", "CLASSIFIED")
        .containsEntry("rejectedCategoryIds", List.of());
    assertThat(names("invoice", id)).containsExactly("Seguros");
  }

  @Test void reclassifyingAnInvoiceReplacesAiLabelsAndPreservesManualDecisions() {
    UUID id = invoice("Seguro anual, Papel A4, Contabilidade Janeiro e serviço telefónico");
    UUID insurance = reference("6263");
    UUID stationery = reference("6233");
    UUID accounting = reference("6221");
    UUID telephone = reference("6262");
    classify("invoice", id, stationery, "Papel A4");
    categories.manual("invoice", id, version("invoice", id), List.of(insurance), false);
    classify("invoice", id, accounting, "Contabilidade Janeiro");
    long financialVersion = store.get(Invoice.class, id).version;
    long expected = version("invoice", id);
    assertThat(categories.details("invoice", id)).extracting(tag -> tag.get("id"))
        .containsExactlyInAnyOrder(insurance, accounting);
    assertThatThrownBy(() -> categories.requestInvoiceClassification(id, expected - 1))
        .isInstanceOf(DomainException.class);

    var first = categories.requestInvoiceClassification(id, expected);
    var job = classificationJob("CLASSIFY_INVOICE", id);
    assertThat(categories.requestInvoiceClassification(id, expected)).containsEntry("version", first.get("version"));
    assertThat(classificationJob("CLASSIFY_INVOICE", id)).isEqualTo(job);
    assertThat(first).containsEntry("jobState", "QUEUED");

    provider.program = request -> Json.write(result(List.of(
        label(stationery.toString(), "Papel A4"), label(telephone.toString(), "serviço telefónico")), List.of()));
    worker.process(job);
    var tags = categories.details("invoice", id);
    assertThat(tags).extracting(tag -> tag.get("id")).containsExactlyInAnyOrder(insurance, stationery, telephone);
    assertThat(tags).anySatisfy(tag -> assertThat(tag).containsEntry("id", insurance)
        .containsEntry("origins", List.of("MANUAL")));
    assertThat(categories.view("invoice", id)).containsEntry("rejectedCategoryIds", List.of());
    assertThat(store.get(Invoice.class, id).version).isEqualTo(financialVersion);
  }

  @Test void aFailedJobCanBeRetriedAndAnOlderGenerationDoesNotCallTheModel() {
    UUID id = invoice("Papel A4");
    var old = classificationJob("CLASSIFY_INVOICE", id);
    jdbc.update("update job set state='FAILED',error=? where id=?", "A resposta do modelo é inválida.", old.id());
    var requested = categories.requestInvoiceClassification(id, version("invoice", id));
    var retry = classificationJob("CLASSIFY_INVOICE", id);
    assertThat(retry.revision()).isGreaterThan(old.revision());
    assertThat(requested).containsEntry("state", "PENDING");
    worker.process(old);
    assertThat(provider.calls).isZero();
    provider.program = request -> Json.write(result(List.of(label(reference("6233").toString(), "Papel A4")), List.of()));
    worker.process(retry);
    assertThat(names("invoice", id)).containsExactly("Material de escritório");
  }

  @Test void modelCannotUseHintsJoinedQuotesOrAnUnrelatedExpenseForPayroll() {
    UUID id = invoice("Papel A4 e seguro multirriscos");
    var state = store.all(Classification.class).stream().filter(s -> id.equals(s.invoiceId)).findFirst().orElseThrow();
    state.hints = Json.write(List.of("Subsídio de férias"));
    store.flush();
    var snapshot = categories.snapshot("invoice", id);
    assertThatThrownBy(() -> categories.apply(snapshot,
        result(List.of(label(reference("632").toString(), "Subsídio de férias")), List.of()), UUID.randomUUID()))
        .hasMessageContaining("sem evidência");
    assertThatThrownBy(() -> categories.apply(snapshot,
        result(List.of(label(reference("6233").toString(), "Papel A4; seguro multirriscos")), List.of()), UUID.randomUUID()))
        .hasMessageContaining("sem evidência");
    assertThatThrownBy(() -> categories.apply(snapshot,
        result(List.of(label(reference("632").toString(), "Papel A4")), List.of()), UUID.randomUUID()))
        .hasMessageContaining("evidência explícita");
    assertThat(names("invoice", id)).isEmpty();
  }

  @Test void longOcrIsMarkedPartialAndSharedDocumentsDoNotSupplyUnscopedEvidence() {
    var doc = new pt.conta.document.SourceDocument();
    doc.filename = "receipt.pdf";
    doc.kind = "INVOICE";
    doc.text = "Papel A4 " + "t".repeat(16_000);
    store.add(doc);
    UUID id = invoice("Papel A4");
    store.get(Invoice.class, id).documentId = doc.id;
    store.flush();
    var partial = Json.read(Json.write(categories.snapshot("invoice", id).input())).path("data");
    assertThat(partial.path("sourceCoverage").asText()).isEqualTo("PARTIAL");
    assertThat(partial.path("sourceText").asText()).isEqualTo(doc.text.substring(0, 16_000));
    doc.draft = Json.write(map("invoices", List.of(map("id", id), map("id", UUID.randomUUID()))));
    store.flush();
    var shared = Json.read(Json.write(categories.snapshot("invoice", id).input())).path("data");
    assertThat(shared.path("sourceCoverage").asText()).isEqualTo("SHARED_DOCUMENT");
    assertThat(shared.path("sourceText").isNull()).isTrue();
  }
}
