package pt.conta.shared;

import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** Converts expected failures to structured, non-sensitive API problem responses. */
@RestControllerAdvice
public class ApiErrors {
  /** Preserves the status and explanation of a business rule failure. */
  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> domain(DomainException e) {
    return problem(e.status, e.getMessage());
  }

  /** Makes concurrent edits explicit rather than silently overwriting them. */
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> conflict(Exception e) {
    return problem(409, "O registo foi alterado. Atualiza a página.");
  }

  /** Reports invalid payloads consistently. */
  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return problem(400, e.getMessage());
  }

  private ResponseEntity<ProblemDetail> problem(int status, String detail) {
    return ResponseEntity.status(status)
        .body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail));
  }
}
