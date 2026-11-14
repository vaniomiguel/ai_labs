package pt.conta.document;

import static pt.conta.shared.Inputs.*;
import java.util.*;
import org.springframework.stereotype.Service;
import pt.conta.bank.*;
import pt.conta.invoice.*;
import pt.conta.review.ReviewService;
import pt.conta.shared.*;
import pt.conta.shared.Types.ReviewStatus;

/** Synchronizes one human edit with its original without copying derived DTO fields or siblings. */
@Service
public class SourceRecordSync {
  public record Edit(SourceDocument document,Map<String,Object> draft,Map<String,Object> row) {}
  private final Store store;
  private final ReviewService review;
  private final FieldProtection protection;
  public SourceRecordSync(Store store,ReviewService review,FieldProtection protection){this.store=store;this.review=review;this.protection=protection;}

  public Edit prepare(Invoice invoice,Map<String,Object> input) {
    UUID document=input.containsKey("documentId")?uuid(input,"documentId"):invoice.documentId;
    var records=store.all(Invoice.class).stream().filter(i->!i.deleted&&Objects.equals(document,i.documentId)).toList();
    return prepare(document,input,invoice.id,invoiceFields(invoice),"INVOICE","invoices","invoice",
        records.stream().map(this::invoiceFields).toList());
  }
  public Edit prepare(BankMovement movement,Map<String,Object> input) {
    UUID document=input.containsKey("documentId")?uuid(input,"documentId"):movement.documentId;
    var records=store.all(BankMovement.class).stream().filter(m->!m.deleted&&Objects.equals(document,m.documentId)).toList();
    return prepare(document,input,movement.id,movementFields(movement),"STATEMENT","movements","movement",
        records.stream().map(this::movementFields).toList());
  }
  private Edit prepare(UUID id,Map<String,Object> input,UUID record,Map<String,Object> before,
      String kind,String collection,String type,List<Map<String,Object>> siblings) {
    if(id==null)return null;
    var doc=store.get(SourceDocument.class,id);
    if(input.containsKey("documentVersion")&&input.get("documentVersion")!=null) {
      if(!(input.get("documentVersion") instanceof Number n)||n.longValue()<0)throw DomainException.invalid("A versão do documento é inválida");
      store.checkVersion(doc,((Number)input.get("documentVersion")).longValue());
    }
    if(!kind.equals(doc.kind))return null;
    var draft=Json.object(doc.draft);var entries=rows(draft,collection);
    if(entries.isEmpty()&&kind.equals("INVOICE")) {
      String storedId=text(draft,"id");
      if(storedId!=null&&!storedId.equals(record.toString())||siblings.size()>1)throw ambiguous();
      return new Edit(doc,draft,draft);
    }
    var explicit=entries.stream().filter(row->record.toString().equals(text(row,"id"))).toList();
    if(explicit.size()==1)return new Edit(doc,draft,explicit.getFirst());
    if(explicit.size()>1)throw ambiguous();
    var legacy=entries.stream().filter(row->text(row,"id")==null).toList();
    if(entries.size()==1&&legacy.size()==1&&siblings.size()<=1)return new Edit(doc,draft,legacy.getFirst());
    String key=safeKey(before,type);
    if(key!=null&&siblings.stream().filter(row->key.equals(safeKey(row,type))).count()==1) {
      var matches=legacy.stream().filter(row->key.equals(safeKey(row,type))).toList();
      if(matches.size()==1)return new Edit(doc,draft,matches.getFirst());
    }
    String reference=text(before,"bankReference");
    if(type.equals("movement")&&reference!=null&&siblings.stream().filter(row->reference.equals(text(row,"bankReference"))).count()==1) {
      var matches=legacy.stream().filter(row->reference.equals(text(row,"bankReference"))).toList();
      if(matches.size()==1)return new Edit(doc,draft,matches.getFirst());
    }
    throw ambiguous();
  }
  private String safeKey(Map<String,Object> row,String type){try{return SourceIdentity.key(row,type);}catch(RuntimeException e){return null;}}
  private DomainException ambiguous(){return new DomainException(409,"Não foi possível identificar este item no rascunho original. Revê a extração do documento; nenhum outro item foi alterado.");}

