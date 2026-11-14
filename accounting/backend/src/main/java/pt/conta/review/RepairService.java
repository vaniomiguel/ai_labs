package pt.conta.review;

import static pt.conta.shared.Inputs.*;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.document.*;
import pt.conta.invoice.*;
import pt.conta.jobs.JobQueue;
import pt.conta.ledger.BookkeepingService;
import pt.conta.shared.*;
import pt.conta.shared.Types.InvoiceType;

/** Owns repair requests and results without publishing or modifying financial records. */
@Service
@Transactional
public class RepairService {
  public record Snapshot(UUID alertId, UUID documentId, long sourceVersion, String kind,
      String mediaType, String storageKey, String sha256, String text, Map<String,Object> draft,
      String message) {}
  private final Store store;
  private final JdbcTemplate jdbc;
  private final JobQueue jobs;
  private final BookkeepingService book;

  public RepairService(Store store, JdbcTemplate jdbc, JobQueue jobs, BookkeepingService book) {
    this.store=store; this.jdbc=jdbc; this.jobs=jobs; this.book=book;
  }

  public Map<String,Object> request(UUID id) {
    store.lockFinancialWrites();
    var alert=store.get(Alert.class,id);
    if (alert.documentId==null) throw DomainException.invalid("Este alerta não tem um documento para análise");
    if (!alert.status.equals("OPEN")) throw DomainException.invalid("O alerta já está resolvido");
    var doc=store.get(SourceDocument.class,alert.documentId);
    if (doc.deleted) throw DomainException.invalid("O documento já foi eliminado");
    var current=latest(id);
    if (current!=null && active(current) && ((Number)current.get("target_version")).longValue()==alert.version
        && sourceVersion(current,doc.version)==doc.version) return view(alert,current);
    alert.proposal=null; alert.updatedAt=Instant.now(); store.flush();
    jobs.enqueue("REPAIR_ALERT",id,alert.version,Json.write(map("sourceVersion",doc.version,"documentId",doc.id)));
    return view(alert,latest(id));
  }

  @Transactional(readOnly=true)
  public Map<String,Object> view(UUID id) { return view(store.get(Alert.class,id),latest(id)); }

  @Transactional(readOnly=true)
  public List<Map<String,Object>> list() {
    var latest=new HashMap<UUID,Map<String,Object>>();
    jdbc.queryForList("select distinct on (target_id) * from job where kind='REPAIR_ALERT' order by target_id,target_version desc,created_at desc,id desc")
        .forEach(j->latest.put((UUID)j.get("target_id"),j));
    return store.all(Alert.class).stream().sorted(Comparator.comparing((Alert a)->!a.status.equals("OPEN"))
        .thenComparing(a->a.createdAt,Comparator.reverseOrder())).map(a->view(a,latest.get(a.id))).toList();
  }

  private Map<String,Object> latest(UUID id) {
    return jdbc.queryForList("select * from job where kind='REPAIR_ALERT' and target_id=? order by target_version desc,created_at desc,id desc limit 1",id)
        .stream().findFirst().orElse(null);
  }
  private Map<String,Object> metadata(Map<String,Object> job) { return Json.object(Objects.toString(job.get("payload"),"{}")); }
  private long sourceVersion(Map<String,Object> job,long fallback) {
    var value=metadata(job).get("sourceVersion");return value instanceof Number n?n.longValue():fallback;
  }
  private boolean active(Map<String,Object> job) { return Set.of("QUEUED","RUNNING","WAITING").contains(job.get("state")); }

  private Map<String,Object> view(Alert alert,Map<String,Object> job) {
    Map<String,Object> proposal=alert.proposal==null?null:Json.object(alert.proposal);
    Map<String,Object> repair=null;
    if(job!=null) {
      var doc=alert.documentId==null?null:store.get(SourceDocument.class,alert.documentId);
      long source=sourceVersion(job,proposal!=null&&proposal.get("documentVersion") instanceof Number n?n.longValue():doc==null?-1:doc.version);
      boolean stale=doc==null||doc.deleted||!alert.status.equals("OPEN")||source!=doc.version
          ||active(job)&&metadata(job).get("outcome")==null&&((Number)job.get("target_version")).longValue()!=alert.version;
      var errors=proposal==null||doc==null?List.<String>of():blocking(doc,proposal);
      String outcome=stale?"STALE":Objects.toString(metadata(job).get("outcome"),null);
      if(outcome==null&&"DONE".equals(job.get("state"))) outcome=proposal==null?"REVIEW_REQUIRED":outcome(doc,proposal,errors);
      repair=map("jobId",job.get("id"),"state",job.get("state"),"outcome",outcome,
          "sourceVersion",source,"error",job.get("error"),"attempts",job.get("attempts"),
          "createdAt",instant(job.get("created_at")),"updatedAt",instant(job.get("updated_at")),
          "availableAt",instant(job.get("available_at")),"blockingIssues",errors,
          "canApply",!stale&&"DONE".equals(job.get("state"))&&proposal!=null&&proposal.containsKey("draft")&&errors.isEmpty()
              &&!"NO_CHANGES".equals(outcome)&&!"STALE".equals(outcome),"context",metadata(job).get("context"));
    }
    return map("id",alert.id,"version",alert.version,"documentId",alert.documentId,"entityType",alert.entityType,
        "entityId",alert.entityId,"code",alert.code,"message",alert.message,"status",alert.status,
        "proposal",proposal,"resolvedBy",alert.resolvedBy,"resolvedAt",alert.resolvedAt,"repair",repair);
  }

