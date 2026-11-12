package pt.conta.api;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import pt.conta.jobs.JobMonitor;

/** Authenticated queue browsing alongside the original, unchanged /jobs list API. */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
  private final JobMonitor monitor;

  public JobController(JobMonitor monitor) {
    this.monitor = monitor;
  }

  @GetMapping("/page")
  public Map<String, Object> page(
      @RequestParam(defaultValue = "active") String state,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return monitor.page(state, page, size);
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable UUID id) {
    return monitor.detail(id);
  }
}
