package pt.conta.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pt.conta.category.CategoryService;
import pt.conta.shared.Inputs;

@RestController
@RequestMapping("/api/v1")
public class CategoryController {
  public record AssignmentEdit(@NotNull @PositiveOrZero Long version,
      @NotNull List<@NotNull UUID> categoryIds, boolean allowAi) {}
  public record Revision(@NotNull @PositiveOrZero Long version) {}
  private final CategoryService categories;
  public CategoryController(CategoryService categories) { this.categories = categories; }

  @PutMapping("/invoices/{id}/categories")
  public Map<String, Object> invoice(@PathVariable UUID id, @Valid @RequestBody AssignmentEdit body) {
    return categories.manual("invoice", id, body.version(), body.categoryIds(), body.allowAi());
  }
  @PutMapping("/movements/{id}/categories")
  public Map<String, Object> movement(@PathVariable UUID id, @Valid @RequestBody AssignmentEdit body) {
    return categories.manual("movement", id, body.version(), body.categoryIds(), body.allowAi());
  }
  @GetMapping("/categories/changes")
  public List<Map<String, Object>> changes() { return categories.changes(); }
  @PostMapping("/categories/changes/{id}/undo")
  public void undo(@PathVariable UUID id, @Valid @RequestBody Revision body) { categories.undo(id, body.version()); }
  @PostMapping("/categories/merge")
  public void merge(@RequestBody Map<String, Object> body) { categories.merge(body); }
  @PostMapping("/categories/review")
  public void review() { categories.requestReview(true); }
  @PostMapping("/categories/protection")
  @Transactional
  public void protection(@RequestBody Map<String, Object> body) {
    if (!(body.get("protected") instanceof Boolean)) throw pt.conta.shared.DomainException.invalid("Indica se as categorias ficam protegidas");
    if (Inputs.rows(body, "items").isEmpty()) throw pt.conta.shared.DomainException.invalid("Seleciona categorias");
    for (var item : Inputs.rows(body, "items")) {
      var edit = new LinkedHashMap<>(item); edit.put("protected", Inputs.flag(body, "protected")); categories.save(edit);
    }
  }
}
