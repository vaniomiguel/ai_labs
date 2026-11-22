package pt.conta.api;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import pt.conta.ledger.FinancialRecordDeletion;
import pt.conta.ledger.FinancialRecordDeletion.Impact;
import pt.conta.ledger.FinancialRecordDeletion.Type;

/** Applies an explicit confirmation to the same consequences the API has previewed. */
@RestController
@RequestMapping("/api/v1/{resource:invoices|movements|documents}/{id}")
public class DeletionController {
  private final FinancialRecordDeletion deletion;

  public DeletionController(FinancialRecordDeletion deletion) {
    this.deletion = deletion;
  }

  @GetMapping("/deletion-impact")
  public Impact impact(@PathVariable String resource, @PathVariable UUID id) {
    return deletion.preview(type(resource), id);
  }

  @DeleteMapping
  public void delete(@PathVariable String resource, @PathVariable UUID id, @RequestParam long version,
      @RequestParam String impactToken) {
    deletion.delete(type(resource), id, version, impactToken);
  }

  private static Type type(String resource) {
    return switch (resource) {
      case "invoices" -> Type.INVOICE;
      case "movements" -> Type.MOVEMENT;
      case "documents" -> Type.DOCUMENT;
      default -> throw new IllegalArgumentException("Unexpected deletion resource");
    };
  }
}
