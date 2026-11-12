package pt.conta.reconciliation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/** A versioned selection, rather than a transient group identity, is the unit of an edit. */
public record ExclusionRequest(
    @NotNull Boolean excluded,
    @NotEmpty List<@NotNull @Valid Item> items,
    @Size(max = 2000) String reason) {
  public record Item(
      @NotNull @Pattern(regexp = "invoice|movement") String type,
      @NotNull UUID id,
      @NotNull @PositiveOrZero Long version) {}
}
