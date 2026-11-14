package pt.conta;

import static pt.conta.shared.Inputs.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pt.conta.ai.AiExecution;
import pt.conta.bank.BankAccount;
import pt.conta.category.*;
import pt.conta.invoice.Category;
import pt.conta.jobs.JobQueue;
import pt.conta.ledger.BookkeepingService;
import pt.conta.shared.*;

/** Explicit real-model evaluation using synthetic reviewed cases and a disposable database. */
public final class CategoryModelEvaluation {
  private final CategoryService categories;
  private final CategoryAiWorker worker;
  private final BookkeepingService book;
  private final Store store;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final List<Map<String,Object>> cases = new ArrayList<>();
  private final UUID account;

  private CategoryModelEvaluation(ConfigurableApplicationContext context) {
    categories=context.getBean(CategoryService.class);worker=context.getBean(CategoryAiWorker.class);
    book=context.getBean(BookkeepingService.class);store=context.getBean(Store.class);
    jdbc=context.getBean(JdbcTemplate.class);transactions=context.getBean(TransactionTemplate.class);
    account=transactions.execute(tx->{var a=new BankAccount();a.name="Avaliação sintética de categorias";return store.add(a).id;});
  }
  private UUID reference(String code) {
    return transactions.execute(tx->store.all(Category.class).stream().filter(c->code.equals(c.referenceCode)).findFirst().orElseThrow().id);
  }
  private UUID invoice(String description) {
    return (UUID)book.saveInvoice(null,map("issuedOn","2026-01-01","supplier","Empresa de teste",
        "number",UUID.randomUUID().toString(),"description",description,"total","100.00","validate",true)).get("id");
  }
  private UUID movement(String description) {
    return (UUID)book.saveMovement(null,map("accountId",account.toString(),"bookedOn","2026-01-02",
        "description",description,"amount","100.00","direction","DEBIT","validate",true)).get("id");
  }
  private List<String> names(String type,UUID id) {return categories.details(type,id).stream().map(c->c.get("name").toString()).toList();}
  private boolean hasName(String type,UUID id,String text) {return names(type,id).stream().anyMatch(n->pt.conta.reconciliation.MatchingRules.normalize(n).equals(pt.conta.reconciliation.MatchingRules.normalize(text)));}
  private Map<String,Object> ledger() {return jdbc.queryForMap("select count(*) as postings,coalesce(sum(amount),0)::text as amount from ledger_entry");}

  private void run(String name,String type,UUID id,Predicate<Map<String,Object>> check) {
    long started=System.nanoTime();String error=null;boolean passed=false;var before=ledger();
    try {
      String kind=type.equals("catalog")?"REFINE_CATEGORIES":type.equals("invoice")?"CLASSIFY_INVOICE":"CLASSIFY_MOVEMENT";
      UUID job=jdbc.queryForList("select id from job where target_id=? and kind=? order by created_at desc limit 1",UUID.class,id,kind).stream().findFirst().orElse(UUID.randomUUID());
      worker.process(new JobQueue.Claim(job,kind,id,0,"{}",0));
      jdbc.update("update job set state='DONE' where target_id=? and kind=?",id,kind);
      var view=type.equals("catalog")?map("catalog",categories.catalog(true)):categories.view(type,id);
      passed=check.test(view)&&before.equals(ledger());
      if(!passed)error="Resultado fora da referência manual ou alteração financeira";
    }catch(Exception e){error=e.getClass().getSimpleName()+": "+e.getMessage();}
    var current=type.equals("catalog")?map("catalog",categories.catalog(true)):categories.view(type,id);
    cases.add(map("case",name,"passed",passed,"durationMs",(System.nanoTime()-started)/1_000_000,"error",error,"actual",current));
    System.out.println("CATEGORY_EVAL "+name+" "+(passed?"PASS":"FAIL")+(error==null?"":" · "+error));
  }

