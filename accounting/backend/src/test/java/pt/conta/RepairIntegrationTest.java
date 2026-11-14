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
    public List<Model> models(){return List.of(new Model("qwen3.5:9b","repair-test-digest",vision,true,true));}
    public String complete(Request request){calls++;return program.apply(request);}
  }
  @Autowired Store store;@Autowired RepairService repairs;@Autowired RepairAiWorker worker;
  @Autowired FakeProvider provider;@Autowired DocumentStorage storage;@Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;@Autowired JobQueue queue;
  @Autowired pt.conta.api.DocumentController controller;
  @Autowired AiOrchestrator orchestrator;
  private record Fixture(UUID doc,UUID alert){}

  @BeforeEach void setup(){provider.calls=0;provider.vision=false;provider.program=request->Json.write(response(valid(),List.of()));}
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
  private Map<String,Object> task(Map<String,Object> alert){return Json.object(Json.write(alert.get("repair")));}
  private JobQueue.Claim request(Fixture f){
    var view=repairs.request(f.alert);UUID id=UUID.fromString(task(view).get("jobId").toString());
    return new JobQueue.Claim(id,"REPAIR_ALERT",f.alert,((Number)view.get("version")).longValue(),"{}",0);
  }
  private void process(JobQueue.Claim claim)throws Exception {worker.process(claim);jdbc.update("update job set state='DONE' where id=?",claim.id());}
  private void assertNoMoney(){assertThat(store.all(LedgerEntry.class)).isEmpty();assertThat(store.all(pt.conta.invoice.Invoice.class)).isEmpty();}

  @Test void requestReturnsAcceptedAndRepeatedClicksReuseTheActiveJob()throws Exception {
    var f=fixture();var accepted=controller.repair(f.alert);
    assertThat(accepted.getStatusCode().value()).isEqualTo(202);var first=accepted.getBody();
    var second=controller.repair(f.alert).getBody();
    assertThat(task(second).get("jobId")).isEqualTo(task(first).get("jobId"));
    assertThat(second.get("version")).isEqualTo(first.get("version"));
    assertThat(task(controller.alert(f.alert)).get("state")).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("select count(*) from job where target_id=?",Integer.class,f.alert)).isEqualTo(1);assertNoMoney();
  }

  @Test void fullTextLongerThanOldChunksReachesTheProviderUnchanged()throws Exception {
    String source="Documento "+"texto completo ".repeat(2250)+" FIM";
    var f=fixture(source,1,"{\"pages\":[{\"page\":1,\"text\":\"excerto incompleto\"}]}");
    provider.program=request->{assertThat(Json.read(request.input()).path("sourceText").asText()).isEqualTo(source);
      assertThat(request.input()).contains("Falta a data da fatura");assertThat(request.images()).isEmpty();assertThat(request.requireFullContext()).isTrue();return Json.write(response(valid(),List.of()));};
    process(request(f));assertThat(provider.calls).isEqualTo(1);assertNoMoney();
  }

  @Test void aVisualModelReceivesEveryRealPdfPageWithoutLayoutMetadata()throws Exception {
    var f=fixture("Texto de todas as páginas",4,"{}");provider.vision=true;
    provider.program=request->{assertThat(request.images()).hasSize(4);
      for(var encoded:request.images())assertThat(Base64.getDecoder().decode(encoded)).startsWith((byte)137,(byte)80,(byte)78,(byte)71);
      assertThat(Json.read(request.input()).path("sourcePages").toString()).isEqualTo("[1,2,3,4]");
      return Json.write(response(valid(),List.of()));};
    process(request(f));var context=(Map<?,?>)task(repairs.view(f.alert)).get("context");
    assertThat(context.get("textComplete")).isEqualTo(true);assertThat((List<?>)context.get("pages")).hasSize(4);assertNoMoney();
    assertThat(controller.attempts(f.doc).getFirst().get("engine").toString()).contains("qwen3.5:9b");
  }

  @Test void incompleteLayoutCannotOmitOtherPdfPages()throws Exception {
    var f=fixture("Texto integral",3,"{\"pages\":[{\"page\":1,\"text\":\"Primeira página\"}]}");provider.vision=true;
    provider.program=request->{assertThat(request.images()).hasSize(3);return Json.write(response(valid(),List.of()));};
    process(request(f));
  }

  @Test void textOnlyModelGetsAllTextWithoutImages()throws Exception {
    var f=fixture("Texto completo de três páginas",3,"{}");
    provider.program=request->{assertThat(request.images()).isEmpty();assertThat(Json.read(request.input()).path("sourceText").asText()).isEqualTo("Texto completo de três páginas");return Json.write(response(valid(),List.of()));};
    process(request(f));assertThat(((Map<?,?>)task(repairs.view(f.alert)).get("context")).get("vision")).isEqualTo(false);
  }

  @Test void excessivePageCountFailsWithoutSendingAPartialSetOfImages()throws Exception {
    var f=fixture("Texto completo",17,"{}");provider.vision=true;
    assertThatThrownBy(()->worker.process(request(f))).isInstanceOf(AiProvider.ContextExceeded.class).hasMessageContaining("Nenhuma página foi omitida");
    assertThat(provider.calls).isZero();assertNoMoney();
  }

  @Test void readyProposalDoesNotPublishBeforeExplicitConfirmation()throws Exception {
    var f=fixture();process(request(f));var view=repairs.view(f.alert);
    assertThat(task(view)).containsEntry("outcome","READY").containsEntry("canApply",true);
    assertThat(store.get(SourceDocument.class,f.doc).draft).doesNotContain("100.00");assertNoMoney();
    controller.apply(f.alert);
    assertThat(store.all(LedgerEntry.class)).hasSize(1);assertThat(store.get(Alert.class,f.alert).status).isEqualTo("RESOLVED");
  }

  @Test void missingRequiredFieldsAndWarningsProduceAnExplicitReviewResult()throws Exception {
    var f=fixture();provider.program=request->{var draft=valid();draft.put("issuedOn",null);return Json.write(response(draft,List.of("Data ilegível")));};
    process(request(f));var task=task(repairs.view(f.alert));
    assertThat(task).containsEntry("outcome","REVIEW_REQUIRED").containsEntry("canApply",false);
    assertThat(task.get("blockingIssues").toString()).contains("Falta a data");
    assertThatThrownBy(()->controller.apply(f.alert)).isInstanceOf(DomainException.class);assertNoMoney();
  }

  @Test void unchangedBusinessDataIsReportedWithoutAnotherApplication()throws Exception {
    var f=fixture();store.get(SourceDocument.class,f.doc).draft=Json.write(valid());store.flush();
    process(request(f));assertThat(task(repairs.view(f.alert))).containsEntry("outcome","NO_CHANGES").containsEntry("canApply",false);assertNoMoney();
  }

  @Test void editingTheSourceWhileTheModelRunsMakesTheResultStale()throws Exception {
    var f=fixture();provider.program=request->{var doc=store.get(SourceDocument.class,f.doc);doc.text="Texto corrigido";store.flush();return Json.write(response(valid(),List.of()));};
    process(request(f));assertThat(task(repairs.view(f.alert))).containsEntry("outcome","STALE").containsEntry("canApply",false);
    assertThat(store.get(Alert.class,f.alert).proposal).isNull();assertNoMoney();
  }

  @Test void resolvingTheAlertWhileTheModelRunsCannotRestoreTheProposal()throws Exception {
    var f=fixture();provider.program=request->{store.get(Alert.class,f.alert).status="RESOLVED";store.flush();return Json.write(response(valid(),List.of()));};
    process(request(f));assertThat(task(repairs.view(f.alert)).get("outcome")).isEqualTo("STALE");assertThat(store.get(Alert.class,f.alert).proposal).isNull();
  }

  @Test void newRequestAfterSourceEditSupersedesTheOlderRunningResult()throws Exception {
    var f=fixture();var old=request(f);UUID[] newer={null};
    provider.program=request->{var doc=store.get(SourceDocument.class,f.doc);doc.text="Dados novos";store.flush();newer[0]=request(f).id();return Json.write(response(valid(),List.of()));};
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
    provider.program=request->{throw new OllamaProvider.AiUnavailable("Modelo indisponível",null);};
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
    provider.program=request->"{\"draft\":\"inválido\"}";
    assertThatThrownBy(()->worker.process(claim)).isInstanceOf(IllegalArgumentException.class);
    queue.finish(claim,"Resposta estruturada inválida",false);
    assertThat(task(repairs.view(f.alert))).containsEntry("state","FAILED").containsEntry("error","Resposta estruturada inválida");
    assertThat(store.get(Alert.class,f.alert).proposal).isNull();assertNoMoney();
  }

  @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
  void simultaneousRequestsCreateOnlyOneJob()throws Exception {
    var f=transactions.execute(tx->{try{return fixture();}catch(Exception e){throw new RuntimeException(e);}});
    try(var pool=Executors.newFixedThreadPool(2)) {
      var start=new CountDownLatch(1);
      Callable<String> request=()->{start.await();return task(repairs.request(f.alert)).get("jobId").toString();};
      var a=pool.submit(request);var b=pool.submit(request);start.countDown();
      assertThat(a.get(20,TimeUnit.SECONDS)).isEqualTo(b.get(20,TimeUnit.SECONDS));
      assertThat(jdbc.queryForObject("select count(*) from job where target_id=?",Integer.class,f.alert)).isEqualTo(1);
    }finally{jdbc.update("update job set state='DONE' where target_id=?",f.alert);}
  }
}
