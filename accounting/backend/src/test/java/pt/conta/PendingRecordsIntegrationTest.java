package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static pt.conta.shared.Inputs.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.bank.*;
import pt.conta.document.*;
import pt.conta.invoice.*;
import pt.conta.ledger.*;
import pt.conta.reconciliation.*;
import pt.conta.review.*;
import pt.conta.shared.*;
import pt.conta.shared.Types.ReviewStatus;

/** Visibility is independent of financial eligibility and of the rest of an imported original. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class PendingRecordsIntegrationTest {
  static EmbeddedPostgres postgres;
  @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception {
    postgres=EmbeddedPostgres.builder().setPort(0).start();
    r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));
    r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
    r.add("accounting.storage",()->System.getProperty("java.io.tmpdir")+"/conta-pending-tests");
  }
  @Autowired Store store;@Autowired BookkeepingService book;@Autowired DocumentPublisher publisher;
  @Autowired DocumentService documents;@Autowired ReviewService review;@Autowired LedgerService ledger;
  @Autowired RepairService repairs;
  @Autowired pt.conta.api.FinanceController finance;
  @Autowired ReconciliationService reconciliation;
  UUID account;
  @BeforeEach void setup(){var a=new BankAccount();a.name="Pending test";account=store.add(a).id;}
  private Map<String,Object> invoice(String date,String number,String total,boolean validate) {
    return book.saveInvoice(null,map("issuedOn",date,"supplier","Pending supplier","number",number,"total",total,"validate",validate));
  }
  private Map<String,Object> movement(String date,String description,String amount,boolean validate) {
    return book.saveMovement(null,map("accountId",account.toString(),"bookedOn",date,"description",description,"amount",amount,"direction","DEBIT","validate",validate));
  }
  private SourceDocument source(String kind,String name) {
    var d=new SourceDocument();d.kind=kind;d.filename=name;d.mediaType="application/pdf";d.storageKey=UUID.randomUUID().toString();
    d.sha256=Hashes.sha256(d.storageKey);d.status="OPEN";d.accountId=kind.equals("STATEMENT")?account:null;
    return store.add(d);
  }
  private Map<String,Object> receipt(String number,String date,String amount) {
    return map("supplier","Source supplier","number",number,"issuedOn",date,"total",amount,"currency","EUR","type","INVOICE","validate",false,"lines",List.of());
  }
  private SourceDocument invoiceSource() {
    var d=source("INVOICE","Pending-Receipts.pdf");store.flush();
    publisher.extracted(d.id,d.version,new DocumentConverter.Converted("Original reference text",1,map("pages",List.of()),"TEST"),
        map("invoices",List.of(receipt("SOURCE-A",null,"100.00"),receipt("SOURCE-B",null,"50.00")),"validate",false,"issues",List.of("Faltam datas"),"reference","preserve-root"));
    return d;
  }
  private SourceDocument statementSource() {
    var d=source("STATEMENT","Pending-Bank.pdf");store.flush();
    publisher.extracted(d.id,d.version,new DocumentConverter.Converted("Original bank text",1,map("pages",List.of()),"TEST"),
        map("movements",List.of(map("bookedOn",null,"description","SOURCE-M-A","amount","100.00","direction","DEBIT","custom","keep-A"),
            map("bookedOn",null,"description","SOURCE-M-B","amount","50.00","direction","DEBIT","custom","keep-B")),"validate",false,"reference","preserve-root"));
    return d;
  }
  private List<Invoice> invoices(SourceDocument d){return store.all(Invoice.class).stream().filter(i->d.id.equals(i.documentId)&&!i.deleted).toList();}
  private List<BankMovement> movements(SourceDocument d){return store.all(BankMovement.class).stream().filter(m->d.id.equals(m.documentId)&&!m.deleted).toList();}
  private Map<String,Object> edit(Invoice i,boolean validate){var input=new LinkedHashMap<>(book.invoiceView(i));input.put("validate",validate);return input;}
  private Map<String,Object> edit(BankMovement m,boolean validate){var input=new LinkedHashMap<>(book.movementView(m));input.put("validate",validate);return input;}

  @Test
  void legacyDueDateWritesAreIgnoredAndNeverSupplyTheInvoiceDate() {
    var pending = finance.createInvoice(map("supplier", "Legacy caller", "total", "100.00",
        "dueOn", "not a date", "validate", false));
    UUID id = (UUID) pending.get("id");
    assertThat(pending).containsEntry("issuedOn", null).doesNotContainKey("dueOn");
    assertThat(finance.invoice(id)).doesNotContainKey("dueOn");
    assertThat(rows(finance.invoices(Map.of()), "items")).allSatisfy(row -> assertThat(row).doesNotContainKey("dueOn"));
    var invoice = store.get(Invoice.class, id);
    assertThat(Json.object(invoice.fieldProvenance)).doesNotContainKey("dueOn");
    var update = edit(invoice, true);
    update.put("dueOn", "2026-02-01");
    assertThatThrownBy(() -> finance.editInvoice(id, update)).isInstanceOf(DomainException.class)
        .hasMessageContaining("data");
  }

  @Test
  void rawDraftWritesDiscardDueDatesForRootAndIndependentReceipts() {
    var doc = source("INVOICE", "Legacy due dates.pdf");
    store.flush();
    var first = receipt("FIRST", "2026-01-01", "10.00");
    first.put("dueOn", "2026-02-01");
    var second = receipt("SECOND", null, "20.00");
    second.put("due_on", "2026-02-02");
    publisher.edit(doc.id, map("version", doc.version, "draft",
        map("issuedOn", null, "dueOn", "2026-02-03", "invoices", List.of(first, second))), documents);
    var draft = Json.object(doc.draft);
    assertThat(draft).containsEntry("issuedOn", null).doesNotContainKeys("dueOn", "due_on");
    assertThat(rows(draft, "invoices")).hasSize(2).allSatisfy(
        row -> assertThat(row).doesNotContainKeys("dueOn", "due_on"));
    assertThat(rows(draft, "invoices").getFirst()).containsEntry("issuedOn", "2026-01-01");
    assertThat(rows(draft, "invoices").getLast()).containsEntry("issuedOn", null);
    assertThat(doc.fieldProvenance).doesNotContain("dueOn", "due_on");
    assertThat(first).containsKey("dueOn");
  }

  @Test
  void legacyMovementWritesIgnoreValueDatesInApiAndProvenance() {
    var pending = finance.createMovement(map("accountId", account, "description", "Legacy caller",
        "amount", "100.00", "direction", "DEBIT", "valueOn", "not a date",
        "value_on", "2026-02-02", "validate", false));
    UUID id = (UUID) pending.get("id");
    assertThat(pending).containsEntry("bookedOn", null).doesNotContainKeys("valueOn", "value_on");
    assertThat(finance.movement(id)).doesNotContainKeys("valueOn", "value_on");
    assertThat(rows(finance.movements(Map.of()), "items"))
        .allSatisfy(row -> assertThat(row).doesNotContainKeys("valueOn", "value_on"));

    var update = new LinkedHashMap<>(pending);
    update.put("bookedOn", "2026-02-01");
    update.put("valueOn", "2026-02-02");
    update.put("value_on", "not a date");
    var edited = finance.editMovement(id, update);
    assertThat(edited).containsEntry("bookedOn", java.time.LocalDate.of(2026, 2, 1))
        .doesNotContainKeys("valueOn", "value_on");
    assertThat(Json.object(store.get(BankMovement.class, id).fieldProvenance))
        .containsKey("bookedOn").doesNotContainKeys("valueOn", "value_on");
  }

  @Test
  void statementDraftWritesDiscardLegacyValueDatesAndKeepOtherFindings() {
    var doc = source("STATEMENT", "Legacy value dates.pdf");
    store.flush();
    var row = map("bookedOn", null, "description", "Old import", "valueOn", "not a date",
        "value_on", "2026-02-02", "issues", List.of("data ilegível em valueOn", "Rever descrição"));
    publisher.edit(doc.id, map("version", doc.version, "draft",
        map("valueOn", "2026-02-03", "movements", List.of(row))), documents);

    var draft = Json.object(doc.draft);
    assertThat(draft).doesNotContainKeys("valueOn", "value_on");
    assertThat(rows(draft, "movements")).singleElement().satisfies(saved -> assertThat(saved)
        .containsEntry("bookedOn", null).containsEntry("description", "Old import")
        .containsEntry("issues", List.of("Rever descrição")).doesNotContainKeys("valueOn", "value_on"));
    assertThat(doc.fieldProvenance).doesNotContain("valueOn", "value_on");
    assertThat(row).containsKey("valueOn");
  }

  @Test void undatedPendingRecordsSurviveEveryPeriodFilterAndSortBeforeDatedRecords() {
    var pending=invoice(null,"UNDATED-I","100.00",false);var bank=movement(null,"UNDATED-M","60.00",false);
    invoice("2026-01-02","DATED-I","25.00",true);movement("2026-01-02","DATED-M","25.00",true);
    for(var filter:List.of(Map.of("year","2026"),Map.of("year","2016","month","12","day","31"),Map.of("from","2030-01-01","to","2030-01-31"))) {
      assertThat(rows(finance.invoices(filter),"items").getFirst().get("id")).isEqualTo(pending.get("id"));
      assertThat(rows(finance.movements(filter),"items").getFirst().get("id")).isEqualTo(bank.get("id"));
      var entries=reconciliation.entries(filter).items();
      assertThat(entries.getFirst().pendingReview()).isTrue();assertThat(entries.getFirst().firstDate()).isNull();
      assertThat(entries.stream().filter(ReconciliationService.Entry::pendingReview)).hasSize(2);
    }
  }

  @Test void datedPendingRecordsFollowThePeriodAndOtherFiltersStillApply() {
    var i=invoice("2016-01-01","DATED-PENDING","100.00",false);movement("2016-01-01","DATED-PENDING-M","100.00",false);
    assertThat(rows(finance.invoices(Map.of("year","2026")),"items")).isEmpty();
    assertThat(rows(finance.movements(Map.of("year","2026")),"items")).isEmpty();
    assertThat(reconciliation.entries(Map.of("year","2026")).items()).isEmpty();
    assertThat(reconciliation.entries(Map.of("year","2016","status","OPEN")).items()).hasSize(2);
    invoice(null,"UNDATED","20.00",false);
    assertThat(rows(finance.invoices(Map.of("year","2026","status","MANUAL")),"items")).isEmpty();
    assertThat(rows(finance.invoices(Map.of("year","2026","q","no-match")),"items")).isEmpty();
    assertThat(rows(finance.invoices(Map.of("year","2026","min","30.00")),"items")).isEmpty();
    assertThat(rows(finance.movements(Map.of("year","2026","accountId",UUID.randomUUID().toString())),"items")).isEmpty();
    assertThat(i.get("reviewStatus")).isEqualTo(ReviewStatus.OPEN);
  }

  @Test void paginationCountsUndatedItemsAndFinancialTotalsStillExcludeThem() {
    invoice(null,"PENDING-1","100.00",false);invoice(null,"PENDING-2",null,false);movement(null,"PENDING-3","60.00",false);
    var page=finance.invoices(Map.of("year","2026","size","1","page","1"));
    assertThat(page.get("total")).isEqualTo(2);assertThat(rows(page,"items")).hasSize(1);
    assertThat(reconciliation.entries(Map.of("year","2026","size","1")).total()).isEqualTo(3);
    assertThat(finance.summary(2026,null).get("outstanding")).isEqualTo("0.00");
    assertThat(book.candidates()).isEmpty();assertThat(store.all(LedgerEntry.class)).isEmpty();
    for(var entry:reconciliation.entries(Map.of("year","2026")).items()){assertThat(entry.balanceImpact()).isNull();assertThat(entry.periodImpact()).isNull();}
  }

  @Test void pendingAmountsRemainRawAndCannotBeManuallyAllocated() {
    var i=invoice(null,"RAW-AMOUNT",null,false);var m=movement(null,"RAW-MOVEMENT",null,false);
    assertThat(i).containsEntry("total",null).containsEntry("remaining",null);
    assertThat(m).containsEntry("amount",null).containsEntry("remaining",null);
    assertThatThrownBy(()->book.allocate(map("invoiceId",i.get("id").toString(),"movementId",m.get("id").toString(),"amount","10.00"))).isInstanceOf(DomainException.class);
    assertThat(store.all(Allocation.class)).isEmpty();assertThat(store.all(LedgerEntry.class)).isEmpty();
  }

  @Test void sourceFilenamesAreSearchableEvenWhenRecordDescriptionsAreMissing() {
    var d=invoiceSource();var rows=finance.invoices(Map.of("year","2026","q","Pending-Receipts.pdf"));
    assertThat(rows.get("total")).isEqualTo(2);
    var bank=statementSource();var m=movements(bank).getFirst();m.description=null;store.flush();
    assertThat(finance.movements(Map.of("year","2026","q","Pending-Bank.pdf")).get("total")).isEqualTo(2);
    assertThat(reconciliation.entries(Map.of("year","2026","q","Pending-Receipts.pdf")).items()).hasSize(2);
  }

  @Test void pendingAndExcludedAreIndependentAndBothStayOutOfCalculations() {
    var i=invoice(null,"PENDING-EXCLUDED","100.00",false);
    book.exclude(new ExclusionRequest(true,List.of(new ExclusionRequest.Item("invoice",(UUID)i.get("id"),((Number)i.get("version")).longValue())),null));
    var entry=reconciliation.entries(Map.of("year","2026")).items().getFirst();
    assertThat(entry.excluded()).isTrue();assertThat(entry.pendingReview()).isTrue();assertThat(entry.balanceImpact()).isNull();
    assertThat(store.all(LedgerEntry.class)).isEmpty();
  }

  @Test void validatingOneReceiptPreservesItsSiblingSourceMetadataAndAlerts() {
    var d=invoiceSource();var children=invoices(d);var a=children.getFirst();var b=children.getLast();long siblingVersion=b.version;
    var before=Json.object(d.draft);var untouched=Json.write(rows(before,"invoices").get(1));
    var documentAlert=review.open(d.id,"document",d.id,"SOURCE_REVIEW","Source needs review");
    var input=edit(a,true);input.put("issuedOn","2026-01-01");book.saveInvoice(a.id,input);
    var after=Json.object(d.draft);
    assertThat(after.get("reference")).isEqualTo("preserve-root");assertThat(Json.write(rows(after,"invoices").get(1))).isEqualTo(untouched);
    assertThat(b.reviewStatus).isEqualTo(ReviewStatus.OPEN);assertThat(b.version).isEqualTo(siblingVersion);
    assertThat(d.status).isEqualTo("OPEN");assertThat(documentAlert.status).isEqualTo("OPEN");
    assertThat(a.reviewStatus).isEqualTo(ReviewStatus.CORRECTED);assertThat(store.all(LedgerEntry.class)).hasSize(1);
    assertThat(book.invoiceView(a).get("documentVersion")).isEqualTo(d.version);
    var inputB=edit(b,true);inputB.put("issuedOn","2026-01-02");book.saveInvoice(b.id,inputB);
    assertThat(d.status).isEqualTo("OPEN");assertThat(documentAlert.status).isEqualTo("OPEN");
    publisher.edit(d.id, map("version", d.version, "draft", Json.object(d.draft), "resolve", true), documents);
    assertThat(d.status).isEqualTo("DONE");assertThat(documentAlert.status).isEqualTo("RESOLVED");
    assertThat(rows(Json.object(d.draft),"invoices")).hasSize(2);assertThat(invoices(d).stream().map(i->i.id)).containsExactly(a.id,b.id);
  }

  @Test void savingAnIncompleteSiblingDoesNotInvalidateAnAlreadyValidatedReceipt() {
    var d=invoiceSource();var children=invoices(d);var a=children.getFirst();var b=children.getLast();
    var input=edit(a,true);input.put("issuedOn","2026-01-01");book.saveInvoice(a.id,input);long version=a.version;
    var draft=edit(b,false);draft.put("supplier",null);book.saveInvoice(b.id,draft);
    assertThat(a.reviewStatus).isEqualTo(ReviewStatus.CORRECTED);assertThat(a.version).isEqualTo(version);
    assertThat(b.reviewStatus).isEqualTo(ReviewStatus.OPEN);assertThat(store.all(LedgerEntry.class)).hasSize(1);
  }

  @Test void statementItemsValidateIndependentlyAndCompleteTheParentOnlyAtTheEnd() {
    var d=statementSource();var children=movements(d);var a=children.getFirst();var b=children.getLast();
    var before=Json.object(d.draft);String untouched=Json.write(rows(before,"movements").getLast());
    var input=edit(a,true);input.put("bookedOn","2026-01-01");book.saveMovement(a.id,input);
    assertThat(a.reviewStatus).isEqualTo(ReviewStatus.CORRECTED);assertThat(b.reviewStatus).isEqualTo(ReviewStatus.OPEN);
    assertThat(Json.write(rows(Json.object(d.draft),"movements").getLast())).isEqualTo(untouched);
    assertThat(Json.object(d.draft).get("reference")).isEqualTo("preserve-root");
    assertThat(store.get(BankStatement.class,a.statementId).validated).isFalse();assertThat(d.status).isEqualTo("OPEN");
    var draft=edit(b,false);draft.put("description",null);book.saveMovement(b.id,draft);
    assertThat(a.reviewStatus).isEqualTo(ReviewStatus.CORRECTED);assertThat(store.all(LedgerEntry.class)).hasSize(1);
    var last=edit(b,true);last.put("bookedOn","2026-01-02");last.put("description","SOURCE-M-B fixed");book.saveMovement(b.id,last);
    assertThat(d.status).isEqualTo("DONE");assertThat(store.get(BankStatement.class,a.statementId).validated).isTrue();
    assertThat(movements(d).stream().map(m->m.id)).containsExactly(a.id,b.id);
  }

  @Test void reorderedSourceRowsAreUpdatedByIdRatherThanPosition() {
    var d=invoiceSource();var a=invoices(d).getFirst();var draft=Json.object(d.draft);var entries=new ArrayList<>(rows(draft,"invoices"));
    Collections.reverse(entries);draft.put("invoices",entries);d.draft=Json.write(draft);store.flush();
    var input=edit(a,true);input.put("issuedOn","2026-01-01");book.saveInvoice(a.id,input);
    var updated=rows(Json.object(d.draft),"invoices");assertThat(updated.getLast().get("id")).isEqualTo(a.id.toString());
    assertThat(updated.getLast().get("issuedOn")).isEqualTo("2026-01-01");assertThat(updated.getFirst().get("issuedOn")).isNull();
  }

  @Test void staleDocumentVersionRejectsTheWholeEdit() {
    var d=invoiceSource();var a=invoices(d).getFirst();var input=edit(a,true);input.put("issuedOn","2026-01-01");
    long version=a.version;String original=d.draft;d.text="Source changed by another user";store.flush();
    assertThatThrownBy(()->book.saveInvoice(a.id,input)).isInstanceOf(DomainException.class).hasMessageContaining("alterado");
    assertThat(a.version).isEqualTo(version);assertThat(a.issuedOn).isNull();assertThat(d.draft).isEqualTo(original);assertThat(store.all(LedgerEntry.class)).isEmpty();
  }

  @Test void ambiguousLegacyRowsAreRejectedBeforeAnyFinancialChange() {
    var d=invoiceSource();var draft=Json.object(d.draft);for(var row:rows(draft,"invoices")){row.remove("id");row.remove("number");}d.draft=Json.write(draft);store.flush();
    var a=invoices(d).getFirst();a.number=null;store.flush();var input=edit(a,true);input.put("issuedOn","2026-01-01");
    String original=d.draft;
    assertThatThrownBy(()->book.saveInvoice(a.id,input)).isInstanceOf(DomainException.class).hasMessageContaining("identificar");
    assertThat(a.issuedOn).isNull();assertThat(d.draft).isEqualTo(original);assertThat(store.all(LedgerEntry.class)).isEmpty();
  }

  @Test void uniqueLegacyFactsCanReconnectAMissingChildId() {
    var d=invoiceSource();var draft=Json.object(d.draft);for(var row:rows(draft,"invoices"))row.remove("id");d.draft=Json.write(draft);store.flush();
    var a=invoices(d).getFirst();var input=edit(a,true);input.put("issuedOn","2026-01-01");book.saveInvoice(a.id,input);
    assertThat(rows(Json.object(d.draft),"invoices").getFirst().get("id")).isEqualTo(a.id.toString());
  }

  @Test void uniqueLegacyBankReferencesIdentifyUndatedMovementsWithoutGuessingTheirPosition() {
    var d=statementSource();var children=movements(d);var draft=Json.object(d.draft);var rows=new ArrayList<>(rows(draft,"movements"));
    for(int n=0;n<rows.size();n++){rows.get(n).remove("id");rows.get(n).put("bankReference","UNIQUE-REF-"+n);children.get(n).bankReference="UNIQUE-REF-"+n;}
    Collections.reverse(rows);draft.put("movements",rows);d.draft=Json.write(draft);store.flush();
    var a=children.getFirst();var input=edit(a,true);input.put("bookedOn","2026-01-01");book.saveMovement(a.id,input);
    assertThat(rows(Json.object(d.draft),"movements").getLast().get("id")).isEqualTo(a.id.toString());
    assertThat(children.getLast().reviewStatus).isEqualTo(ReviewStatus.OPEN);
  }

  @Test void validationMakesMatchingAvailableOnlyAfterward() {
    var pending=invoice(null,"READY-FOR-MATCH","100.00",false);var bank=movement("2026-01-02","READY-FOR-MATCH","100.00",true);
    assertThat(book.candidates()).isEmpty();var a=store.get(Invoice.class,(UUID)pending.get("id"));
    var input=edit(a,true);input.put("issuedOn","2026-01-01");book.saveInvoice(a.id,input);book.synchronize();book.automatic();
    assertThat(store.all(Allocation.class).stream().filter(link->link.active&&link.invoiceId.equals(a.id))).hasSize(1);
    int entries=store.all(LedgerEntry.class).size();book.synchronize();book.automatic();assertThat(store.all(LedgerEntry.class)).hasSize(entries);
  }

  @Test void recordAlertKeepsItsConcernsAndOnlyValidatingPublishesTheItem() {
    var doc = invoiceSource();
    var item = invoices(doc).getFirst();
    var sibling = invoices(doc).getLast();
    var alert = review.openFor("invoice", item.id).orElseThrow();
    review.open(doc.id, "invoice", item.id, "AI_CONCERN", "Confirma a data no original");
    review.open(doc.id, "invoice", item.id, "AI_CONCERN", "Confirma a data no original");
    store.flush();
    assertThat(alert.concerns()).extracting(Alert.Concern::code).containsExactly("INVALID_INVOICE", "AI_CONCERN");
    var draft = edit(item, false);
    draft.put("issuedOn", "2026-01-01");
    draft.put("description", "Corrigido no rascunho");
    repairs.saveRecordDraft(alert.id, alertSave(doc, item, alert, draft, false));
    assertThat(item.description).isEqualTo("Corrigido no rascunho");
    assertThat(alert.status).isEqualTo("OPEN");
    assertThat(store.all(LedgerEntry.class)).isEmpty();

    movement("2026-01-02", item.number, "100.00", true);
    repairs.saveRecordDraft(alert.id, alertSave(doc, item, alert, draft, true));
    assertThat(alert.status).isEqualTo("RESOLVED");
    assertThat(sibling.reviewStatus).isEqualTo(ReviewStatus.OPEN);
    assertThat(book.remaining(item)).isEqualByComparingTo("0.00");
    assertThat(review.open(doc.id, "invoice", item.id, "AI_CONCERN", "Novo alerta")).isNull();

    var revision = edit(item, true);
    revision.put("total", "120.00");
    book.saveInvoice(item.id, revision);
    assertThat(item.manuallyConfirmed).isTrue();
    assertThat(book.remaining(item)).isEqualByComparingTo("20.00");
    var effects = store.all(LedgerEntry.class).stream().filter(entry -> item.id.equals(entry.sourceId))
        .map(entry -> entry.amount).reduce(Money.ZERO, java.math.BigDecimal::add);
    assertThat(effects).isEqualByComparingTo("120.00");
    assertThat(review.openFor("invoice", item.id)).isEmpty();
  }

  private Map<String, Object> alertSave(SourceDocument doc, Invoice invoice, Alert alert,
      Map<String, Object> draft, boolean resolve) {
    return map("alertVersion", alert.version, "recordVersion", invoice.version,
        "documentVersion", doc.version, "draft", draft, "resolve", resolve);
  }

  @Test void movementExtractionGroupsMissingFieldsAndDuplicateWarningsByItem() {
    movement("2026-01-01", "Sobreposição", "42.00", true);
    var doc = source("STATEMENT", "Overlap.pdf");
    var repeat = map("bookedOn", "2026-01-01", "description", "Sobreposição", "amount", "42.00",
        "direction", null, "issues", List.of("Débito ou crédito incerto"));
    var clean = map("bookedOn", "2026-01-02", "description", "Outro pagamento", "amount", "9.00", "direction", "DEBIT");
    var draft = map("movements", List.of(repeat, clean), "validate", true);
    store.flush();
    var source = new DocumentConverter.Converted("Extrato", 1, Map.of(), "TEST");
    publisher.extracted(doc.id, doc.version, source, draft);
    var overlap = movements(doc).stream().filter(item -> "Sobreposição".equals(item.description)).findFirst().orElseThrow();
    var alert = review.openFor("movement", overlap.id).orElseThrow();
    assertThat(alert.concerns()).extracting(Alert.Concern::code)
        .containsExactly("INVALID_MOVEMENT", "EXTRACTION_REVIEW", "POSSIBLE_DUPLICATE");
    assertThat(review.openFor("document", doc.id)).isEmpty();
    assertThat(movements(doc)).filteredOn(item -> !item.id.equals(overlap.id))
        .singleElement().satisfies(item -> assertThat(item.reviewStatus).isEqualTo(ReviewStatus.AUTOMATIC));
    publisher.extracted(doc.id, doc.version, source, draft);
    assertThat(review.openFor("movement", overlap.id)).contains(alert);
    assertThat(alert.concerns()).hasSize(3);
  }

  @Test void individualValidationPreservesCategoriesAndExclusion() {
    var d=invoiceSource();var a=invoices(d).getFirst();
    UUID category=store.all(Category.class).stream().filter(c->"6263".equals(c.referenceCode)).findFirst().orElseThrow().id;
    var draft=edit(a,false);draft.put("categories",List.of("Seguros"));book.saveInvoice(a.id,draft);
    book.exclude(new ExclusionRequest(true,List.of(new ExclusionRequest.Item("invoice",a.id,a.version)),null));
    var input=edit(a,true);input.put("issuedOn","2026-01-01");book.saveInvoice(a.id,input);
    assertThat(a.excluded).isTrue();assertThat(a.reviewStatus).isEqualTo(ReviewStatus.CORRECTED);
    assertThat(book.invoiceView(a).get("categoryIds").toString()).contains(category.toString());
    assertThat(store.all(LedgerEntry.class)).isEmpty();assertThat(invoices(d)).hasSize(2);
  }
}
