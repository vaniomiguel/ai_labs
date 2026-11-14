package pt.conta.document;

import static pt.conta.shared.Inputs.*;
import java.util.*;
import pt.conta.reconciliation.MatchingRules;
import pt.conta.shared.*;

/** Stable source facts used for legacy draft identities; a row position is not an identity. */
public final class SourceIdentity {
  private SourceIdentity() {}
  public static String key(Map<String,Object> row,String type) {
    if(type.equals("invoice")) {
      String supplier=Objects.requireNonNullElse(text(row,"supplierTaxId"),MatchingRules.normalize(text(row,"supplier")));
      String number=text(row,"number");
      if(number!=null&&!supplier.isBlank())return Json.write(List.of(supplier,MatchingRules.normalize(number),Objects.requireNonNullElse(text(row,"type"),"INVOICE")));
      if(date(row,"issuedOn")==null||row.get("total")==null||supplier.isBlank())return null;
      return Json.write(List.of(supplier,date(row,"issuedOn").toString(),Money.optional(row.get("total")),Objects.requireNonNullElse(text(row,"description"),"")));
    }
    if(date(row,"bookedOn")==null||text(row,"description")==null||row.get("amount")==null)return null;
    return Json.write(Arrays.asList(date(row,"bookedOn").toString(),MatchingRules.normalize(text(row,"description")),
        Money.optional(row.get("amount")),text(row,"direction"),text(row,"occurredAt"),text(row,"bankReference")));
  }
}