  private Instant instant(Object value) { return value instanceof java.sql.Timestamp t?t.toInstant():value instanceof Instant i?i:null; }

  /** Snapshots all source content in a short transaction before any provider call. */
  public Snapshot begin(JobQueue.Claim claim) {
    store.lockFinancialWrites();
    if(!ownsLease(claim))return null;
    var job=latest(claim.target());
    if(job==null||!job.get("id").equals(claim.id())) { mark(claim,"STALE",null);return null; }
    if(metadata(job).get("outcome")!=null) return null; // Already applied before a lease was reclaimed.
    var alert=store.get(Alert.class,claim.target());
    if(alert.documentId==null||!alert.status.equals("OPEN")||alert.version!=claim.revision()) {mark(claim,"STALE",null);return null;}
    var doc=store.get(SourceDocument.class,alert.documentId);
    if(doc.deleted||sourceVersion(job,doc.version)!=doc.version) {mark(claim,"STALE",null);return null;}
    var data=metadata(job);data.put("sourceVersion",doc.version);
    jdbc.update("update job set payload=? where id=?",Json.write(data),claim.id());
    return new Snapshot(alert.id,doc.id,doc.version,doc.kind,doc.mediaType,doc.storageKey,doc.sha256,
        Objects.requireNonNullElse(doc.text,""),Json.object(doc.draft),alert.message);
  }

  /** Atomically links a result to the exact live request; stale results never replace proposals. */
  public boolean complete(JobQueue.Claim claim,Snapshot snapshot,Map<String,Object> response,Map<String,Object> context) {
    store.lockFinancialWrites();
    var current=latest(claim.target());
    if(current!=null&&current.get("id").equals(claim.id())&&metadata(current).get("outcome")!=null) return false;
    var alert=store.get(Alert.class,claim.target());var doc=store.get(SourceDocument.class,snapshot.documentId());
    if(current==null||!current.get("id").equals(claim.id())||!ownsLease(claim)||!alert.status.equals("OPEN")
        ||alert.version!=claim.revision()||doc.deleted||doc.version!=snapshot.sourceVersion()) {
      if(ownsLease(claim)){mark(claim,"STALE",context);attempt(snapshot,response,context,"STALE");}return false;
    }
    var proposal=new LinkedHashMap<>(response);
    if(response.get("draft") instanceof Map<?,?> raw) {
      var draft=DraftSanitizer.sanitize(doc.kind,Json.object(Json.write(raw)));
      var issues=new LinkedHashSet<Object>();
      if(response.get("issues") instanceof List<?> list)issues.addAll(list);
      if(draft.get("issues") instanceof List<?> list)issues.addAll(list);
      draft.put("issues",new ArrayList<>(issues));proposal.put("draft",draft);proposal.put("issues",new ArrayList<>(issues));
    }
    proposal.put("documentVersion",snapshot.sourceVersion());proposal.put("jobId",claim.id().toString());
    var errors=blocking(doc,proposal);String outcome=outcome(doc,proposal,errors);
    alert.proposal=Json.write(proposal);mark(claim,outcome,context);attempt(snapshot,proposal,context,outcome);return true;
  }

  private void attempt(Snapshot snapshot,Map<String,Object> result,Map<String,Object> context,String outcome) {
    var attempt=new ExtractionAttempt();attempt.documentId=snapshot.documentId();
    attempt.engine="IA · "+Objects.toString(context==null?null:context.get("model"),"Correção");attempt.outcome=outcome;
    attempt.result=Json.write(result);attempt.evidence=Json.write(map("context",context,"evidence",result.get("evidence")));
    store.add(attempt);
  }