  private void evaluate() {
    UUID original=invoice("Compra de cabo USB-C para ligação de computador");
    categories.apply(categories.snapshot("invoice",original),map("status","CLASSIFIED","explanation","Preparação de categoria automática específica",
        "categories",List.of(map("id","new:c","evidence","cabo USB-C")),"operations",List.of(map("kind","CREATE","sourceIds",List.of(),"key","new:c","name","Cabos USB-C",
            "parentId",reference("623").toString(),"targetId",null,"description","Cabos com ligação USB-C","evidence","cabo USB-C","reason","Catálogo inicial de avaliação"))),UUID.randomUUID());
    jdbc.update("update job set state='DONE' where kind in ('CLASSIFY_INVOICE','CLASSIFY_MOVEMENT')");
    UUID usbA=invoice("Compra de cabo USB-A para ligação de periférico ao computador");
    run("usb-a-generalizes-usb-c-history","invoice",usbA,v->hasName("invoice",original,"Cabos USB")&&hasName("invoice",usbA,"Cabos USB")
        &&categories.catalog(false).stream().noneMatch(c->c.get("name").toString().equalsIgnoreCase("Cabos USB-A")));
    UUID peripherals=invoice("Aquisição de dois artigos distintos: 1 cabo HDMI para monitor e 1 rato óptico para computador");
    run("hdmi-and-mouse-preserve-useful-subcategories","invoice",peripherals,v->names("invoice",peripherals).size()>=2&&hasName("invoice",original,"Cabos USB"));
    UUID insurance=movement("Prémio de seguro multirriscos - FIDELIDADE COMPANHI");
    run("bank-insurance","movement",insurance,v->categories.recordMatches("movement",insurance,reference("6263").toString()));
    UUID brandOnly=movement("FIDELIDADE COMPANHI");
    run("brand-only-bank-description-does-not-invent-details","movement",brandOnly,v->categories.recordMatches("movement",brandOnly,reference("6263").toString())
        ||names("movement",brandOnly).isEmpty()&&v.get("state").equals("REVIEW"));
    UUID accounting=movement("Contabilidade Janeiro");
    run("bank-accounting","movement",accounting,v->categories.recordMatches("movement",accounting,reference("622").toString()));
    UUID mixed=invoice("Compra de papel A4 para impressora e seguro multirriscos anual da empresa");
    run("mixed-paper-and-insurance","invoice",mixed,v->categories.recordMatches("invoice",mixed,reference("623").toString())&&categories.recordMatches("invoice",mixed,reference("6263").toString()));
    UUID ambiguous=movement("TRANSFERENCIA 001");
    run("ambiguous-bank-description-abstains","movement",ambiguous,v->names("movement",ambiguous).isEmpty()&&v.get("state").equals("REVIEW"));
    run("catalog-review-preserves-reference-families","catalog",CategoryCatalog.ID,v->hasName("invoice",original,"Cabos USB")
        &&categories.catalog(false).stream().filter(c->c.get("origin").equals("REFERENCE")).allMatch(c->Boolean.TRUE.equals(c.get("protected"))));
  }

  public static void main(String[] args) throws Exception {
    Path output=Path.of(args.length==0?"target/category-model-evaluation.json":args[0]).toAbsolutePath();
    boolean passed;
    try(var postgres=EmbeddedPostgres.builder().setPort(0).start()) {
      var app=new SpringApplication(AccountingApplication.class);app.setRegisterShutdownHook(false);
      try(var context=app.run("--spring.profiles.active=test","--server.port=0",
          "--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),"--spring.datasource.username=postgres","--spring.datasource.password=postgres",
          "--accounting.jobs-enabled=false","--accounting.storage="+Files.createTempDirectory("conta-category-eval"))) {
        var evaluation=new CategoryModelEvaluation(context);evaluation.evaluate();
        var executions=evaluation.transactions.execute(tx->evaluation.store.all(AiExecution.class).stream().map(e->map("id",e.id,"model",e.model,"modelDigest",e.modelDigest,
            "promptVersion",e.promptVersion,"outcome",e.outcome,"durationMs",e.durationMs,"input",Json.value(e.request),"response",Json.value(e.response))).toList());
        passed=evaluation.cases.stream().allMatch(c->Boolean.TRUE.equals(c.get("passed")));
        Files.createDirectories(output.getParent());Files.writeString(output,Json.write(map("passed",passed,"cases",evaluation.cases,"executions",executions)));
        System.out.println("CATEGORY_EVAL_REPORT "+output);
      }
    }
    if(!passed)System.exit(1);
  }
}
