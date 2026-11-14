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
      String message, Map<String,Object> fieldProvenance) {}
  private final Store store;
  private final JdbcTemplate jdbc;
  private final JobQueue jobs;
  private final BookkeepingService book;
  private final FieldProtection protection;
  private final DocumentPublisher publisher;

  public RepairService(Store store, JdbcTemplate jdbc, JobQueue jobs, BookkeepingService book, FieldProtection protection, DocumentPublisher publisher) {
    this.store=store; this.jdbc=jdbc; this.jobs=jobs; this.book=book;this.protection=protection;this.publisher=publisher;
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
      var errors=proposal==null||doc==null?List.<String>of():previewErrors(doc,proposal);
      if(proposal!=null&&doc!=null)enrich(doc,proposal);
      String outcome=stale?"STALE":Objects.toString(metadata(job).get("outcome"),null);
      if(outcome==null&&"DONE".equals(job.get("state"))) outcome=proposal==null?"REVIEW_REQUIRED":outcome(doc,proposal,errors);
      Map<String,Object> eligibility=Map.of();
      if(!stale&&"DONE".equals(job.get("state"))&&!"STALE".equals(outcome)&&proposal!=null&&proposal.containsKey("draft")) {
        try{eligibility=prepare(alert.id,Map.of());}catch(DomainException ignored){}
      }
      repair=map("jobId",job.get("id"),"state",job.get("state"),"outcome",outcome,
          "sourceVersion",source,"error",job.get("error"),"attempts",job.get("attempts"),
          "createdAt",instant(job.get("created_at")),"updatedAt",instant(job.get("updated_at")),
          "availableAt",instant(job.get("available_at")),"blockingIssues",errors,
          "canApplyDraft",Boolean.TRUE.equals(eligibility.get("canApplyDraft")),
          "canValidate",Boolean.TRUE.equals(eligibility.get("canValidate")),
          "canApply",Boolean.TRUE.equals(eligibility.get("canValidate")),"context",metadata(job).get("context"));
    }
    if(proposal!=null&&job==null&&alert.documentId!=null)enrich(store.get(SourceDocument.class,alert.documentId),proposal);
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
        Objects.requireNonNullElse(doc.text,""),Json.object(doc.draft),alert.message,Json.object(doc.fieldProvenance));
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
    var errors=previewErrors(doc,proposal);String outcome=outcome(doc,proposal,errors);
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
    try{return protection.changes(doc.kind,Json.object(doc.draft),protection.copy(proposal.get("draft")),Json.object(doc.fieldProvenance)).isEmpty()?"NO_CHANGES":"READY";}
    catch(DomainException e){return "REVIEW_REQUIRED";}
  }

  private void enrich(SourceDocument doc,Map<String,Object> proposal) {
    if(!(proposal.get("draft") instanceof Map<?,?> raw))return;
    try {proposal.put("changes",protection.changes(doc.kind,Json.object(doc.draft),protection.copy(raw),Json.object(doc.fieldProvenance)));}
    catch(DomainException e){proposal.put("changes",List.of());proposal.put("structureIssue",e.getMessage());}
  }
  private List<String> previewErrors(SourceDocument doc,Map<String,Object> proposal) {
    if(!(proposal.get("draft") instanceof Map<?,?> raw))return List.of("A análise não contém uma correção de dados aplicável");
    try {return blocking(doc,map("draft",protection.automaticDocument(doc.kind,Json.object(doc.draft),protection.copy(raw),Json.object(doc.fieldProvenance))));}
    catch(DomainException e){return List.of(e.getMessage());}
  }

  /** A preview has no writes; acceptance is evaluated again under the financial write lock. */
  @Transactional(readOnly=true)
  public Map<String,Object> preview(UUID id,Map<String,Object> body) {return prepare(id,body);}

  private Map<String,Object> prepare(UUID id,Map<String,Object> body) {
    var alert=store.get(Alert.class,id);
    if(!alert.status.equals("OPEN")||alert.documentId==null||alert.proposal==null)throw DomainException.invalid("Não existe uma proposta aplicável");
    var doc=store.get(SourceDocument.class,alert.documentId);var proposal=Json.object(alert.proposal);
    if(!body.isEmpty()&&(!body.containsKey("documentVersion")||!body.containsKey("proposalId")))
      throw new DomainException(428,"A versão documental e a identidade da proposta são obrigatórias");
    if(doc.deleted||!(proposal.get("documentVersion") instanceof Number n)||n.longValue()!=doc.version)
      throw new DomainException(409,"O documento foi alterado; guarda as tuas edições e pede uma nova análise.");
    if(body.containsKey("documentVersion"))store.checkVersion(doc,version(map("version",body.get("documentVersion"))));
    if(body.containsKey("proposalId")&&!Objects.equals(Objects.toString(proposal.get("jobId"),alert.id.toString()),body.get("proposalId")))
      throw new DomainException(409,"Esta proposta foi substituída. Atualiza a análise antes de aplicar.");
    var job=latest(id);
    if(job!=null&&"STALE".equals(metadata(job).get("outcome")))throw new DomainException(409,"Esta proposta está desatualizada; pede uma nova análise.");
    if(job!=null&&!"DONE".equals(job.get("state")))throw DomainException.invalid("A análise ainda não terminou com sucesso");
    if(!(proposal.get("draft") instanceof Map<?,?> raw))throw DomainException.invalid("A análise não contém uma correção de dados aplicável");
    var before=Json.object(doc.draft);var local=body.get("draft") instanceof Map<?,?> edits?protection.align(doc.kind,before,protection.copy(edits)):protection.copy(before);
    // Only editable fields may cross from a browser draft; identifiers/control metadata remain server-owned.
    var current=protection.copy(before);var oldRows=protection.entries(doc.kind,current);var localRows=protection.entries(doc.kind,local);
    var allowedItems=new LinkedHashSet<String>();
    if(body.get("selectedItems") instanceof List<?> items) {
      for(Object item:items) {
        if(!(item instanceof String key)||oldRows.stream().noneMatch(r->protection.rowKey(r,protection.root(doc.kind,current)).equals(key)))throw DomainException.invalid("O item indicado não pertence a este documento");
        allowedItems.add(key);
      }
    }else if(body.containsKey("selectedItems"))throw DomainException.invalid("A seleção de itens é inválida");
    else for(var row:oldRows)if(alert.entityType.equals("document")||Objects.equals(text(row,"id"),alert.entityId.toString()))allowedItems.add(protection.rowKey(row,protection.root(doc.kind,current)));
    if(allowedItems.isEmpty()&&!body.containsKey("selectedItems")&&oldRows.size()==1)allowedItems.add(protection.rowKey(oldRows.getFirst(),protection.root(doc.kind,current)));
    for(int i=0;i<oldRows.size();i++)if(allowedItems.contains(protection.rowKey(oldRows.get(i),protection.root(doc.kind,current))))
      for(String field:protection.fields(doc.kind))if(localRows.get(i).containsKey(field))oldRows.get(i).put(field,localRows.get(i).get(field));
    var metadata=protection.humanDocument(doc.kind,before,current,Json.object(doc.fieldProvenance),false);
    var changes=protection.changes(doc.kind,current,protection.copy(raw),metadata);
    var selected=new LinkedHashSet<String>();
    if(body.get("acceptedFields") instanceof List<?> requested)for(Object key:requested){if(!(key instanceof String value)||!selected.add(value))throw DomainException.invalid("A seleção de campos é inválida");}
    else if(body.containsKey("acceptedFields"))throw DomainException.invalid("A seleção de campos é inválida");
    var available=changes.stream().map(c->c.get("key").toString()).collect(java.util.stream.Collectors.toSet());
    if(!available.containsAll(selected)||changes.stream().anyMatch(c->selected.contains(c.get("key").toString())&&!allowedItems.contains(c.get("itemId").toString())))throw DomainException.invalid("A seleção contém um campo que não pertence a esta proposta");
    var touched=new LinkedHashSet<String>();var accepted=new ArrayList<Map<String,Object>>();
    for(int i=0;i<oldRows.size();i++)for(String field:protection.fields(doc.kind))if(!protection.same(field,protection.entries(doc.kind,before).get(i).get(field),oldRows.get(i).get(field)))touched.add(protection.rowKey(oldRows.get(i),protection.root(doc.kind,current)));
    String proposalId=Objects.toString(proposal.get("jobId"),alert.id.toString());
    for(var change:changes)if(allowedItems.contains(change.get("itemId").toString())&&(change.get("kind").equals("COMPLEMENT")||selected.contains(change.get("key").toString()))) {
      String item=change.get("itemId").toString(),field=change.get("field").toString();
      var row=oldRows.stream().filter(r->protection.rowKey(r,protection.root(doc.kind,current)).equals(item)).findFirst().orElseThrow(protection::ambiguous);
      row.put(field,change.get("proposed"));var ownership=protection.rowMetadata(metadata,item);
      ownership.put(field,protection.decision("HUMAN_ACCEPTED_AI",proposalId));metadata.put(item,ownership);touched.add(item);accepted.add(change);
    }
    var errors=blocking(doc,map("draft",current),touched);
    return map("draft",current,"changes",changes,"fieldProvenance",metadata,"selectedItems",touched,"accepted",accepted,
        "blockingIssues",errors,"canApplyDraft",!touched.isEmpty(),"canValidate",!touched.isEmpty()&&errors.isEmpty());
  }

  public Map<String,Object> apply(UUID id,Map<String,Object> body,DocumentService documents) {
    store.lockFinancialWrites();var prepared=prepare(id,body);
    if(!Boolean.TRUE.equals(prepared.get("canApplyDraft")))throw DomainException.invalid("Não existem complementos ou escolhas para aplicar");
    boolean validate=body.isEmpty()||flag(body,"validate");
    if(validate&&!Boolean.TRUE.equals(prepared.get("canValidate")))throw DomainException.invalid(String.join("; ",(List<String>)prepared.get("blockingIssues")));
    var alert=store.get(Alert.class,id);var selected=new LinkedHashSet<String>((Set<String>)prepared.get("selectedItems"));
    return publisher.applySelected(alert.documentId,protection.copy(prepared.get("draft")),protection.copy(prepared.get("fieldProvenance")),selected,validate,documents);
  }

  /** Publication remains authoritative; this preview reuses its invoice arithmetic rules. */
  private List<String> blocking(SourceDocument doc,Map<String,Object> proposal) {
    if(!(proposal.get("draft") instanceof Map<?,?> raw))return List.of("A análise não contém uma correção de dados aplicável");
    return blocking(doc,proposal,null);
  }
  private List<String> blocking(SourceDocument doc,Map<String,Object> proposal,Set<String> selected) {
    if(!(proposal.get("draft") instanceof Map<?,?> raw))return List.of("A análise não contém uma correção de dados aplicável");
    var draft=Json.object(Json.write(raw));var errors=new ArrayList<String>();
    if(doc.kind.equals("STATEMENT")) {
      for(var row:rows(draft,"movements")) {
        if(selected!=null&&!selected.contains(protection.rowKey(row,false)))continue;
        try {var m=new pt.conta.bank.BankMovement();m.accountId=row.get("accountId")==null?doc.accountId:uuid(row,"accountId");
          m.bookedOn=date(row,"bookedOn");m.description=text(row,"description");m.amount=Money.optional(row.get("amount"));
          m.currency=Objects.requireNonNullElse(text(row,"currency"),"EUR");m.direction=enumeration(row,"direction",pt.conta.shared.Types.Direction.class,null);
          errors.addAll(book.movementErrors(m));
        }catch(RuntimeException e){errors.add("Movimento com formato inválido");}
      }return errors;
    }
    if(!doc.kind.equals("INVOICE"))return errors;
    var receipts=rows(draft,"invoices");if(receipts.isEmpty())receipts=List.of(draft);
    var existing=store.all(Invoice.class).stream().filter(i->doc.id.equals(i.documentId)&&!i.deleted).toList();
    for(int n=0;n<receipts.size();n++) {
      try {
        var row=receipts.get(n);if(selected!=null&&!selected.contains(protection.rowKey(row,protection.root(doc.kind,draft))))continue;
        var i=new Invoice();if(n<existing.size())i.id=existing.get(n).id;
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

}
