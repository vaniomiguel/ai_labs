package pt.conta.shared;

import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal persistence gateway; domain services own transactions and never expose managed entities.
 */
@Repository
public class Store {
  private static final Object CACHE_KEY = new Object();
  @PersistenceContext private EntityManager em;

  /** Retrieves one entity or returns a user-facing not-found error. */
  public <T> T get(Class<T> type, UUID id) {
    T result = em.find(type, id);
    if (result == null) throw DomainException.missing();
    return result;
  }

  /** Reads domain records in stable creation order. */
  public <T> List<T> all(Class<T> type) {
    var cache = cache();
    if (cache == null) return query(type);
    @SuppressWarnings("unchecked")
    var cached =
        (List<T>) (List<?>) cache.computeIfAbsent(type, key -> new ArrayList<Object>(query(type)));
    return cached;
  }

  /** Inserts a new entity inside its caller's transaction. */
  public <T> T add(T entity) {
    em.persist(entity);
    var cache = cache();
    if (cache != null && cache.containsKey(entity.getClass()))
      cache.get(entity.getClass()).add(entity);
    return entity;
  }

  /** Removes a replaceable child row; parent revisions remain preserved in the audit trail. */
  public void remove(Object entity) {
    em.remove(entity);
    var cache = cache();
    if (cache != null && cache.containsKey(entity.getClass()))
      cache.get(entity.getClass()).remove(entity);
  }

  /** Flushes pending changes before derived SQL reads. */
  public void flush() {
    em.flush();
  }

  /** Serializes financial writes across processes to prevent cross-row over-allocation. */
  public void lockFinancialWrites() {
    em.createNativeQuery("select pg_advisory_xact_lock(712690118)").getSingleResult();
  }

  /** Rejects a stale edit using the version supplied by the browser. */
  public void checkVersion(BaseEntity entity, long expected) {
    if (entity.version != expected)
      throw new DomainException(409, "O registo foi alterado. Atualiza a página.");
  }

  /**
   * Memoizes an aggregate read projection once per transaction without loading full audit payloads.
   */
  @SuppressWarnings("unchecked")
  public <T> T memo(Class<T> key, java.util.function.Supplier<T> load) {
    var cache = cache();
    if (cache == null) return load.get();
    return (T)
        cache
            .computeIfAbsent(key, ignored -> new ArrayList<Object>(List.of(load.get())))
            .getFirst();
  }

  private <T> List<T> query(Class<T> type) {
    return new ArrayList<>(
        em.createQuery(
                "select e from " + type.getSimpleName() + " e order by e.createdAt,e.id", type)
            .getResultList());
  }

  /**
   * Bounds repeated domain reads to one query per type in a transaction, including newly added
   * rows.
   */
  @SuppressWarnings("unchecked")
  private Map<Class<?>, List<Object>> cache() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
    var existing =
        (Map<Class<?>, List<Object>>) TransactionSynchronizationManager.getResource(CACHE_KEY);
    if (existing != null) return existing;
    var cache = new HashMap<Class<?>, List<Object>>();
    TransactionSynchronizationManager.bindResource(CACHE_KEY, cache);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void suspend() {
            TransactionSynchronizationManager.unbindResourceIfPossible(CACHE_KEY);
          }

          @Override
          public void resume() {
            TransactionSynchronizationManager.bindResource(CACHE_KEY, cache);
          }

          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(CACHE_KEY);
          }
        });
    return cache;
  }
}
