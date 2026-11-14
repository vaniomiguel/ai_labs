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
  public static final String REPAIR_ANALYSIS_INSTRUCTIONS = "repair-analysis-instructions";
  public static final String HUMAN_FIELD_AUTHORITY_INSTRUCTIONS = "human-field-authority-instructions";
  public static final String CATEGORY_VALIDATION_RETRY_INSTRUCTIONS = "category-validation-retry-instructions";

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
