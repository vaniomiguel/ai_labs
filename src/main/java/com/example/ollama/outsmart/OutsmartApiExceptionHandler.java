package com.example.ollama.outsmart;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps Outsmart API failures to HTTP errors without changing other application endpoints. */
@RestControllerAdvice(assignableTypes = OutsmartGameController.class)
public class OutsmartApiExceptionHandler {

    /** Keeps error responses small and specific to Outsmart. */
    public record Error(String error) {
    }

    /** Reports malformed player lists and invalid names or models as client errors. */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Error> badRequest(Exception exception) {
        String message = exception instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getAllErrors().getFirst().getDefaultMessage()
                : exception.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Error(message));
    }

    /** Reports IDs that have never existed or whose games were removed. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Error> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error(exception.getMessage()));
    }

    /** Reports an unavailable local model without advancing its turn. */
    @ExceptionHandler(OutsmartModelUnavailableException.class)
    public ResponseEntity<Error> modelUnavailable(OutsmartModelUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Error(exception.getMessage()));
    }

    /** Reports finished games, full in-memory capacity, and stale turn submissions. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Error> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(exception.getMessage()));
    }
}
