package pt.conta.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the Angular shell for browser requests to workspace routes. */
@Controller
public class WorkspacePageController {
  @GetMapping({
    "/login", "/reconciliation", "/invoice", "/movement", "/document", "/processing",
    "/alert", "/balances", "/history", "/settings", "/assistant",
    "/invoice/new", "/invoice/{id}/edit", "/invoice/{id}/categories",
    "/movement/new", "/movement/{id}/edit", "/movement/{id}/categories",
    "/document/{id}/review", "/alert/{id}/review", "/processing/{id:[^.]+}",
    "/reconciliation/{id:[^.]+}", "/reconciliation/{id}/assign", "/reconciliation/{id}/exclude",
    "/reconciliation/{id}/reverse"
  })
  public String workspace() {
    return "forward:/index.html";
  }
}
