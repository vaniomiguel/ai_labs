package pt.conta.document;

import static pt.conta.shared.Inputs.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import pt.conta.audit.AuditService;
import pt.conta.shared.*;

/** Field ownership and identity-aware merges shared by every extraction and review path. */
@Service
public class FieldProtection {
  public static final List<String> INVOICE=List.of("supplier","supplierTaxId","number","type","issuedOn","dueOn","total","net","tax","discount","description","currency","relatedInvoiceId","lines");
  public static final List<String> MOVEMENT=List.of("accountId","bankReference","bookedOn","valueOn","occurredAt","description","amount","currency","direction","kind");
  private static final Set<String> NUMBERS=Set.of("total","net","tax","discount","amount","quantity","unitPrice");
  private final AuditService audit;
  public FieldProtection(AuditService audit){this.audit=audit;}
  public List<String> fields(String kind){return kind.equals("INVOICE")?INVOICE:MOVEMENT;}
  public Map<String,Object> copy(Object value){return value==null?new LinkedHashMap<>():Json.object(Json.write(value));}
  public boolean empty(Object value){return value==null||value instanceof String s&&s.isBlank()||value instanceof Collection<?> c&&c.isEmpty();}
  public Object normalized(String field,Object value) {
    if(empty(value))return null;
    if(value instanceof Map<?,?> row){var out=new TreeMap<String,Object>();row.forEach((k,v)->{if(!Set.of("id","version","invoiceId","position").contains(k.toString()))out.put(k.toString(),normalized(k.toString(),v));});return out;}
    if(value instanceof List<?> rows)return rows.stream().map(v->normalized(field,v)).toList();
    String text=value.toString().trim();
    if(NUMBERS.contains(field))try{return new BigDecimal(text).stripTrailingZeros().toPlainString();}catch(NumberFormatException ignored){}
    return text;
  }
  public boolean same(String field,Object a,Object b){return Objects.equals(normalized(field,a),normalized(field,b));}
  public boolean protectedField(Map<String,Object> metadata,String field,Object current){
    return metadata.get(field) instanceof Map<?,?> m&&Boolean.TRUE.equals(m.get("protected"))
        ||metadata.get("_legacy") instanceof Map<?,?> legacy&&(!empty(current)||Boolean.TRUE.equals(legacy.get("includeEmpty")));
  }
  public Map<String,Object> decision(String origin,String proposal){return map("origin",origin,"protected",!origin.equals("AUTOMATIC"),"actor",audit.actor(),"at",Instant.now().toString(),"proposalId",proposal);}
  public Map<String,Object> human(String kind,Map<String,Object> before,Map<String,Object> after,Map<String,Object> metadata,boolean validate) {
    var result=copy(metadata);
    for(String field:fields(kind)) {
      if(after.containsKey(field)&&!same(field,before.get(field),after.get(field)))result.put(field,decision("HUMAN_EDIT",null));
      else if(validate&&!empty(after.get(field))&&!protectedField(result,field,after.get(field)))result.put(field,decision("HUMAN_CONFIRMED",null));
    }
    return result;
  }
  public Map<String,Object> automatic(String kind,Map<String,Object> current,Map<String,Object> incoming,Map<String,Object> metadata) {
    var result=copy(current);
    for(String field:fields(kind))if(!empty(incoming.get(field))&&empty(current.get(field))&&!protectedField(metadata,field,current.get(field)))result.put(field,incoming.get(field));
    return result;
  }
  public Map<String,Object> automaticOrigins(String kind,Map<String,Object> row,Map<String,Object> metadata){
    var result=copy(metadata);for(String field:fields(kind))if(!empty(row.get(field))&&!result.containsKey(field))result.put(field,decision("AUTOMATIC",null));return result;
  }
  public String rowKey(Map<String,Object> row,boolean root){return root?"root":Objects.toString(row.get("id"),Objects.toString(row.get("_itemId"),""));}
  public List<Map<String,Object>> entries(String kind,Map<String,Object> draft){
    if(kind.equals("INVOICE")){var rows=rows(draft,"invoices");return rows.isEmpty()?List.of(draft):rows;}
    return rows(draft,"movements");
  }
  public boolean root(String kind,Map<String,Object> draft){return kind.equals("INVOICE")&&rows(draft,"invoices").isEmpty();}
  public Map<String,Object> rowMetadata(Map<String,Object> all,String key){var result=copy(all.get(key));if(all.containsKey("_legacy"))result.put("_legacy",all.get("_legacy"));return result;}

