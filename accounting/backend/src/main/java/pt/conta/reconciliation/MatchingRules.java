package pt.conta.reconciliation;

import java.text.Normalizer;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure matching rules shared by automatic and manually confirmed allocations. */
public final class MatchingRules {
  private MatchingRules() {}

  /** Uses calendar-month distance only when the dates belong to different years. */
  public static boolean eligible(LocalDate invoice, LocalDate movement, int crossYearMonths) {
    return invoice != null
        && movement != null
        && (invoice.getYear() == movement.getYear()
            || Math.abs(
                    ChronoUnit.MONTHS.between(YearMonth.from(invoice), YearMonth.from(movement)))
                <= crossYearMonths);
  }

  /** Removes case, accents and punctuation while retaining token boundaries. */
  public static String normalize(String text) {
    return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toUpperCase(Locale.ROOT)
        .replaceAll("[^A-Z0-9]+", " ")
        .trim();
  }

  /** Requires a complete, meaningful reference; substring amounts are never evidence. */
  public static boolean reference(String number, String description) {
    String ref = normalize(number);
    return ref.length() >= 4
        && Pattern.compile("(?:^| )" + Pattern.quote(ref) + "(?: |$)")
            .matcher(normalize(description))
            .find();
  }

  /** Accepts a distinctive full supplier name, not generic one-word abbreviations. */
  public static boolean supplier(String supplier, String description) {
    String name =
        normalize(supplier)
            .replaceAll("\\b(LDA|SA|UNIPESSOAL|LIMITADA)\\b", "")
            .replaceAll(" +", " ")
            .trim();
    return name.length() >= 6
        && Pattern.compile("(?:^| )" + Pattern.quote(name) + "(?: |$)")
            .matcher(normalize(description))
            .find();
  }
}
