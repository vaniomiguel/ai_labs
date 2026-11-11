package pt.conta.shared;

/** Carries a user-readable validation failure and its appropriate HTTP status. */
public class DomainException extends RuntimeException {
  public final int status;

  public DomainException(int status, String message) {
    super(message);
    this.status = status;
  }

  /** Signals a missing resource without exposing persistence details. */
  public static DomainException missing() {
    return new DomainException(404, "Registo não encontrado");
  }

  /** Signals an invalid business operation. */
  public static DomainException invalid(String message) {
    return new DomainException(422, message);
  }
}
