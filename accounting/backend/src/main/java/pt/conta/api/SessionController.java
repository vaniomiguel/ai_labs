package pt.conta.api;

import java.security.Principal;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** Session and CSRF discovery for the same-origin browser application. */
@RestController
@RequestMapping("/api/v1")
public class SessionController {
  /** Forces token materialization so Angular receives its XSRF-TOKEN cookie. */
  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken());
  }

  /** Returns the authenticated audit identity. */
  @GetMapping("/me")
  Map<String, String> me(Principal principal) {
    return Map.of("username", principal.getName());
  }
}
