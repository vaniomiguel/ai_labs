package pt.conta.category;

import static pt.conta.shared.Inputs.*;

import java.util.*;
import pt.conta.shared.DomainException;

/** Validates the relationships in a schema-checked category review response. */
record InvoiceCategoryReview(String summary, List<Assessment> assessments, List<Suggestion> suggestions) {
  record Assessment(UUID categoryId, String fit, String reason) {}
  record Suggestion(UUID categoryId, String reason, String evidence, List<UUID> replacesCategoryIds) {}

  static InvoiceCategoryReview read(Map<String, Object> result, Set<UUID> current) {
    var assessments = rows(result, "assessments").stream()
        .map(row -> new Assessment(id(row.get("categoryId")), text(row, "fit"), explanation(row, "reason"))).toList();
    var assessed = new HashSet<>(assessments.stream().map(Assessment::categoryId).toList());
    if (!assessed.equals(current) || assessed.size() != assessments.size()) {
      throw DomainException.invalid("Avalia cada categoria atribuída exatamente uma vez.");
    }
    var suggested = new HashSet<UUID>();
    var suggestions = rows(result, "suggestions").stream()
        .map(row -> suggestion(row, current, suggested))
        .toList();
    return new InvoiceCategoryReview(explanation(result, "summary"), assessments, suggestions);
  }

  private static Suggestion suggestion(Map<String, Object> row, Set<UUID> current, Set<UUID> suggested) {
    UUID categoryId = id(row.get("categoryId"));
    if (current.contains(categoryId) || !suggested.add(categoryId)) {
      throw DomainException.invalid("As sugestões devem ser diferentes das categorias atribuídas e entre si.");
    }
    var replacements = ((List<?>) row.get("replacesCategoryIds")).stream().map(InvoiceCategoryReview::id).toList();
    if (!current.containsAll(replacements) || new HashSet<>(replacements).size() != replacements.size()) {
      throw DomainException.invalid("Uma sugestão só pode indicar categorias atribuídas para substituir.");
    }
    return new Suggestion(categoryId, explanation(row, "reason"), (String) row.get("evidence"), replacements);
  }

  private static String explanation(Map<String, Object> row, String key) {
    String explanation = text(row, key);
    if (explanation == null) throw DomainException.invalid("A revisão de categorias precisa de uma justificação.");
    return explanation;
  }

  private static UUID id(Object value) {
    try {
      String input = Objects.toString(value, "");
      UUID id = UUID.fromString(input);
      if (id.toString().equalsIgnoreCase(input)) return id;
    } catch (IllegalArgumentException ignored) {
      // The model may return names or invented identifiers.
    }
    throw DomainException.invalid("Usa apenas identificadores de categoria apresentados no pedido.");
  }
}
