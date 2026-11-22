package pt.conta.ai;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.jobs.Job;
import pt.conta.jobs.JobQueue;
import pt.conta.shared.Store;

/** Records model executions and subsequent domain validation outcomes. */
@Service
public class AiResults {
  private final Store store;

  public AiResults(Store store) {
    this.store = store;
  }

  @Transactional
  public void record(AiExecution execution, JobQueue.Claim claim) {
    store.lockFinancialWrites();
    if (claim.token() != null && store.find(Job.class, claim.id())
        .filter(job -> claim.token().equals(job.leaseToken) && "RUNNING".equals(job.state)).isEmpty()) return;
    store.add(execution);
  }

  @Transactional
  public void categoryOutcome(UUID execution, String outcome) {
    store.find(AiExecution.class, execution).ifPresent(result -> result.outcome = outcome);
  }
}
