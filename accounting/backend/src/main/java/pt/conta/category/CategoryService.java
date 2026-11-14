package pt.conta.category;

import static pt.conta.shared.Inputs.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.audit.AuditService;
import pt.conta.bank.BankMovement;
import pt.conta.document.SourceDocument;
import pt.conta.invoice.*;
import pt.conta.jobs.JobQueue;
import pt.conta.reconciliation.*;
import pt.conta.shared.*;

/** Owns taxonomy and category decisions. It never writes financial records or ledger effects. */
@Service
@Transactional
public class CategoryService {
  public record Snapshot(String type, UUID target, String fingerprint, long catalogRevision,
      Map<String, Object> input) {}
  private final Store store;
  private final AuditService audit;
  private final JobQueue jobs;
  private final JdbcTemplate jdbc;
  private final Map<String, Object> reference;

  public CategoryService(Store store, AuditService audit, JobQueue jobs, JdbcTemplate jdbc) {
    this.store = store; this.audit = audit; this.jobs = jobs; this.jdbc = jdbc;
    try {
      reference = Json.object(new String(new ClassPathResource("category-reference.json")
          .getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    } catch (Exception e) { throw new IllegalStateException("Referência de categorias indisponível", e); }
  }

  private Classification state(String type, UUID id, boolean create) {
    var existing = store.all(Classification.class).stream()
        .filter(s -> id.equals(type.equals("invoice") ? s.invoiceId : s.movementId)).findFirst();
    if (existing.isPresent() || !create) return existing.orElse(null);
    var result = new Classification();
    if (type.equals("invoice")) result.invoiceId = id; else result.movementId = id;
    return store.add(result);
  }

  private Category canonical(UUID id) {
    var seen = new HashSet<UUID>();
    var category = store.get(Category.class, id);
    while (category.mergedIntoId != null) {
      if (!seen.add(category.id)) throw DomainException.invalid("Ciclo na fusão de categorias");
      category = store.get(Category.class, category.mergedIntoId);
    }
    return category;
  }

  private boolean descendant(UUID id, UUID ancestor) {
    var seen = new HashSet<UUID>();
    Category c = canonical(id);
    UUID root = canonical(ancestor).id;
    while (c != null && seen.add(c.id)) {
      if (c.id.equals(root)) return true;
      c = c.parentId == null ? null : canonical(c.parentId);
    }
    return false;
  }

  public boolean matches(UUID categoryId, String filter) {
    UUID id;
    try { id = UUID.fromString(filter); }
    catch (IllegalArgumentException e) {
      var category = named(filter);
      if (category == null) return false;
      id = category.id;
    }
    return descendant(categoryId, id);
  }

  public boolean recordMatches(String type, UUID id, String filter) {
    return details(type, id).stream().anyMatch(c -> matches((UUID) c.get("id"), filter));
  }

  private Category named(String name) {
    String normalized = MatchingRules.normalize(name);
    if (normalized.isBlank()) return null;
    return store.all(Category.class).stream().filter(c ->
        MatchingRules.normalize(c.name).equals(normalized)
            || aliases(c).stream().anyMatch(a -> MatchingRules.normalize(a).equals(normalized)))
        .sorted(Comparator.comparing((Category c) -> c.deleted).thenComparing(c -> c.createdAt))
        .findFirst().map(c -> canonical(c.id)).orElse(null);
  }

  private List<String> aliases(Category c) {
    var values = new ArrayList<String>();
    Json.read(c.aliases).forEach(a -> values.add(a.asText()));
    return values;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> catalog(boolean archived) {
    return store.all(Category.class).stream().filter(c -> archived || !c.deleted)
        .map(this::categoryView).sorted(Comparator.comparing(c -> c.get("path").toString())).toList();
  }

  public Map<String, Object> categoryView(Category c) {
    return map("id", c.id, "version", c.version, "name", c.name, "aliases", aliases(c),
        "description", c.description, "parentId", c.parentId, "mergedIntoId", c.mergedIntoId,
        "origin", c.origin, "protected", c.protectedCategory, "archived", c.deleted,
        "referenceCode", c.referenceCode, "referenceName", c.referenceName,
        "referenceVersion", c.referenceVersion, "referenceSource", c.referenceSource,
        "path", path(c), "usage", store.all(CategoryAssignment.class).stream()
            .filter(a -> !a.rejected && canonical(a.categoryId).id.equals(c.id)).count());
  }

  private String path(Category category) {
    var names = new LinkedList<String>();
    var seen = new HashSet<UUID>();
    for (var c = category; c != null && seen.add(c.id); c = c.parentId == null ? null : canonical(c.parentId))
      names.addFirst(c.name);
    return String.join(" › ", names);
  }

  private List<CategoryAssignment> assignments(Classification s) {
    return s == null ? List.of() : store.all(CategoryAssignment.class).stream()
        .filter(a -> a.classificationId.equals(s.id)).toList();
  }

  private List<Map<String, Object>> inherited(UUID movement) {
    var result = new ArrayList<Map<String, Object>>();
    var invoiceIds = store.all(Allocation.class).stream().filter(a -> a.active && a.movementId.equals(movement))
        .map(a -> a.invoiceId).distinct().sorted().toList();
    for (var id : invoiceIds) {
      var invoice = store.get(Invoice.class, id);
      if (invoice.deleted || invoice.excluded) continue;
      for (var category : details("invoice", id)) result.add(map("id", category.get("id"),
          "origin", "INHERITED", "reason", "Herdada da fatura " + Objects.requireNonNullElse(invoice.number, id.toString()),
          "invoiceId", id, "documentId", invoice.documentId));
    }
    return result;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> details(String type, UUID id) {
    var state = state(type, id, false);
    var assigned = assignments(state);
    var inherited = type.equals("movement") ? inherited(id) : List.<Map<String, Object>>of();
    var reasons = new LinkedHashMap<UUID, List<Map<String, Object>>>();
    var rejected = assigned.stream().filter(a -> a.rejected).map(a -> canonical(a.categoryId).id).toList();
    for (var a : assigned) {
      if (a.rejected || !inherited.isEmpty() && a.origin.equals("AI")) continue;
      var c = canonical(a.categoryId);
      reasons.computeIfAbsent(c.id, k -> new ArrayList<>()).add(map("origin", a.origin,
          "reason", a.evidence, "executionId", a.executionId));
    }
    for (var a : inherited) reasons.computeIfAbsent((UUID) a.get("id"), k -> new ArrayList<>()).add(a);
    reasons.keySet().removeIf(c -> rejected.stream().anyMatch(r -> descendant(c, r)));
    var allIds = new ArrayList<>(reasons.keySet());
    // Parents are available through paths and family filters, not redundant monetary tags.
    reasons.keySet().removeIf(c -> allIds.stream().anyMatch(other -> !other.equals(c) && descendant(other, c)));
    return reasons.entrySet().stream().map(e -> {
      var c = canonical(e.getKey());
      return map("id", c.id, "name", c.name, "path", path(c), "archived", c.deleted,
          "origins", e.getValue().stream().map(v -> v.get("origin")).distinct().toList(), "evidence", e.getValue());
    }).sorted(Comparator.comparing(c -> c.get("path").toString())).toList();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> view(String type, UUID id) {
    var s = state(type, id, false);
    var tags = details(type, id);
    boolean hasInherited = type.equals("movement") && !inherited(id).isEmpty();
    String status = hasInherited ? "INHERITED" : s == null ? "PENDING" : s.status;
    boolean provisional = type.equals("movement") && !hasInherited && tags.stream().anyMatch(c -> {
      var origins = (List<?>) c.get("origins");
      return origins.contains("AI") && !origins.contains("MANUAL") && !origins.contains("LEGACY");
    });
    if (status.equals("CLASSIFIED") && provisional) status = "PROVISIONAL";
    if (status.equals("PENDING")) {
      var queue = store.memo(QueueStates.class, () -> {
        var states = new HashMap<String, String>();
        jdbc.query("select target_id,target_version,state from job where kind in ('CLASSIFY_INVOICE','CLASSIFY_MOVEMENT')",
            rs -> { states.put(rs.getObject(1, UUID.class) + ":" + rs.getLong(2), rs.getString(3)); });
        return new QueueStates(states);
      });
      String queuedState = queue.states().get(id + ":" + (s == null ? 0 : s.generation));
      if ("WAITING".equals(queuedState)) status = "WAITING";
      if ("FAILED".equals(queuedState)) status = "REVIEW";
    }
    return map("version", s == null ? 0L : s.version, "state", status,
        "reason", hasInherited ? "Categorias das faturas associadas" : provisional
            ? "Classificação provisória pela descrição bancária. " + Objects.requireNonNullElse(s.reason, "") : s == null ? null : s.reason,
        "categories", tags, "rejectedCategoryIds", assignments(s).stream().filter(a -> a.rejected)
            .map(a -> canonical(a.categoryId).id).distinct().toList(), "catalogRevision", revision());
  }

  private record QueueStates(Map<String, String> states) {}

  private String sourceText(UUID document) {
    if (document == null) return null;
    String text = Objects.requireNonNullElse(store.get(SourceDocument.class, document).text, "");
    return text.substring(0, Math.min(text.length(), 16000));
  }

  public long revision() { return store.get(CategoryCatalog.class, CategoryCatalog.ID).revision; }
  private void bump() { store.get(CategoryCatalog.class, CategoryCatalog.ID).revision++; }

  private Map<String, Object> source(String type, UUID id) {
    if (type.equals("invoice")) {
      var i = store.get(Invoice.class, id);
      return map("id", id, "type", type, "deleted", i.deleted, "supplier", i.supplier,
          "number", i.number, "description", i.description, "documentId", i.documentId,
          "issuedOn", i.issuedOn, "total", Money.text(i.total), "invoiceType", i.type,
          "sourceText", i.documentId != null && store.all(Invoice.class).stream().filter(other -> i.documentId.equals(other.documentId) && !other.deleted).count() == 1 ? sourceText(i.documentId) : null,
          "lines", store.all(InvoiceLine.class).stream().filter(l -> id.equals(l.invoiceId))
              .sorted(Comparator.comparingInt(l -> l.position)).map(l -> map("description", l.description,
                  "total", Money.text(l.total))).toList());
    }
    var m = store.get(BankMovement.class, id);
    return map("id", id, "type", type, "deleted", m.deleted, "description", m.description,
        "documentId", m.documentId, "bookedOn", m.bookedOn, "amount", Money.text(m.amount), "direction", m.direction,
        "sourceText", null);
  }

  /** Called by factual edits, never by category-only edits or the AI apply path. */
  public void recordChanged(String type, UUID id, Map<String, Object> input, boolean human) {
    store.lockFinancialWrites();
    var s = state(type, id, true);
    if (input.containsKey("categories")) {
      var names = input.get("categories") instanceof List<?> list ? list.stream().map(Object::toString).filter(n -> !n.isBlank()).toList() : List.<String>of();
      if (human) {
        var current = details(type, id).stream().map(c -> MatchingRules.normalize(c.get("name").toString())).collect(Collectors.toSet());
        if (!current.equals(names.stream().map(MatchingRules::normalize).collect(Collectors.toSet()))) {
          var ids = names.stream().map(n -> {
            var c = named(n);
            if (c == null) c = create(n, null, "MANUAL", true, "Criada pelo utilizador");
            if (c.deleted) throw DomainException.invalid("A categoria está arquivada: " + n);
            return c.id;
          }).toList();
          manual(type, id, s.version, ids, false);
        }
      } else s.hints = Json.write(names);
    }
    String hash = Hashes.sha256(Json.write(source(type, id)) + s.hints);
    if (!hash.equals(s.contentHash)) {
      s.contentHash = hash; s.generation++; s.status = "PENDING";
      s.reason = "A aguardar classificação por IA";
      enqueue(type, id, s);
    }
  }

  private void enqueue(String type, UUID id, Classification s) {
    jobs.enqueue(type.equals("invoice") ? "CLASSIFY_INVOICE" : "CLASSIFY_MOVEMENT", id, s.generation,
        Json.write(map("generation", s.generation)), s.contentHash + ":" + s.generation);
  }

  /** Inheritance is resolved from active allocations, so every undo/exclusion is reflected. */
  public void refreshInheritance() {
    for (var m : store.all(BankMovement.class)) {
      if (m.deleted) continue;
      var s = state("movement", m.id, true);
      var inherited = inherited(m.id);
      String hash = Hashes.sha256(Json.write(inherited));
      if (Objects.equals(hash, s.inheritanceHash)) continue;
      boolean previouslyInherited = s.inheritanceHash != null;
      s.inheritanceHash = hash;
      if (!inherited.isEmpty()) {
        s.generation++; s.status = "INHERITED"; s.reason = "Categorias das faturas associadas";
      } else if (previouslyInherited && s.status.equals("INHERITED")) {
        s.generation++; s.status = "PENDING"; s.reason = "Associação alterada; a rever descrição bancária";
        enqueue("movement", m.id, s);
      }
    }
  }

  public Map<String, Object> manual(String type, UUID id, long expected, List<UUID> desired, boolean allowAi) {
    store.lockFinancialWrites();
    source(type, id); // Verify the record exists before creating classification state.
    var s = state(type, id, true);
    store.checkVersion(s, expected);
    var before = view(type, id);
    var selected = desired.stream().map(this::canonical).map(c -> c.id).collect(Collectors.toSet());
    var old = assignments(s);
    var oldIds = details(type, id).stream().map(c -> (UUID) c.get("id")).collect(Collectors.toSet());
    for (var c : selected) if (canonical(c).deleted && !oldIds.contains(c)) throw DomainException.invalid("Categoria arquivada");
    for (var a : old) if (a.origin.equals("MANUAL") && (!a.rejected || allowAi || selected.stream().anyMatch(c -> descendant(c, a.categoryId)))) store.remove(a);
    store.flush();
    for (var c : selected) assign(s, c, "MANUAL", false, "Escolha do utilizador", null);
    if (!allowAi) for (var removed : oldIds) if (!selected.contains(removed)) assign(s, removed, "MANUAL", true, "Removida pelo utilizador", null);
    s.generation++; s.status = selected.isEmpty() ? "REVIEW" : "CLASSIFIED";
    s.reason = selected.isEmpty() ? "Sem categoria escolhida pelo utilizador" : "Categorias editadas pelo utilizador";
    if (allowAi) { s.status = "PENDING"; enqueue(type, id, s); }
    store.flush(); refreshInheritance(); store.flush();
    audit.record("CLASSIFY_MANUAL", type, id, before, view(type, id));
    return view(type, id);
  }

  private void assign(Classification s, UUID category, String origin, boolean rejected, String evidence, UUID execution) {
    if (assignments(s).stream().anyMatch(a -> a.categoryId.equals(category) && a.origin.equals(origin) && a.rejected == rejected)) return;
    var a = new CategoryAssignment(); a.classificationId = s.id; a.categoryId = category;
    a.origin = origin; a.rejected = rejected; a.evidence = evidence; a.executionId = execution; store.add(a);
  }

  private Category create(String name, UUID parent, String origin, boolean protect, String description) {
    if (name == null || name.isBlank() || name.length() > 120) throw DomainException.invalid("Nome de categoria inválido");
    var existing = named(name);
    if (existing != null) {
      if (existing.deleted) throw DomainException.invalid("Nome de categoria arquivada: " + name);
      return existing;
    }
    var c = new Category(); c.name = name.trim(); c.parentId = parent;
    c.origin = origin; c.protectedCategory = protect; c.description = description;
    store.add(c); bump(); return c;
  }

  public Map<String, Object> save(Map<String, Object> input) {
    store.lockFinancialWrites();
    UUID id = uuid(input, "id"), parent = uuid(input, "parentId");
    if (parent != null && canonical(parent).deleted) throw DomainException.invalid("Família arquivada");
    Category c;
    String previousName = null;
    var before = new ArrayList<Map<String, Object>>();
    if (id == null) {
      if (named(text(input, "name")) != null) throw DomainException.invalid("A categoria ou nome alternativo já existe");
      c = create(text(input, "name"), parent, "MANUAL", true, text(input, "description"));
    } else {
      c = store.get(Category.class, id); store.checkVersion(c, version(input)); before.add(image(c));
      previousName = c.name;
      if (c.mergedIntoId != null) throw DomainException.invalid("Edita a categoria de destino da fusão");
      String name = text(input, "name");
      if (input.containsKey("name") && (name == null || name.length() > 120)) throw DomainException.invalid("Nome de categoria inválido");
      if (name != null) {
        var existing = named(name);
        if (existing != null && !existing.id.equals(c.id)) throw DomainException.invalid("A categoria já existe");
        c.name = name;
      }
      if (input.containsKey("parentId")) c.parentId = parent;
      if (input.containsKey("description")) c.description = text(input, "description");
      c.protectedCategory = true;
      bump();
    }
    if (input.containsKey("aliases")) {
      if (!(input.get("aliases") instanceof List<?> values)) throw DomainException.invalid("Nomes alternativos inválidos");
      var aliases = values.stream().map(Object::toString).map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
      for (var name : aliases) {
        var other = named(name);
        if (other != null && !other.id.equals(c.id)) throw DomainException.invalid("Nome alternativo já usado: " + name);
      }
      c.aliases = Json.write(aliases);
    }
    if (previousName != null && !previousName.equals(c.name)) {
      var aliases = new LinkedHashSet<>(aliases(c)); aliases.add(previousName); c.aliases = Json.write(aliases);
    }
    if (input.containsKey("protected")) {
      boolean protect = Boolean.TRUE.equals(input.get("protected"));
      if (!protect && c.origin.equals("REFERENCE")) throw DomainException.invalid("As famílias de referência são protegidas");
      c.protectedCategory = protect;
    }
    if (input.containsKey("archived")) c.deleted = Boolean.TRUE.equals(input.get("archived"));
    checkTree(); store.flush();
    change("EDIT", before, List.of(image(c)), "Alteração manual do catálogo", "", null);
    refreshInheritance();
    return categoryView(c);
  }

  public void archive(UUID id, long version, boolean permanently) {
    store.lockFinancialWrites(); var c = store.get(Category.class, id); store.checkVersion(c, version);
    boolean children = store.all(Category.class).stream().anyMatch(child -> id.equals(child.parentId) && (permanently || !child.deleted));
    boolean used = store.all(CategoryAssignment.class).stream().anyMatch(a -> id.equals(a.categoryId))
        || store.all(Category.class).stream().anyMatch(other -> id.equals(other.mergedIntoId));
    if (children) throw DomainException.invalid("Move ou arquiva primeiro as subcategorias");
    if (permanently && used) throw DomainException.invalid("A categoria tem utilizações; pode ser arquivada");
    var before = List.of(image(c));
    if (permanently) store.remove(c); else { c.deleted = true; c.protectedCategory = true; }
    bump(); store.flush(); change(permanently ? "DELETE_UNUSED" : "ARCHIVE", before,
        permanently ? List.of() : List.of(image(c)), "Remoção manual do catálogo", "", null);
  }

  private void checkTree() {
    for (var c : store.all(Category.class)) {
      var seen = new HashSet<UUID>();
      for (var current = c; current != null; current = current.parentId == null ? null : canonical(current.parentId))
        if (!seen.add(current.id)) throw DomainException.invalid("A hierarquia de categorias contém um ciclo");
      canonical(c.id);
    }
  }

  private Map<String, Object> image(Category c) {
    return map("id", c.id.toString(), "version", c.version, "name", c.name, "aliases", c.aliases,
        "parentId", c.parentId == null ? null : c.parentId.toString(),
        "mergedIntoId", c.mergedIntoId == null ? null : c.mergedIntoId.toString(),
        "origin", c.origin, "protected", c.protectedCategory, "deleted", c.deleted, "description", c.description);
  }

  private void change(String kind, List<Map<String, Object>> before, List<Map<String, Object>> after,
      String reason, String evidence, UUID execution) {
    var c = new CategoryChange(); c.kind = kind; c.beforeState = Json.write(before); c.afterState = Json.write(after);
    c.actor = execution == null ? audit.actor() : "AI"; c.executionId = execution;
    c.catalogRevision = revision(); c.reason = reason; c.evidence = evidence; store.add(c);
    audit.record("CATEGORY_" + kind, "category_change", c.id, before, after);
  }

  public List<Map<String, Object>> changes() {
    return store.all(CategoryChange.class).stream().sorted(Comparator.comparing((CategoryChange c) -> c.createdAt).reversed())
        .map(c -> map("id", c.id, "version", c.version, "kind", c.kind, "actor", c.actor,
            "reason", c.reason, "evidence", c.evidence, "before", Json.value(c.beforeState),
            "after", Json.value(c.afterState), "undone", c.undone, "createdAt", c.createdAt)).toList();
  }

  public void undo(UUID id, long expected) {
    store.lockFinancialWrites(); var change = store.get(CategoryChange.class, id); store.checkVersion(change, expected);
    if (change.undone || change.kind.equals("DELETE_UNUSED")) throw DomainException.invalid("Esta alteração não pode ser desfeita");
    var before = rows(map("rows", Json.value(change.beforeState)), "rows");
    var after = rows(map("rows", Json.value(change.afterState)), "rows");
    for (var row : after) {
      var c = store.get(Category.class, uuid(row, "id"));
      if (c.version != ((Number) row.get("version")).longValue())
        throw new DomainException(409, "A categoria tem alterações posteriores; estas escolhas foram preservadas");
    }
    // A later rejection of the merged label also rejects the original label when restored.
    // A later explicit choice of the broader label must not resurrect an old automatic variant.
    var preserved = new LinkedHashMap<UUID, List<Classification>>();
    for (var row : before) {
      UUID original = uuid(row, "id"), current = canonical(original).id;
      if (original.equals(current)) continue;
      var states = store.all(Classification.class).stream().filter(s -> assignments(s).stream()
          .anyMatch(a -> a.origin.equals("MANUAL") && (a.rejected && descendant(current, a.categoryId)
              || !a.rejected && a.createdAt.isAfter(change.createdAt) && !a.categoryId.equals(original)
                  && canonical(a.categoryId).id.equals(current)))).toList();
      preserved.put(original, states);
    }
    for (var row : before) {
      var c = store.get(Category.class, uuid(row, "id"));
      c.name = text(row, "name"); c.aliases = text(row, "aliases"); c.parentId = uuid(row, "parentId");
      c.mergedIntoId = uuid(row, "mergedIntoId"); c.deleted = flag(row, "deleted");
      c.description = text(row, "description"); c.origin = text(row, "origin"); c.protectedCategory = true;
    }
    for (var row : after) if (before.stream().noneMatch(b -> b.get("id").equals(row.get("id")))) {
      var c = store.get(Category.class, uuid(row, "id"));
      boolean used = store.all(CategoryAssignment.class).stream().anyMatch(a -> c.id.equals(a.categoryId))
          || store.all(Category.class).stream().anyMatch(other -> c.id.equals(other.parentId) || c.id.equals(other.mergedIntoId));
      c.deleted = !used; c.protectedCategory = true;
    }
    preserved.forEach((category, states) -> states.forEach(s -> {
      assign(s, category, "MANUAL", true, "Decisão manual posterior preservada ao desfazer a evolução", null);
      s.generation++;
    }));
    checkTree(); change.undone = true; bump(); store.flush(); refreshInheritance();
    audit.record("UNDO_CATEGORY_CHANGE", "category_change", id, after, before);
  }

  /** Stable source, manual decisions and catalog revision guard long-running model calls. */
  @Transactional(readOnly = true)
  public Snapshot snapshot(String type, UUID id) {
    Map<String, Object> data;
    Classification s = null;
    if (type.equals("catalog")) data = map("type", "catalog");
    else {
      data = source(type, id);
      if (Boolean.TRUE.equals(data.get("deleted"))) return null;
      if (type.equals("movement") && !inherited(id).isEmpty()) return null;
      s = state(type, id, false);
    }
    var catalog = store.all(Category.class).stream().map(c -> map("id", c.id, "version", c.version,
        "name", c.name, "aliases", aliases(c), "parentId", c.parentId, "mergedIntoId", c.mergedIntoId,
        "description", c.description == null ? null : c.description.substring(0, Math.min(300, c.description.length())),
        "origin", c.origin, "protected", c.protectedCategory, "archived", c.deleted, "referenceCode", c.referenceCode)).toList();
    var examples = examples();
    var input = map("data", data, "catalog", catalog, "reference", reference,
        "catalogRevision", revision(), "generation", s == null ? 0L : s.generation,
        "manualDecisions", assignments(s).stream().filter(a -> !a.origin.equals("AI"))
            .map(a -> map("categoryId", a.categoryId, "rejected", a.rejected, "origin", a.origin)).toList(),
        "hints", s == null ? List.of() : Json.value(s.hints), "examples", examples);
    String fingerprint = Hashes.sha256(Json.write(input));
    if (s != null && fingerprint.equals(s.appliedFingerprint)) return null;
    return new Snapshot(type, id, fingerprint, revision(), input);
  }

  private List<Map<String, Object>> examples() {
    var result = new ArrayList<Map<String, Object>>();
    var counts = new HashMap<UUID, Integer>();
    int budget = 18000;
    for (var s : store.all(Classification.class).stream().sorted(Comparator
        .comparing((Classification c) -> assignments(c).stream().noneMatch(a -> a.origin.equals("MANUAL") && !a.rejected))
        .thenComparing(c -> c.updatedAt, Comparator.reverseOrder())).toList()) {
      String type = s.invoiceId != null ? "invoice" : "movement";
      var source = source(type, s.invoiceId != null ? s.invoiceId : s.movementId);
      if (Boolean.TRUE.equals(source.get("deleted"))) continue;
      source.remove("sourceText");
      if (Json.write(source).length() > 2000) source = map("id", source.get("id"), "type", type,
          "description", Objects.toString(source.get("description"), "").substring(0, Math.min(500, Objects.toString(source.get("description"), "").length())),
          "supplier", source.get("supplier"), "lines", source.get("lines") instanceof List<?> lines ? lines.stream().limit(5).toList() : List.of());
      for (var tag : details(type, s.invoiceId != null ? s.invoiceId : s.movementId)) {
        UUID id = (UUID) tag.get("id");
        if (counts.getOrDefault(id, 0) >= 2 || result.size() >= 40
            || tag.get("origins").equals(List.of("INHERITED"))) continue;
        var example = map("categoryId", id, "origins", tag.get("origins"), "data", source);
        int size = Json.write(example).length();
        if (size > budget) continue;
        result.add(example); budget -= size; counts.merge(id, 1, Integer::sum);
      }
    }
    return result;
  }

  private boolean grounded(String quote, Snapshot snapshot, boolean operation) {
    if (quote == null || MatchingRules.normalize(quote).length() < 3) return false;
    String text = textValues(snapshot.input().get("data")) + textValues(snapshot.input().get("hints"));
    if (operation) text += textValues(snapshot.input().get("examples"));
    String source = MatchingRules.normalize(text);
    return Arrays.stream(quote.split(";", -1)).allMatch(part ->
        MatchingRules.normalize(part).length() >= 3 && source.contains(MatchingRules.normalize(part)));
  }

  private static final Pattern VARIANT = Pattern.compile("(?iu)\\b([\\p{L}\\d]{2,})[-–]([\\p{L}\\d]+)\\b");

  private String variantBase(String name) {
    return MatchingRules.normalize(VARIANT.matcher(Objects.toString(name, "")).replaceAll("$1"));
  }

  private void checkVariant(Category category, Snapshot snapshot) {
    String source = textValues(snapshot.input().get("data"));
    var variants = VARIANT.matcher(category.name);
    while (variants.find()) {
      var actual = Pattern.compile("(?iu)\\b" + Pattern.quote(variants.group(1)) + "[-–]([\\p{L}\\d]+)\\b").matcher(source);
      var suffixes = new HashSet<String>();
      while (actual.find()) suffixes.add(MatchingRules.normalize(actual.group(1)));
      if (!suffixes.isEmpty() && !suffixes.contains(MatchingRules.normalize(variants.group(2))))
        throw DomainException.invalid("VARIANT_MISMATCH: Não atribua '" + category.name + "' a uma variante diferente. "
            + (category.protectedCategory ? "Use uma categoria geral adequada sem alterar a categoria protegida."
                : "Use GENERALIZE sourceIds=[" + category.id + "], name='" + VARIANT.matcher(category.name).replaceAll("$1")
                    + "', parentId='" + category.parentId + "', key='new:general'; atribua categories.id='new:general'."));
    }
  }

  private String textValues(Object value) {
    if (value instanceof Map<?, ?> map) return map.values().stream().map(this::textValues).collect(Collectors.joining("\n"));
    if (value instanceof List<?> list) return list.stream().map(this::textValues).collect(Collectors.joining("\n"));
    return Objects.toString(value, "") + "\n";
  }

  public boolean genericMovement(Snapshot snapshot) {
    if (!snapshot.type().equals("movement")) return false;
    var data = (Map<?, ?>) snapshot.input().get("data");
    String description = MatchingRules.normalize(Objects.toString(data.get("description"), ""));
    String remainder = description.replaceAll("\\b(TRANSFERENCIA|TRANSFER|TRANSF|TRF|PAGAMENTO|PAG|DEBITO|CREDITO|COMPRA|SEPA|MB|MULTIBANCO|REFERENCIA|REF|NUM|POR|PARA|DE|DO|DA|EM|CONFIRMAR|GENERICA|AGRUPADO)\\b", "")
        .replaceAll("[0-9 ]", "");
    return remainder.isBlank();
  }

  public boolean apply(Snapshot snapshot, Map<String, Object> result, UUID execution) {
    store.lockFinancialWrites();
    var current = snapshot(snapshot.type(), snapshot.target());
    if (current == null || !current.fingerprint().equals(snapshot.fingerprint())) return false;
    if (genericMovement(snapshot)) result = map("status", "REVIEW", "categories", List.of(), "operations", List.of(),
        "explanation", "Descrição bancária genérica: falta informação sobre a natureza da despesa");
    if ("REVIEW".equals(result.get("status")) && (!rows(result, "categories").isEmpty() || !rows(result, "operations").isEmpty()))
      throw DomainException.invalid("Uma resposta incerta não pode alterar categorias");
    var keys = new HashMap<String, UUID>();
    var familyKeys = rows(result, "operations").stream().map(op -> text(op, "parentId")).filter(Objects::nonNull).collect(Collectors.toSet());
    for (var operation : rows(result, "operations")) operate(operation, keys, execution, snapshot, familyKeys);
    checkTree();
    if (!snapshot.type().equals("catalog")) {
      var s = state(snapshot.type(), snapshot.target(), true);
      var desired = new LinkedHashMap<UUID, String>();
      for (var assignment : rows(result, "categories")) {
        String evidence = text(assignment, "evidence");
        if (!grounded(evidence, snapshot, false)) throw DomainException.invalid("Categoria sem evidência nos dados do registo");
        UUID id = resolve(text(assignment, "id"), keys);
        var c = canonical(id); if (c.deleted) throw DomainException.invalid("Categoria arquivada");
        checkVariant(c, snapshot);
        if ("626".equals(c.referenceCode)) throw DomainException.invalid("GENERIC_FAMILY: Serviços diversos é uma família genérica. Escolha uma natureza concreta sustentada pelos dados ou responda REVIEW sem categorias.");
        desired.put(c.id, evidence);
      }
      for (var a : assignments(s).stream().filter(a -> a.origin.equals("AI")).toList()) store.remove(a);
      store.flush();
      for (var a : desired.entrySet())
        if (assignments(s).stream().noneMatch(rejection -> rejection.rejected && descendant(a.getKey(), rejection.categoryId)))
          assign(s, a.getKey(), "AI", false, a.getValue(), execution);
      s.status = details(snapshot.type(), snapshot.target()).isEmpty() ? "REVIEW" : "CLASSIFIED";
      s.reason = Objects.requireNonNullElse(text(result, "explanation"), "Classificação por IA");
      store.flush();
      var updated = snapshot(snapshot.type(), snapshot.target());
      s.appliedFingerprint = updated == null ? snapshot.fingerprint() : updated.fingerprint();
    }
    store.flush(); refreshInheritance(); store.flush(); return true;
  }

  private UUID resolve(String key, Map<String, UUID> keys) {
    if (key == null) return null;
    if (keys.containsKey(key)) return keys.get(key);
    try { UUID id = UUID.fromString(key); store.get(Category.class, id); return id; }
    catch (IllegalArgumentException e) { throw DomainException.invalid("Identificador de categoria inválido: " + key); }
  }

  @SuppressWarnings("unchecked")
  private void operate(Map<String, Object> op, Map<String, UUID> keys, UUID execution, Snapshot snapshot, Set<String> familyKeys) {
    if (execution != null && !grounded(text(op, "evidence"), snapshot, true))
      throw DomainException.invalid("Evolução de categorias sem evidência");
    String kind = text(op, "kind");
    if (!Set.of("CREATE", "GENERALIZE", "MERGE", "MOVE").contains(Objects.toString(kind, "")))
      throw DomainException.invalid("Operação de categoria inválida");
    var sourceIds = op.get("sourceIds") instanceof List<?> list ? list.stream().map(Object::toString).map(UUID::fromString).distinct().toList() : List.<UUID>of();
    var sources = sourceIds.stream().map(id -> store.get(Category.class, id)).toList();
    String proposalKey = text(op, "key");
    if (proposalKey != null && !proposalKey.matches("new:[A-Za-z0-9_-]{1,60}")) throw DomainException.invalid("Chave de proposta inválida");
    if (kind.equals("CREATE") && !sources.isEmpty()) throw DomainException.invalid("CREATE não altera categorias existentes");
    if (kind.equals("MERGE") && text(op, "targetId") == null) throw DomainException.invalid("Falta o destino da fusão");
    if (!kind.equals("CREATE") && sources.isEmpty()) throw DomainException.invalid("Falta a categoria de origem");
    if (sources.stream().anyMatch(c -> c.deleted || c.mergedIntoId != null)) throw DomainException.invalid("A categoria de origem já está arquivada ou fundida");
    if (execution != null && sources.stream().anyMatch(c -> c.protectedCategory || c.origin.equals("REFERENCE")))
      throw DomainException.invalid("A IA não pode alterar categorias protegidas");
    var before = new ArrayList<Map<String, Object>>(); sources.forEach(c -> before.add(image(c)));
    var modified = new LinkedHashSet<Category>(sources);
    UUID parent = resolve(text(op, "parentId"), keys);
    if (parent != null && canonical(parent).deleted) throw DomainException.invalid("Família arquivada");
    if (execution != null && kind.equals("CREATE") && !familyKeys.contains(proposalKey)) {
      String proposed = MatchingRules.normalize(text(op, "name"));
      var narrower = store.all(Category.class).stream().filter(c -> !c.deleted && !c.protectedCategory
          && c.mergedIntoId == null && Objects.equals(c.parentId, parent)
          && (MatchingRules.normalize(c.name).startsWith(proposed + " ")
              || !MatchingRules.normalize(c.name).equals(proposed) && VARIANT.matcher(c.name).find()
                  && variantBase(c.name).equals(variantBase(text(op, "name"))))).toList();
      if (!proposed.isBlank() && !narrower.isEmpty()) throw DomainException.invalid("GENERALIZE_REQUIRED: '" + text(op, "name")
          + "' abrange categorias existentes. Use GENERALIZE com sourceIds=" + narrower.stream().map(c -> c.id.toString()).toList()
          + ", em vez de CREATE, para atualizar também o histórico.");
    }
    if (kind.equals("MOVE")) {
      if (sources.size() != 1) throw DomainException.invalid("Move uma categoria de cada vez");
      sources.getFirst().parentId = parent; if (execution == null) sources.getFirst().protectedCategory = true;
    } else {
      Category target;
      UUID targetId = resolve(text(op, "targetId"), keys);
      if (targetId != null) target = canonical(targetId);
      else {
        if (proposalKey == null) throw DomainException.invalid("Falta a chave da categoria criada");
        if (execution != null && parent == null) throw DomainException.invalid("As categorias automáticas devem pertencer a uma família");
        var existing = named(text(op, "name"));
        if (existing != null && sourceIds.contains(existing.id)) throw DomainException.invalid("A promoção exige um conceito mais abrangente");
        target = create(text(op, "name"), parent, execution == null ? "MANUAL" : "AUTOMATIC", execution == null, text(op, "description"));
        if (existing == null) modified.add(target);
      }
      if (target.deleted) throw DomainException.invalid("Categoria de destino arquivada");
      if (execution == null && !target.protectedCategory) {
        if (!modified.contains(target)) before.add(image(target));
        modified.add(target); target.protectedCategory = true;
      }
      for (var source : sources) {
        if (source.id.equals(target.id) || descendant(target.id, source.id)) throw DomainException.invalid("Fusão circular de categorias");
        source.mergedIntoId = target.id; source.deleted = true;
        if (execution == null) source.protectedCategory = true;
        for (var child : store.all(Category.class).stream().filter(c -> source.id.equals(c.parentId)).toList()) {
          if (execution != null && child.protectedCategory) throw DomainException.invalid("Subcategoria protegida");
          before.add(image(child)); modified.add(child); child.parentId = target.id;
        }
      }
      String key = text(op, "key");
      if (key != null) { if (keys.putIfAbsent(key, target.id) != null) throw DomainException.invalid("Chave de categoria repetida"); }
    }
    if (modified.isEmpty()) return;
    checkTree(); bump(); store.flush();
    change(kind, before, modified.stream().map(this::image).toList(),
        Objects.requireNonNullElse(text(op, "reason"), "Evolução do catálogo"), text(op, "evidence"), execution);
  }

  public void merge(Map<String, Object> input) {
    store.lockFinancialWrites();
    var expectedIds = new HashSet<String>();
    if (input.get("sourceIds") instanceof List<?> sources) sources.forEach(id -> expectedIds.add(id.toString()));
    if (text(input, "targetId") != null) expectedIds.add(text(input, "targetId"));
    var provided = rows(input, "items").stream().map(row -> text(row, "id")).collect(Collectors.toSet());
    if (expectedIds.size() < 2 || !provided.equals(expectedIds)) throw DomainException.invalid("Indica as versões de todas as categorias da fusão");
    for (var item : rows(input, "items")) store.checkVersion(store.get(Category.class, uuid(item, "id")), version(item));
    var op = new LinkedHashMap<>(input); op.put("kind", "MERGE");
    operate(op, new HashMap<>(), null, null, Set.of()); refreshInheritance();
  }

  public void requestReview() {
    requestReview(false);
  }

  public void requestReview(boolean force) {
    store.lockFinancialWrites();
    String fingerprint = Hashes.sha256(Json.write(examples()) + ":" + revision());
    jobs.enqueue("REFINE_CATEGORIES", CategoryCatalog.ID, revision(), "{}", force ? UUID.randomUUID().toString() : fingerprint);
  }

  public void reviewWhenSettled(UUID currentJob) {
    Long pending = jdbc.queryForObject("select count(*) from job where id<>? and kind in ('CLASSIFY_INVOICE','CLASSIFY_MOVEMENT','NORMALIZE_CATEGORIES') and state in ('QUEUED','RUNNING','WAITING')", Long.class, currentJob);
    if (pending == 0) requestReview();
  }

  public void retryStale(String type, UUID id) {
    if (type.equals("catalog")) return;
    store.lockFinancialWrites();
    var s = state(type, id, false);
    if (s != null && s.status.equals("PENDING") && !Boolean.TRUE.equals(source(type, id).get("deleted"))) {
      s.generation++; enqueue(type, id, s);
    }
  }
}