  public void sync(Edit edit,Invoice invoice,List<String> errors) {
    if(edit==null)return;
    var fields=invoiceFields(invoice);
    fields.put("lines",store.all(InvoiceLine.class).stream().filter(l->invoice.id.equals(l.invoiceId))
        .sorted(Comparator.comparingInt(l->l.position)).map(l->map("description",l.description,"quantity",l.quantity==null?null:l.quantity.toPlainString(),
            "unitPrice",l.unitPrice==null?null:l.unitPrice.toPlainString(),"net",Money.text(l.net),"tax",Money.text(l.tax),"discount",Money.text(l.discount),"total",Money.text(l.total))).toList());
    boolean complete=store.all(Invoice.class).stream().filter(i->!i.deleted&&edit.document.id.equals(i.documentId)).allMatch(i->i.reviewStatus!=ReviewStatus.OPEN);
    copyProvenance(edit,invoice.fieldProvenance);
    finish(edit,fields,invoice.reviewStatus,errors,complete);
  }
  public void sync(Edit edit,BankMovement movement,List<String> errors) {
    if(edit==null)return;
    var siblings=store.all(BankMovement.class).stream().filter(m->!m.deleted&&edit.document.id.equals(m.documentId)).toList();
    boolean complete=siblings.stream().allMatch(m->m.reviewStatus!=ReviewStatus.OPEN);
    copyProvenance(edit,movement.fieldProvenance);
    finish(edit,movementFields(movement),movement.reviewStatus,errors,complete);
    if(movement.statementId!=null)store.get(BankStatement.class,movement.statementId).validated=edit.document.status.equals("DONE");
  }
  private void copyProvenance(Edit edit,String provenance) {
    var metadata=Json.object(edit.document.fieldProvenance);
    metadata.put(protection.rowKey(edit.row,edit.row==edit.draft),Json.object(provenance));
    edit.document.fieldProvenance=Json.write(metadata);
  }
  private void finish(Edit edit,Map<String,Object> fields,ReviewStatus status,List<String> errors,boolean complete) {
    edit.row.putAll(fields);edit.row.put("validate",status!=ReviewStatus.OPEN);
    edit.row.put("issues",status==ReviewStatus.OPEN?errors:List.of());
    complete=complete&&protection.entries(edit.document.kind,edit.draft).stream().noneMatch(row->Boolean.FALSE.equals(row.get("validate")));
    if(complete){edit.draft.put("issues",List.of());edit.draft.put("validate",true);review.resolveFor("document",edit.document.id);}
    else edit.draft.put("validate",false);
    edit.document.draft=Json.write(edit.draft);edit.document.status=complete?"DONE":"OPEN";
    store.flush();
  }
  private Map<String,Object> invoiceFields(Invoice i){return map("id",i.id.toString(),"issuedOn",i.issuedOn,"dueOn",i.dueOn,
      "number",i.number,"supplier",i.supplier,"supplierTaxId",i.supplierTaxId,"description",i.description,"currency",i.currency,
      "total",Money.text(i.total),"net",Money.text(i.net),"tax",Money.text(i.tax),"discount",Money.text(i.discount),
      "type",i.type,"relatedInvoiceId",i.relatedInvoiceId);}
  private Map<String,Object> movementFields(BankMovement m){return map("id",m.id.toString(),"accountId",m.accountId,
      "bookedOn",m.bookedOn,"valueOn",m.valueOn,"occurredAt",m.occurredAt,"description",m.description,
      "bankReference",m.bankReference,"amount",Money.text(m.amount),"currency",m.currency,"direction",m.direction,"kind",m.kind);}
}