  /** Match by persisted identity, unique facts/reference, or an unambiguous singleton; never position. */
  public Map<String,Object> align(String kind,Map<String,Object> current,Map<String,Object> incoming) {
    var result=copy(incoming);var old=entries(kind,current);var proposed=entries(kind,result);
    if(old.isEmpty())return result;
    if(root(kind,current)&&root(kind,result))return result;
    if(root(kind,current)!=root(kind,result)&&!(kind.equals("INVOICE")&&old.size()==1&&proposed.size()==1))throw ambiguous();
    if(old.size()!=proposed.size())throw ambiguous();
    var used=new HashSet<Map<String,Object>>();var aligned=new ArrayList<Map<String,Object>>();
    for(var previous:old) {
      var matches=proposed.stream().filter(r->!used.contains(r)&&identityMatch(previous,r)).toList();
      if(matches.isEmpty())matches=proposed.stream().filter(r->!used.contains(r)&&factMatch(kind,previous,r)).toList();
      if(matches.isEmpty()&&kind.equals("STATEMENT")&&text(previous,"bankReference")!=null)
        matches=proposed.stream().filter(r->!used.contains(r)&&Objects.equals(text(previous,"bankReference"),text(r,"bankReference"))).toList();
      if(matches.isEmpty()&&old.size()==1&&(kind.equals("INVOICE")||Objects.equals(text(previous,"bookedOn"),text(proposed.getFirst(),"bookedOn"))&&Objects.equals(text(previous,"description"),text(proposed.getFirst(),"description"))))matches=proposed;
      if(matches.size()!=1)throw ambiguous();
      var selected=matches.getFirst();used.add(selected);var row=copy(selected);row.put("_proposalIndex",proposed.indexOf(selected));
      for(String key:List.of("id","_itemId"))if(previous.get(key)!=null)row.put(key,previous.get(key));
      aligned.add(row);
    }
    if(root(kind,current))return aligned.getFirst();
    result.put(kind.equals("INVOICE")?"invoices":"movements",aligned);return result;
  }
  private boolean identityMatch(Map<String,Object> a,Map<String,Object> b){return a.get("id")!=null&&a.get("id").toString().equals(Objects.toString(b.get("id"),""))||a.get("_itemId")!=null&&Objects.equals(a.get("_itemId"),b.get("_itemId"));}
  private boolean factMatch(String kind,Map<String,Object> a,Map<String,Object> b){try{String key=SourceIdentity.key(a,kind.equals("INVOICE")?"invoice":"movement");return key!=null&&key.equals(SourceIdentity.key(b,kind.equals("INVOICE")?"invoice":"movement"));}catch(RuntimeException e){return false;}}
  public DomainException ambiguous(){return new DomainException(409,"A análise não identifica inequivocamente os mesmos itens. Revê a estrutura do original; nenhum item existente foi substituído ou removido.");}
  public Map<String,Object> initialize(String kind,Map<String,Object> draft){
    var result=copy(draft);if(!root(kind,result))for(var row:entries(kind,result))if(row.get("id")==null&&row.get("_itemId")==null)row.put("_itemId",UUID.randomUUID().toString());return result;
  }
  public Map<String,Object> automaticDocument(String kind,Map<String,Object> current,Map<String,Object> incoming,Map<String,Object> metadata) {
    if(current.isEmpty()||metadata.isEmpty()&&entries(kind,current).stream().noneMatch(r->fields(kind).stream().anyMatch(f->!empty(r.get(f)))))return initialize(kind,incoming);
    var aligned=align(kind,current,incoming);var result=copy(current);var previous=entries(kind,result);var proposed=entries(kind,aligned);
    for(int n=0;n<previous.size();n++) {
      var row=previous.get(n);var merged=automatic(kind,row,proposed.get(n),rowMetadata(metadata,rowKey(row,root(kind,result))));
      row.putAll(merged);
    }
    // Processing flags describe the merged values, never erase existing human decisions.
    if(incoming.containsKey("issues"))result.put("issues",incoming.get("issues"));
    if(!metadata.containsKey("_legacy")&&metadata.values().stream().noneMatch(v->v instanceof Map<?,?> m&&m.values().stream().anyMatch(x->x instanceof Map<?,?> d&&Boolean.TRUE.equals(d.get("protected")))))
      result.put("validate",incoming.get("validate"));
    return result;
  }
  public Map<String,Object> humanDocument(String kind,Map<String,Object> before,Map<String,Object> after,Map<String,Object> metadata,boolean validate){
    var result=copy(metadata);var old=entries(kind,before);boolean root=root(kind,after);
    for(var row:entries(kind,after)){
      String key=rowKey(row,root);if(key.isEmpty())throw ambiguous();
      var previous=old.stream().filter(r->rowKey(r,root(kind,before)).equals(key)).findFirst().orElse(Map.of());
      result.put(key,human(kind,previous,row,rowMetadata(metadata,key),validate));
    }
    return result;
  }
  public List<Map<String,Object>> changes(String kind,Map<String,Object> current,Map<String,Object> proposed,Map<String,Object> metadata) {
    var aligned=align(kind,current,proposed);var before=entries(kind,current);var after=entries(kind,aligned);var changes=new ArrayList<Map<String,Object>>();
    for(int n=0;n<before.size();n++){
      var row=before.get(n);String item=rowKey(row,root(kind,current));var ownership=rowMetadata(metadata,item);
      for(String field:fields(kind))if(!empty(after.get(n).get(field))&&!same(field,row.get(field),after.get(n).get(field))) {
        boolean protectedValue=protectedField(ownership,field,row.get(field));
        changes.add(map("key",item+":"+field,"itemId",item,"itemIndex",n,"proposalIndex",root(kind,current)?0:after.get(n).getOrDefault("_proposalIndex",n),"field",field,"current",row.get(field),"proposed",after.get(n).get(field),"protected",protectedValue,"kind",empty(row.get(field))&&!protectedValue?"COMPLEMENT":"CONFLICT"));
      }
    }return changes;
  }
}
