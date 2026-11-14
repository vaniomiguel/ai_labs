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
import pt.conta.review.*;
import pt.conta.shared.*;

/** Human ownership is enforced where values are written, independently of model compliance. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @Transactional
class FieldProtectionIntegrationTest {
  static EmbeddedPostgres postgres;
  @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception {
    postgres=EmbeddedPostgres.builder().setPort(0).start();
    r.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));
    r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
    r.add("accounting.storage",()->System.getProperty("java.io.tmpdir")+"/conta-field-tests");
  }
  @Autowired Store store;@Autowired BookkeepingService book;@Autowired DocumentPublisher publisher;
  @Autowired DocumentService documents;@Autowired RepairService repairs;@Autowired ReviewService review;@Autowired FieldProtection protection;
  private Map<String,Object> invoice(String number){return map("supplier","Fornecedor original","number",number,"issuedOn","2026-01-01","total","100.00","currency","EUR","type","INVOICE","lines",List.of(),"validate",false);}
  private SourceDocument document(String kind,Map<String,Object> draft) {
    var doc=new SourceDocument();doc.kind=kind;doc.filename="original.pdf";doc.mediaType="application/pdf";doc.storageKey="test";doc.sha256=UUID.randomUUID().toString();
    if(kind.equals("STATEMENT")){var account=new BankAccount();account.name="Conta";doc.accountId=store.add(account).id;}
    store.add(doc);store.flush();
    publisher.extracted(doc.id,doc.version,new DocumentConverter.Converted("Texto integral",1,map("pages",List.of()),"TEST"),draft);store.flush();return doc;
  }
  private Invoice record(SourceDocument doc){return store.all(Invoice.class).stream().filter(i->doc.id.equals(i.documentId)&&!i.deleted).findFirst().orElseThrow();}
  private Alert proposal(SourceDocument doc,Map<String,Object> draft) {
    store.flush();var alert=review.open(doc.id,"document",doc.id,"TEST_"+UUID.randomUUID(),"Rever valores");
    alert.proposal=Json.write(map("draft",draft,"jobId",UUID.randomUUID().toString(),"documentVersion",doc.version,"evidence",List.of(map("field","supplier","quote","Fornecedor proposto"))));store.flush();return alert;
  }
  private Map<String,Object> request(SourceDocument doc,Alert alert,List<String> accepted,List<String> selected,boolean validate) {
    var proposal=Json.object(alert.proposal);
    return map("documentVersion",doc.version,"proposalId",proposal.get("jobId"),"draft",Json.object(doc.draft),"acceptedFields",accepted,"selectedItems",selected,"validate",validate);
  }
  private Map<String,Object> edited(Invoice i){return protection.copy(book.invoiceView(i));}
  private Map<String,Object> owner(Invoice i,String field){return protection.copy(Json.object(i.fieldProvenance).get(field));}

  @Test void automaticWritesCannotReplaceAManualValueOrClear() {
    var draft=invoice("PROTECT");var saved=book.saveInvoice(null,draft);var i=store.get(Invoice.class,(UUID)saved.get("id"));
    var edit=edited(i);edit.put("supplierTaxId",null);edit.put("description","Remover depois");book.saveInvoice(i.id,edit);
    edit=edited(i);edit.put("description",null);book.saveInvoice(i.id,edit);
    var ai=invoice("DIFFERENT");ai.put("supplier","Fornecedor IA");ai.put("description","Preencher automaticamente");
    book.saveInvoice(i.id,ai,"AI",false);
    assertThat(i.supplier).isEqualTo("Fornecedor original");assertThat(i.number).isEqualTo("PROTECT");assertThat(i.description).isNull();
    assertThat(owner(i,"description")).containsEntry("origin","HUMAN_EDIT");
  }

  @Test void validatingProtectsPopulatedValuesButNotUntouchedEmptyOptionalFields() {
    var doc=document("INVOICE",invoice("VALIDATE"));var i=record(doc);assertThat(owner(i,"supplier")).containsEntry("origin","AUTOMATIC");
    var edit=edited(i);edit.put("validate",true);book.saveInvoice(i.id,edit);
    assertThat(owner(i,"supplier")).containsEntry("origin","HUMAN_CONFIRMED").containsEntry("protected",true);
    assertThat(Json.object(i.fieldProvenance)).doesNotContainKey("supplierTaxId");
    assertThat(store.all(LedgerEntry.class)).hasSize(1);
  }

  @Test void unchangedFullFormAndNormalizedMoneyDoNotBecomeHumanEdits() {
    var doc=document("INVOICE",invoice("NORMALIZE"));var i=record(doc);var edit=edited(i);edit.put("total","100.0");edit.put("description","Descrição humana");
    book.saveInvoice(i.id,edit);
    assertThat(owner(i,"total")).containsEntry("origin","AUTOMATIC");assertThat(owner(i,"supplier")).containsEntry("origin","AUTOMATIC");
    assertThat(owner(i,"description")).containsEntry("origin","HUMAN_EDIT");
  }

  @Test void reprocessingPreservesHumanDraftValuesAndStoresConflictsAsSuggestions() {
    var doc=document("INVOICE",invoice("REPROCESS"));var draft=Json.object(doc.draft);draft.put("supplier","Nome humano");draft.put("description","");
    publisher.edit(doc.id,map("version",doc.version,"draft",draft,"resolve",false),documents);
    var ai=invoice("REPROCESS");ai.put("supplier","Nome alternativo");ai.put("description","Descrição IA");ai.put("supplierTaxId","123456789");
    publisher.aiExtracted(doc.id,doc.version,ai,"[]");
    assertThat(Json.object(doc.draft)).containsEntry("supplier","Nome humano");
    // A deliberately cleared nonempty field is protected; an untouched blank remains fillable.
    assertThat(Json.object(doc.draft)).containsEntry("supplierTaxId","123456789");
    assertThat(store.all(Alert.class)).anySatisfy(a->{assertThat(a.code).isEqualTo("AI_FIELD_SUGGESTIONS");assertThat(a.proposal).contains("Nome alternativo");});
    assertThat(store.all(ExtractionAttempt.class)).anySatisfy(a->assertThat(a.result).contains("Nome alternativo"));
  }

  @Test void missingAiValuesNeverEraseExistingValues() {
    var doc=document("INVOICE",invoice("MISSING"));publisher.aiExtracted(doc.id,doc.version,map("supplier",null,"total",null,"issuedOn",null,"number",null),"[]");
    assertThat(Json.object(doc.draft)).containsEntry("supplier","Fornecedor original").containsEntry("total","100.00").containsEntry("number","MISSING");
  }

  @Test void incompleteComplementsCanBeSavedWithoutValidatingOrPostingMoney() {
    var initial=invoice("INCOMPLETE");initial.put("supplier",null);initial.put("issuedOn",null);initial.put("total",null);
    var doc=document("INVOICE",initial);var ai=protection.copy(initial);ai.put("supplier","Vueling");ai.put("total","56.64");
    var alert=proposal(doc,ai);var body=request(doc,alert,List.of(),List.of("root"),false);
    assertThat(repairs.preview(alert.id,body)).containsEntry("canApplyDraft",true).containsEntry("canValidate",false);
    repairs.apply(alert.id,body,documents);
    var i=record(doc);assertThat(i.supplier).isEqualTo("Vueling");assertThat(i.total).isEqualByComparingTo("56.64");assertThat(i.issuedOn).isNull();
    assertThat(i.reviewStatus.name()).isEqualTo("OPEN");assertThat(store.all(LedgerEntry.class)).isEmpty();
    assertThat(owner(i,"supplier")).containsEntry("origin","HUMAN_ACCEPTED_AI");
  }

  @Test void bodylessRequestsDoNotAuthorizeConflictingReplacements() {
    var doc=document("INVOICE",invoice("LEGACY"));var ai=invoice("OTHER");ai.put("supplier","Nome IA");
    var alert=proposal(doc,ai);
    assertThatThrownBy(()->repairs.apply(alert.id,Map.of(),documents)).isInstanceOf(DomainException.class);
    assertThat(record(doc).supplier).isEqualTo("Fornecedor original");assertThat(store.all(LedgerEntry.class)).isEmpty();
  }

  @Test void acceptingOneConflictKeepsOthersAndProtectsTheAcceptedDecision() {
    var doc=document("INVOICE",invoice("CONFLICT"));var ai=invoice("REPLACEMENT");ai.put("supplier","Nome IA");
    var alert=proposal(doc,ai);repairs.apply(alert.id,request(doc,alert,List.of("root:supplier"),List.of("root"),false),documents);
    var i=record(doc);assertThat(i.supplier).isEqualTo("Nome IA");assertThat(i.number).isEqualTo("CONFLICT");
    assertThat(owner(i,"supplier")).containsEntry("origin","HUMAN_ACCEPTED_AI").containsEntry("proposalId",Json.object(alert.proposal).get("jobId"));
    publisher.aiExtracted(doc.id,doc.version,invoice("LATER"),"[]");assertThat(Json.object(doc.draft)).containsEntry("supplier","Nome IA");
  }

  @Test void localEditsWhileAiRunsWinOverComplementsUnlessExplicitlyAccepted() {
    var initial=invoice("LOCAL");initial.put("supplier",null);var doc=document("INVOICE",initial);
    var ai=invoice("LOCAL");ai.put("supplier","Fornecedor IA");var alert=proposal(doc,ai);var body=request(doc,alert,List.of(),List.of("root"),false);
    var local=Json.object(doc.draft);local.put("supplier","Fornecedor escrito agora");body.put("draft",local);
    var preview=repairs.preview(alert.id,body);assertThat(((List<?>)preview.get("changes")).toString()).contains("CONFLICT","protected=true");
    repairs.apply(alert.id,body,documents);assertThat(record(doc).supplier).isEqualTo("Fornecedor escrito agora");
  }

  @Test void selectedInvoiceValidationPreservesSiblingStateMetadataAndIds() {
    var first=invoice("CHILD-A");first.put("issuedOn",null);var second=invoice("CHILD-B");second.put("issuedOn",null);
    var doc=document("INVOICE",map("invoices",List.of(first,second),"metadata",map("original","preservado"),"validate",false));
    var rows=rows(Json.object(doc.draft),"invoices");String a=rows.get(0).get("id").toString(),b=rows.get(1).get("id").toString();String sibling=Json.write(rows.get(1));
    var ai=protection.copy(Json.object(doc.draft));rows(ai,"invoices").forEach(r->r.put("issuedOn","2026-01-02"));var reversed=new ArrayList<>(rows(ai,"invoices"));Collections.reverse(reversed);ai.put("invoices",reversed);
    var alert=proposal(doc,ai);
    var preview=repairs.preview(alert.id,request(doc,alert,List.of(),List.of(a),true));
    assertThat(((List<Map<String,Object>>)preview.get("changes")).stream().filter(c->c.get("itemId").equals(a)).findFirst().orElseThrow()).containsEntry("proposalIndex",1);
    repairs.apply(alert.id,request(doc,alert,List.of(),List.of(a),true),documents);
    assertThat(store.get(Invoice.class,UUID.fromString(a)).reviewStatus.name()).isEqualTo("CORRECTED");
    assertThat(store.get(Invoice.class,UUID.fromString(b)).reviewStatus.name()).isEqualTo("OPEN");
    assertThat(Json.write(rows(Json.object(doc.draft),"invoices").get(1))).isEqualTo(sibling);
    assertThat(Json.object(doc.draft).get("metadata")).isEqualTo(map("original","preservado"));assertThat(doc.status).isEqualTo("OPEN");
    assertThat(store.all(LedgerEntry.class)).hasSize(1);
  }

  @Test void acceptingInvoiceArticlesRequiresExplicitCollectionAcceptance() {
    var doc=document("INVOICE",invoice("LINES"));var i=record(doc);var human=edited(i);
    human.put("lines",List.of(map("description","Artigo humano","total","100.00")));book.saveInvoice(i.id,human);
    var ai=Json.object(doc.draft);ai.put("lines",List.of(map("description","Outro artigo","total","100.00")));
    var alert=proposal(doc,ai);var preview=repairs.preview(alert.id,request(doc,alert,List.of(),List.of("root"),false));
    assertThat(preview).containsEntry("canApplyDraft",false);
    repairs.apply(alert.id,request(doc,alert,List.of("root:lines"),List.of("root"),false),documents);
    assertThat(book.invoiceView(i).get("lines").toString()).contains("Outro artigo");assertThat(owner(i,"lines")).containsEntry("origin","HUMAN_ACCEPTED_AI");
  }

  @Test void draftAcceptanceDoesNotModifyAlreadyPublishedFinancialValuesOrBalances() {
    var doc=document("INVOICE",invoice("PUBLISHED"));var i=record(doc);var human=edited(i);human.put("validate",true);book.saveInvoice(i.id,human);
    var ai=Json.object(doc.draft);ai.put("total","150.00");var alert=proposal(doc,ai);int postings=store.all(LedgerEntry.class).size();
    repairs.apply(alert.id,request(doc,alert,List.of("root:total"),List.of("root"),false),documents);
    assertThat(i.total).isEqualByComparingTo("100.00");assertThat(i.reviewStatus.name()).isNotEqualTo("OPEN");
    assertThat(Json.object(doc.draft)).containsEntry("total","150.00").containsEntry("validate",false);assertThat(store.all(LedgerEntry.class)).hasSize(postings);
    publisher.aiExtracted(doc.id,doc.version,invoice("PUBLISHED"),"[]");
    assertThat(doc.status).isEqualTo("OPEN");assertThat(Json.object(doc.draft)).containsEntry("total","150.00").containsEntry("validate",false);
    assertThat(i.total).isEqualByComparingTo("100.00");assertThat(store.all(LedgerEntry.class)).hasSize(postings);
  }

  @Test void staleVersionsAndInvalidSelectionsDoNotPartiallyApply() {
    var initial=invoice("STALE");initial.put("supplier",null);var doc=document("INVOICE",initial);var alert=proposal(doc,invoice("STALE"));
    var body=request(doc,alert,List.of("root:notAllowed"),List.of("root"),false);String before=doc.draft;
    assertThatThrownBy(()->repairs.preview(alert.id,body)).isInstanceOf(DomainException.class);assertThat(doc.draft).isEqualTo(before);
    body.put("acceptedFields",List.of());body.put("documentVersion",doc.version-1);
    assertThatThrownBy(()->repairs.preview(alert.id,body)).isInstanceOf(DomainException.class);assertThat(doc.draft).isEqualTo(before);
    body.put("documentVersion",doc.version);body.put("proposalId",UUID.randomUUID().toString());
    assertThatThrownBy(()->repairs.preview(alert.id,body)).isInstanceOf(DomainException.class);assertThat(doc.draft).isEqualTo(before);
  }

  @Test void ambiguousReprocessingKeepsAllExistingChildrenAndRaisesReview() {
    var doc=document("INVOICE",map("invoices",List.of(invoice("OLD-A"),invoice("OLD-B")),"validate",false));
    String before=doc.draft;var replacement=map("invoices",List.of(invoice("NEW-A"),invoice("NEW-B")),"validate",false);
    publisher.aiExtracted(doc.id,doc.version,replacement,"[]");
    assertThat(rows(Json.object(doc.draft),"invoices")).extracting(r->r.get("number")).containsExactly("OLD-A","OLD-B");
    assertThat(store.all(Invoice.class)).hasSize(2);assertThat(store.all(Alert.class)).anySatisfy(a->assertThat(a.code).isEqualTo("AI_STRUCTURE_REVIEW"));
  }

  @Test void provenanceInTheClientPayloadCannotForgeAnUnprotectedHumanField() {
    var doc=document("INVOICE",invoice("READONLY"));var i=record(doc);var edit=edited(i);edit.put("supplier","Nome humano");edit.put("fieldProvenance",Map.of());book.saveInvoice(i.id,edit);
    var untrusted=invoice("READONLY");untrusted.put("supplier","Nome IA");untrusted.put("fieldProvenance",Map.of());book.saveInvoice(i.id,untrusted,"AI",false);
    assertThat(i.supplier).isEqualTo("Nome humano");assertThat(owner(i,"supplier")).containsEntry("protected",true);
  }

  @Test void onlyTheSelectedMovementIsValidatedAndItsHumanDescriptionSurvives() {
    var a=map("bookedOn",null,"description","Descrição humana","amount","25.00","direction","DEBIT","bankReference","A","validate",false);
    var b=map("bookedOn",null,"description","Outro movimento","amount","30.00","direction","DEBIT","bankReference","B","validate",false);
    var doc=document("STATEMENT",map("movements",List.of(a,b),"validate",false));
    var original=rows(Json.object(doc.draft),"movements");String first=original.get(0).get("id").toString(),second=original.get(1).get("id").toString();String sibling=Json.write(original.get(1));
    var ai=Json.object(doc.draft);for(var row:rows(ai,"movements")){row.put("bookedOn","2026-01-02");row.put("description","Sugestão IA");}
    var reverse=new ArrayList<>(rows(ai,"movements"));Collections.reverse(reverse);ai.put("movements",reverse);
    var alert=proposal(doc,ai);repairs.apply(alert.id,request(doc,alert,List.of(),List.of(first),true),documents);
    var movement=store.get(BankMovement.class,UUID.fromString(first));assertThat(movement.description).isEqualTo("Descrição humana");assertThat(movement.reviewStatus.name()).isEqualTo("CORRECTED");
    assertThat(store.get(BankMovement.class,UUID.fromString(second)).reviewStatus.name()).isEqualTo("OPEN");
    assertThat(Json.write(rows(Json.object(doc.draft),"movements").get(1))).isEqualTo(sibling);assertThat(doc.status).isEqualTo("OPEN");
    assertThat(store.all(LedgerEntry.class)).hasSize(1);
  }

  @Test void deliberatelyClearingADocumentFieldProtectsItsBlankValueFromReExtraction() {
    var initial=invoice("CLEAR");initial.put("description","Valor anteriormente extraído");var doc=document("INVOICE",initial);
    var human=Json.object(doc.draft);human.put("description",null);publisher.edit(doc.id,map("version",doc.version,"draft",human,"resolve",false),documents);
    var ai=invoice("CLEAR");ai.put("description","Texto recuperado pela IA");publisher.aiExtracted(doc.id,doc.version,ai,"[]");
    assertThat(Json.object(doc.draft).get("description")).isNull();
    var alert=store.all(Alert.class).stream().filter(x->x.code.equals("AI_FIELD_SUGGESTIONS")).findFirst().orElseThrow();
    assertThat(repairs.view(alert.id).get("proposal").toString()).contains("protected=true","Texto recuperado pela IA");
  }

  @Test void aRepeatedAcceptanceIsStaleAndDoesNotCreateAnotherPosting() {
    var doc=document("INVOICE",invoice("ONCE"));var ai=Json.object(doc.draft);ai.put("supplierTaxId","123456789");var alert=proposal(doc,ai);
    var body=request(doc,alert,List.of(),List.of("root"),true);repairs.apply(alert.id,body,documents);int count=store.all(LedgerEntry.class).size();
    assertThatThrownBy(()->repairs.preview(alert.id,body)).isInstanceOf(DomainException.class);assertThat(store.all(LedgerEntry.class)).hasSize(count);
  }

  @Test void acceptingFinancialFieldsPreservesManualCategoriesExclusionAndAuditEvidence() {
    var initial=invoice("CATEGORIES");initial.put("categories",List.of("Categoria antiga"));var doc=document("INVOICE",initial);var i=record(doc);
    var edit=edited(i);edit.put("categories",List.of("Categoria humana"));book.saveInvoice(i.id,edit);i.excluded=true;store.flush();
    var ai=Json.object(doc.draft);ai.put("supplierTaxId","123456789");ai.put("categories",List.of("Categoria da IA"));var alert=proposal(doc,ai);
    repairs.apply(alert.id,request(doc,alert,List.of(),List.of("root"),true),documents);
    assertThat(book.invoiceView(i).get("categories")).isEqualTo(List.of("Categoria humana"));assertThat(i.excluded).isTrue();
    assertThat(store.all(LedgerEntry.class)).isEmpty();
    assertThat(store.all(pt.conta.audit.AuditEvent.class)).anySatisfy(a->{assertThat(a.action).isEqualTo("APPLY_AI_FIELDS");assertThat(a.afterValue).contains("HUMAN_ACCEPTED_AI",Json.object(alert.proposal).get("jobId").toString());});
  }

  @Test void validatingASiblingDoesNotCompleteADocumentWithAnUnvalidatedReplacementDraft() {
    var doc=document("INVOICE",map("invoices",List.of(invoice("VALID-A"),invoice("VALID-B")),"validate",false));
    for(var i:store.all(Invoice.class)){var input=edited(i);input.remove("categories");input.put("validate",true);book.saveInvoice(i.id,input);}
    var children=rows(Json.object(doc.draft),"invoices");String first=children.get(0).get("id").toString();UUID second=UUID.fromString(children.get(1).get("id").toString());
    var ai=Json.object(doc.draft);rows(ai,"invoices").getFirst().put("total","150.00");var alert=proposal(doc,ai);
    repairs.apply(alert.id,request(doc,alert,List.of(first+":total"),List.of(first),false),documents);
    assertThat(rows(Json.object(doc.draft),"invoices").getFirst()).containsEntry("validate",false);
    var input=edited(store.get(Invoice.class,second));input.remove("categories");input.put("validate",true);book.saveInvoice(second,input);
    assertThat(doc.status).isEqualTo("OPEN");assertThat(store.get(Invoice.class,UUID.fromString(first)).total).isEqualByComparingTo("100.00");
  }
}
