package pt.conta.shared;

import java.util.List;

/** A rejected edit with messages that can be associated with individual form controls. */
public class FieldValidationException extends DomainException {
  public record Issue(String code, String message, List<String> fields) {
    public Issue(String code, String message, String... fields) {
      this(code, message, List.of(fields));
    }
  }

  public final List<Issue> issues;

  public FieldValidationException(int status, String detail, List<Issue> issues) {
    super(status, detail);
    this.issues = List.copyOf(issues);
  }

  public static FieldValidationException invalid(List<Issue> issues) {
    return new FieldValidationException(422, String.join("; ", messages(issues)), issues);
  }

  public static List<String> messages(List<Issue> issues) {
    return issues.stream().map(Issue::message).distinct().toList();
  }
}
