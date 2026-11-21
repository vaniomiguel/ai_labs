import { Component, computed, inject, input, output, signal } from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { FormsModule } from "@angular/forms";
import { MatButtonModule } from "@angular/material/button";
import type { MatButton } from "@angular/material/button";
import { NavigationEnd, Router } from "@angular/router";
import { euros, labels } from "./api";
import type { ExpenseCategory } from "./api";
import { AppliedFilters } from "./applied-filters";
import type { AppliedFilter } from "./applied-filters";
import { defaultFinancialFilters, EMPTY_ADVANCED_FILTERS, FINANCIAL_FILTER_FIELDS } from "./workspace-filters";
import type { AdvancedFilters, FinancialFilterKey, FinancialFilters, FinancialFilterView } from "./workspace-filters";

const FILTER_LABELS: Record<FinancialFilterKey, string> = {
  year: "Ano", month: "Mês", q: "Pesquisa", day: "Dia", from: "Desde", to: "Até",
  category: "Categoria", status: "Revisão", direction: "Direção", min: "Montante mínimo",
  max: "Montante máximo", time: "Hora", participation: "Participação no saldo",
};

@Component({
  selector: "financial-filters",
  imports: [FormsModule, MatButtonModule, AppliedFilters],
  templateUrl: "./financial-filters.html",
})
export class FinancialFilterBar {
  readonly view = input.required<FinancialFilterView>();
  readonly filters = input.required<FinancialFilters>();
  readonly months = input.required<readonly string[]>();
  readonly categories = input.required<readonly ExpenseCategory[]>();
  readonly changed = output<Partial<FinancialFilters>>();
  readonly cleared = output<void>();

  readonly labels = labels;
  readonly reviewStatuses = ["OPEN", "AUTOMATIC", "MANUAL", "CORRECTED", "EDITED"];
  readonly requiresYear = computed(() => this.view() === "reconciliation" || this.view() === "balances");
  readonly hasSearch = computed(() => this.view() !== "balances");
  readonly hasAdvanced = computed(() => this.view() !== "balances" && this.view() !== "history");
  readonly hasMovementFields = computed(() => this.view() === "movements" || this.view() === "reconciliation");
  readonly searchDraft = signal<string | null>(null);
  readonly advancedOpen = signal(false);
  advancedDraft: AdvancedFilters = { ...EMPTY_ADVANCED_FILTERS };

  readonly chips = computed(() => this.fields().flatMap(key => this.filterChip(key)));
  readonly canClear = computed(() => {
    const defaults = defaultFinancialFilters();
    return this.searchDraft() !== null || this.fields().some(key => this.filters()[key] !== defaults[key]);
  });

  constructor() {
    inject(Router).events.pipe(takeUntilDestroyed()).subscribe(event => {
      if (event instanceof NavigationEnd) this.closeAdvanced();
    });
  }

  applyYear(input: HTMLInputElement) {
    if (!input.validity.valid) {
      input.value = String(this.filters().year ?? "");
      return;
    }
    this.changed.emit({ year: input.value === "" ? null : input.valueAsNumber });
  }

  search() {
    const query = (this.searchDraft() ?? this.filters().q).trim();
    this.searchDraft.set(null);
    this.changed.emit({ q: query });
  }

  toggleAdvanced() {
    if (this.advancedOpen()) {
      this.closeAdvanced();
      return;
    }
    const { day, from, to, category, status, direction, min, max, time, participation } = this.filters();
    this.advancedDraft = { day, from, to, category, status, direction, min, max, time, participation };
    this.advancedOpen.set(true);
  }

  closeAdvanced(trigger?: MatButton) {
    if (!this.advancedOpen()) return;
    this.advancedOpen.set(false);
    trigger?.focus();
  }

  dismissOnBlur(event: FocusEvent, control: HTMLElement) {
    if (!(event.relatedTarget instanceof Node) || !control.contains(event.relatedTarget)) {
      this.closeAdvanced();
    }
  }

  applyAdvanced(trigger: MatButton) {
    const { min, max, ...draft } = this.advancedDraft;
    this.closeAdvanced(trigger);
    this.changed.emit({ ...draft, min: min.trim().replace(",", "."), max: max.trim().replace(",", ".") });
  }

  clear() {
    this.searchDraft.set(null);
    this.closeAdvanced();
    this.cleared.emit();
  }

  remove(name: string) {
    const key = this.fields().find(field => field === name);
    if (!key) return;
    if (key === "q") this.searchDraft.set(null);
    if (key === "year" && !this.requiresYear()) {
      this.changed.emit({ year: null });
      return;
    }
    this.changed.emit({ [key]: defaultFinancialFilters()[key] });
  }

  private fields(): readonly FinancialFilterKey[] {
    return FINANCIAL_FILTER_FIELDS[this.view()];
  }

  private filterChip(key: FinancialFilterKey): AppliedFilter[] {
    const value = this.filters()[key];
    if (value === null || value === "") return [];
    const removable = key !== "year" || !this.requiresYear() || value !== defaultFinancialFilters().year;
    return [{ key, label: `${FILTER_LABELS[key]}: ${this.formatValue(key, value)}`, removable }];
  }

  private formatValue(key: FinancialFilterKey, value: number | string): string {
    const text = String(value);
    switch (key) {
      case "month": return this.months()[Number(text) - 1];
      case "from":
      case "to": return text.split("-").reverse().join("/");
      case "category": return this.categories().find(category => category.id === text)?.path ?? "Categoria indisponível";
      case "status":
      case "direction": return labels[text] ?? text;
      case "min":
      case "max": return euros(text);
      case "participation": return text === "active" ? "Incluídos" : "Excluídos";
      default: return text;
    }
  }
}