  private boolean ownsLease(JobQueue.Claim claim) {
    if(claim.token()==null)return true;
    return Boolean.TRUE.equals(jdbc.queryForObject("select lease_token=? and state='RUNNING' from job where id=? for update",Boolean.class,claim.token(),claim.id()));
  }
  private void mark(JobQueue.Claim claim,String outcome,Map<String,Object> context) {
    if(!ownsLease(claim))return;
    var job=jdbc.queryForList("select * from job where id=?",claim.id()).stream().findFirst().orElse(null);
    if(job==null)return;
    var data=metadata(job);data.put("outcome",outcome);if(context!=null)data.put("context",context);
    jdbc.update("update job set payload=?,updated_at=now() where id=?",Json.write(data),claim.id());
  }

  public void context(JobQueue.Claim claim,Map<String,Object> context) {
    store.lockFinancialWrites();
    var rows=jdbc.queryForList("select * from job where id=?",claim.id());
    if(rows.isEmpty()||!ownsLease(claim))return;
    var data=metadata(rows.getFirst());data.put("context",context);
    jdbc.update("update job set payload=?,updated_at=now() where id=?",Json.write(data),claim.id());
  }

  private String outcome(SourceDocument doc,Map<String,Object> proposal,List<String> errors) {
    if(!proposal.containsKey("draft")||!errors.isEmpty()||proposal.get("issues") instanceof List<?> list&&!list.isEmpty())return "REVIEW_REQUIRED";
    return business(proposal.get("draft")).equals(business(Json.value(doc.draft)))?"NO_CHANGES":"READY";
  }
  private Object business(Object value) {
    if(value instanceof Map<?,?> input) {
      var result=new TreeMap<String,Object>();input.forEach((k,v)->{if(!Set.of("validate","issues","id","version","documentId").contains(k.toString())&&v!=null&&!(v instanceof List<?> l&&l.isEmpty()))result.put(k.toString(),business(v));});return result;
    }
    if(value instanceof List<?> list)return list.stream().map(this::business).toList();
    return value;
  }

  /** Publication remains authoritative; this preview reuses its invoice arithmetic rules. */
  private List<String> blocking(SourceDocument doc,Map<String,Object> proposal) {
    if(!(proposal.get("draft") instanceof Map<?,?> raw))return List.of("A análise não contém uma correção de dados aplicável");
    var draft=Json.object(Json.write(raw));var errors=new ArrayList<String>();
    if(!doc.kind.equals("INVOICE"))return errors;
    var receipts=rows(draft,"invoices");if(receipts.isEmpty())receipts=List.of(draft);
    var existing=store.all(Invoice.class).stream().filter(i->doc.id.equals(i.documentId)&&!i.deleted).toList();
    for(int n=0;n<receipts.size();n++) {
      try {
        var row=receipts.get(n);var i=new Invoice();if(n<existing.size())i.id=existing.get(n).id;
        i.documentId=doc.id;i.issuedOn=date(row,"issuedOn");i.supplier=text(row,"supplier");i.number=text(row,"number");
        i.supplierTaxId=text(row,"supplierTaxId");i.total=Money.optional(row.get("total"));i.net=Money.optional(row.get("net"));
        i.tax=Money.optional(row.get("tax"));i.discount=Money.optional(row.get("discount"));i.currency=Objects.requireNonNullElse(text(row,"currency"),"EUR");
        i.type=enumeration(row,"type",InvoiceType.class,InvoiceType.INVOICE);i.relatedInvoiceId=uuid(row,"relatedInvoiceId");
        var lines=rows(row,"lines").stream().map(line->{var l=new InvoiceLine();l.total=Money.optional(line.get("total"));l.net=Money.optional(line.get("net"));l.tax=Money.optional(line.get("tax"));return l;}).toList();
        for(var error:book.invoiceErrors(i,lines))errors.add((receipts.size()>1?"Comprovativo "+(n+1)+": ":"")+error);
      }catch(RuntimeException e){errors.add("Comprovativo "+(n+1)+": dados com formato inválido");}
    }
    return errors;
  }

  public void assertApplicable(UUID id) {
    store.lockFinancialWrites();var alert=store.get(Alert.class,id);
    if(!alert.status.equals("OPEN"))throw DomainException.invalid("O alerta já está resolvido");
    var view=view(id);var repair=(Map<?,?>)view.get("repair");
    if(repair!=null&&!Boolean.TRUE.equals(repair.get("canApply"))) {
      if("STALE".equals(repair.get("outcome")))throw new DomainException(409,"O documento foi alterado; pede uma nova análise");
      throw DomainException.invalid("A proposta ainda não pode ser aplicada. Consulta os dados em falta e o estado da análise.");
    }
  }
}
