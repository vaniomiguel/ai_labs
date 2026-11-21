package pt.conta.ai;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.shared.Store;

/** Records model executions and subsequent domain validation outcomes. */
@Service
public class AiResults {
  private final Store store;

  public AiResults(Store store) {
    this.store = store;
  }

  @Transactional
  public void record(AiExecution execution) {
    store.add(execution);
  }

  @Transactional
  public void categoryOutcome(UUID execution, String outcome) {
    store.get(AiExecution.class, execution).outcome = outcome;
  }
}
