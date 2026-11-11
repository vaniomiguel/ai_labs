package pt.conta.api;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import pt.conta.shared.Json;

/** Serves the versioned OpenAPI contract from the authenticated application. */
@RestController
public class ContractController {
  /** Returns plain JSON values compatible with the HTTP JSON mapper. */
  @GetMapping("/api/v1/openapi")
  public Object contract() throws java.io.IOException {
    return Json.value(
        new String(
            new ClassPathResource("openapi.json").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8));
  }
}
