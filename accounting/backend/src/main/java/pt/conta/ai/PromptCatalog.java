package pt.conta.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import pt.conta.shared.*;

/** Central, versioned prompt and schema catalog with optional external prompt overrides. */
@Component
public class PromptCatalog {
  public static final String INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS = "extract-invoice";
  public static final String INVOICE_EXTRACTION_FIELD_INSTRUCTIONS = "extract-invoice-fields";
  public static final String INVOICE_REPAIR_SYSTEM_INSTRUCTIONS = "repair-invoice";
  public static final String AUTOMATIC_REVIEW_SYSTEM_INSTRUCTIONS = "automatic-review";
  public static final String AUTOMATIC_INVOICE_REVIEW_CHECKLIST = "automatic-review-invoice";
  public static final String AUTOMATIC_MOVEMENT_REVIEW_CHECKLIST = "automatic-review-movement";
  public static final String AUTOMATIC_DOCUMENT_REVIEW_CHECKLIST = "automatic-review-document";
  public static final String AUTOMATIC_ALLOCATION_REVIEW_CHECKLIST = "automatic-review-allocation";
  public static final String AUTOMATIC_REVIEW_RESPONSE_EXAMPLE = "automatic-review-example";
  public static final String REPAIR_ANALYSIS_INSTRUCTIONS = "repair-analysis-instructions";
  public static final String REPAIR_RECORD_SCOPE_INSTRUCTIONS = "repair-record-scope-instructions";
  public static final String HUMAN_FIELD_AUTHORITY_INSTRUCTIONS = "human-field-authority-instructions";
  public static final String CATEGORY_VALIDATION_RETRY_INSTRUCTIONS = "category-validation-retry-instructions";
  public static final String EXPENSE_CLASSIFICATION_SYSTEM_INSTRUCTIONS = "categories";
  public static final String CATEGORY_CATALOG_REVIEW_SYSTEM_INSTRUCTIONS = "category-catalog-review";
  public static final String CATEGORY_VARIANT_MISMATCH_INSTRUCTIONS = "category-variant-mismatch-instructions";
  public static final String CATEGORY_PROTECTED_VARIANT_INSTRUCTIONS = "category-protected-variant-instructions";
  public static final String CATEGORY_GENERALIZE_VARIANT_INSTRUCTIONS = "category-generalize-variant-instructions";
  public static final String CATEGORY_GENERIC_FAMILY_INSTRUCTIONS = "category-generic-family-instructions";
  public static final String CATEGORY_GENERALIZE_REQUIRED_INSTRUCTIONS = "category-generalize-required-instructions";
  public static final String CATEGORY_SINGLE_MOVE_INSTRUCTIONS = "category-single-move-instructions";

  private final AccountingProperties properties;

  public PromptCatalog(AccountingProperties properties) {
    this.properties = properties;
  }

  /** Loads a task prompt or instruction fragment, preferring an optional external override. */
  public String prompt(String task) {
    try {
      if (properties.promptDirectory() != null && !properties.promptDirectory().isBlank()) {
        Path file = Path.of(properties.promptDirectory()).resolve(task + ".md");
        if (Files.isRegularFile(file)) return Files.readString(file);
      }
      return new String(
          new ClassPathResource("prompts/" + task + ".md").getInputStream().readAllBytes(),
          StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Prompt não encontrada: " + task, e);
    }
  }

  /** Returns the task-specific response schema supplied to every provider. */
  public Map<String, Object> schema(String task) {
    try {
      return Json.object(
          new String(
              new ClassPathResource("prompts/" + task + ".schema.json")
                  .getInputStream()
                  .readAllBytes(),
              StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Esquema não encontrado: " + task, e);
    }
  }
}
