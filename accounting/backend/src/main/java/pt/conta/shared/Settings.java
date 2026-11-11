package pt.conta.shared;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores live settings without requiring edits to application code or restarts. */
@Service
public class Settings {
  private final Store store;

  public Settings(Store store) {
    this.store = store;
  }

  /** Returns a persisted override or the explicit default. */
  @Transactional(readOnly = true)
  public String get(String name, String fallback) {
    return store.all(AppSetting.class).stream()
        .filter(s -> s.name.equals(name))
        .map(s -> s.value)
        .findFirst()
        .orElse(fallback);
  }

  /** Replaces a setting atomically; callers validate values appropriate to the setting. */
  @Transactional
  public void set(String name, String value) {
    store.lockFinancialWrites();
    var setting =
        store.all(AppSetting.class).stream()
            .filter(s -> s.name.equals(name))
            .findFirst()
            .orElseGet(
                () -> {
                  var s = new AppSetting();
                  s.name = name;
                  return store.add(s);
                });
    setting.value = value;
  }
}
