package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;
import pt.conta.ai.AutomaticReviewContext.*;
import pt.conta.shared.Json;
import pt.conta.shared.Settings;

/** Assembles review requests and partitions oversized input without dropping source text silently. */
@Component
class AutomaticReviewRequests {
  private static final int MAX_REQUEST_CHARACTERS = 50_000;

  record Excerpt(Source source, String text, int offset) {
    Map<String, Object> input() {
      return map("sourceId", source.id(), "documentId", source.documentId(), "documentKind", source.kind(),
          "coverage", !source.available() ? "MISSING" : complete() ? "FULL" : "PARTIAL",
          "offset", offset, "pages", pages(), "text", text);
    }

    private List<Map<String, Object>> pages() {
      int end = offset + text.length();
      return source.pages().stream().filter(page -> page.start() < end && page.end() > offset)
          .map(page -> map("page", page.number(), "start", Math.max(page.start(), offset) - offset,
              "end", Math.min(page.end(), end) - offset)).toList();
    }

    boolean complete() {
      return source.complete() && offset == 0 && text.length() == source.text().length();
    }
  }

  record Batch(List<Field> fields, List<Excerpt> excerpts) {
    boolean hasEvidence() {
      return fields.stream().anyMatch(field -> excerpts.stream()
          .anyMatch(source -> source.source().id().equals(field.sourceId()) && !source.text().isBlank()));
    }
  }

  record Instructions(String system, String checklist, Map<String, Object> schema, Object example) {}

  private final PromptCatalog prompts;
  private final Settings settings;

  AutomaticReviewRequests(PromptCatalog prompts, Settings settings) {
    this.prompts = prompts;
    this.settings = settings;
  }

  Instructions instructions(Snapshot snapshot) {
    String checklist = prompts.prompt(snapshot.kind().checklist);
    if (snapshot.kind() == Kind.DOCUMENT) {
      String kind = snapshot.sources().getFirst().kind();
      if ("INVOICE".equals(kind)) checklist += "\n" + prompts.prompt(PromptCatalog.AUTOMATIC_INVOICE_REVIEW_CHECKLIST);
      if ("STATEMENT".equals(kind)) checklist += "\n" + prompts.prompt(PromptCatalog.AUTOMATIC_MOVEMENT_REVIEW_CHECKLIST);
    }
    return new Instructions(prompts.prompt(PromptCatalog.AUTOMATIC_REVIEW_SYSTEM_INSTRUCTIONS), checklist,
        prompts.schema(PromptCatalog.AUTOMATIC_REVIEW_SYSTEM_INSTRUCTIONS),
        Json.value(prompts.prompt(PromptCatalog.AUTOMATIC_REVIEW_RESPONSE_EXAMPLE)));
  }

  Batch full(Snapshot snapshot) {
    return new Batch(snapshot.fields(), snapshot.sources().stream().map(s -> new Excerpt(s, s.text(), 0)).toList());
  }

  AiProvider.Request assemble(
      Snapshot snapshot, Batch batch, Instructions instructions, String model, boolean partial) {
    String input = Json.write(map("reviewType", snapshot.kind().name(), "checklist", instructions.checklist(),
        "coverage", partial ? "PARTIAL" : "FULL", "data", snapshot.data(),
        "fieldsToCheck", batch.fields().stream().map(Field::input).toList(),
        "sources", batch.excerpts().stream().map(Excerpt::input).toList(),
        "rules", map("currency", "EUR", "crossYearMonths", settings.get("crossYearMonths", "3")),
        "responseSchema", instructions.schema(), "responseExample", instructions.example()));
    return new AiProvider.Request(model, instructions.system(), input, instructions.schema(), List.of(), true);
  }

  boolean fits(AiProvider.Request request) {
    return (long) request.instructions().length() + request.input().length() + Json.write(request.schema()).length()
        <= MAX_REQUEST_CHARACTERS;
  }

  List<Batch> split(Batch batch, boolean separateFieldsFirst) {
    if (separateFieldsFirst && batch.fields().size() > 1) return splitFields(batch);
    var largest = batch.excerpts().stream().max(Comparator.comparingInt(e -> e.text().length())).orElseThrow();
    int split = boundary(largest);
    if (split > 0) {
      return List.of(withExcerpt(batch, largest, 0, split),
          withExcerpt(batch, largest, split, largest.text().length()));
    }
    if (batch.excerpts().size() > 1) {
      return batch.excerpts().stream()
          .map(excerpt -> new Batch(batch.fields().stream()
              .filter(field -> field.sourceId().equals(excerpt.source().id())).toList(), List.of(excerpt)))
          .filter(Batch::hasEvidence).toList();
    }
    return batch.fields().size() > 1 ? splitFields(batch) : List.of();
  }

  private List<Batch> splitFields(Batch batch) {
    int middle = batch.fields().size() / 2;
    return List.of(new Batch(batch.fields().subList(0, middle), batch.excerpts()),
        new Batch(batch.fields().subList(middle, batch.fields().size()), batch.excerpts()));
  }

  private Batch withExcerpt(Batch batch, Excerpt largest, int from, int to) {
    var excerpts = batch.excerpts().stream().map(excerpt -> excerpt == largest
        ? new Excerpt(excerpt.source(), excerpt.text().substring(from, to), excerpt.offset() + from) : excerpt).toList();
    return new Batch(batch.fields(), excerpts);
  }

  private int boundary(Excerpt excerpt) {
    String text = excerpt.text();
    int middle = text.length() / 2;
    int pageBoundary = -1;
    for (var page : excerpt.source().pages()) {
      int offset = page.start() - excerpt.offset();
      if (offset > 0 && offset < text.length()) pageBoundary = nearer(pageBoundary, offset, middle);
    }
    if (pageBoundary > 0) return pageBoundary;
    for (char separator : new char[] {'\f', '\n', '\r'}) {
      int lastUsable = text.length() - 2;
      if (separator == '\r' && text.endsWith("\r\n")) lastUsable--;
      int before = text.lastIndexOf(separator, Math.min(middle, lastUsable));
      int after = text.indexOf(separator, middle);
      int nearest = nearer(before, after > lastUsable ? -1 : after, middle);
      if (nearest >= 0) return separator == '\r' && text.charAt(nearest + 1) == '\n' ? nearest + 2 : nearest + 1;
    }
    return "HISTORY".equals(excerpt.source().kind()) ? workbookBoundary(excerpt, middle) : -1;
  }

  private int nearer(int previous, int candidate, int middle) {
    if (previous < 0) return candidate;
    return candidate < 0 || Math.abs(previous - middle) <= Math.abs(candidate - middle) ? previous : candidate;
  }

  private int workbookBoundary(Excerpt excerpt, int middle) {
    // Archived workbooks are compact JSON. A complete cells[] row is the analogue of a text line.
    int nearest = -1;
    try (var parser = new JsonFactory().createParser(excerpt.source().text())) {
      while (parser.nextToken() != null) {
        if (parser.currentToken() != JsonToken.START_ARRAY) continue;
        JsonStreamContext parent = parser.getParsingContext().getParent();
        if (parent == null || !parent.inArray() || parent.getParent() == null
            || !"cells".equals(parent.getParent().getCurrentName())) continue;
        int offset = (int) parser.currentTokenLocation().getCharOffset() - excerpt.offset();
        if (offset > 0 && offset < excerpt.text().length()) nearest = nearer(nearest, offset, middle);
      }
    } catch (IOException e) {
      return -1;
    }
    return nearest;
  }
}
