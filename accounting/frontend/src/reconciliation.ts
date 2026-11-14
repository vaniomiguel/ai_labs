import { Component, input, output, signal, inject } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { MatButtonModule } from "@angular/material/button";
import { A11yModule } from "@angular/cdk/a11y";
import { CategoryTags } from "./category-tags";
import { Api, euros, labels, errorMessage, ReconciliationEntry, ReconciliationPage, ExclusionItem, ExclusionRequest } from "./api";

/** Renders backend-calculated money and complete groups, including excluded single records. */
@Component({
  selector: "reconciliation-table",
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, A11yModule, CategoryTags],
  templateUrl: "./reconciliation.html",
})
export class ReconciliationTable {
  private readonly api = inject(Api);
  readonly data = input.required<ReconciliationPage>();
  readonly loading = input(false);
  readonly working = input(false);
  readonly candidates = input<any[]>([]);
  readonly aiSuggestions = input<any[]>([]);
  readonly reconcile = output<void>();
  readonly changed = output<void>();
  readonly pageChange = output<number>();
  readonly categories = output<{type:"invoice"|"movement";record:any}>();
  readonly categoryFilter = output<string>();
  readonly edit = output<{ kind: string; record: any }>();
  readonly remove = output<{ kind: string; record: any }>();
  readonly original = output<string>();
  readonly assign = output<{ invoice: any; candidate?: any }>();
  readonly assignMovement = output<any>();
  readonly undo = output<any>();
  readonly reverse = output<any>();
  readonly expanded = signal(new Set<string>());
  readonly selection = signal<{ items: (ExclusionItem & { label: string; selected: boolean })[] } | null>(null);
  readonly saving = signal(false);
  readonly error = signal("");
  readonly money = euros;
  readonly labels = labels;

  groupCategories(entry: ReconciliationEntry) {
    return [...new Map([...entry.invoices,...entry.movements].flatMap(r => r.categoryDetails ?? []).map(c => [c.id,c])).values()];
  }
  toggle(id: string) {
    const expanded = new Set(this.expanded());
    if (expanded.has(id)) expanded.delete(id); else expanded.add(id);
    this.expanded.set(expanded);
  }
  candidatesFor(id: string) { return this.candidates().filter(c => c.invoiceId === id).slice(0, 5); }
  aiFor(id: string) { return this.aiSuggestions().find(s => s.invoiceId === id); }
  movementName(entry: ReconciliationEntry, id: string) {
    return entry.movements.find(m => m.id === id)?.description ?? id.slice(0, 8);
  }
  invoiceName(entry: ReconciliationEntry, id: string) {
    const invoice = entry.invoices.find(i => i.id === id);
    return invoice?.number || invoice?.supplier || id.slice(0, 8);
  }
  async participation(entry: ReconciliationEntry) {
    const items = [
      ...entry.invoices.map(i => ({ type: "invoice" as const, id: i.id, version: i.version, label: i.number || i.supplier || "Fatura", selected: false })),
      ...entry.movements.map(m => ({ type: "movement" as const, id: m.id, version: m.version, label: m.description || "Movimento", selected: false })),
    ];
    this.error.set("");
    if (entry.excluded || items.length === 1) await this.saveParticipation(!entry.excluded, items);
    else this.selection.set({ items });
  }
  select(type: string) {
    const selection = this.selection();
    if (selection) this.selection.set({ items: selection.items.map(i => ({ ...i, selected: type === "all" || i.type === type })) });
  }
  hasSelection() { return this.selection()?.items.some(i => i.selected) ?? false; }
  async applySelection() {
    const items = this.selection()?.items.filter(i => i.selected);
    if (items?.length) await this.saveParticipation(true, items);
  }
  private async saveParticipation(excluded: boolean, items: ExclusionItem[]) {
    this.saving.set(true);
    this.error.set("");
    try {
      const request: ExclusionRequest = {
        excluded, items: items.map(({ type, id, version }) => ({ type, id, version })),
      };
      await this.api.post("/reconciliation/exclusions", request);
      this.selection.set(null);
      this.changed.emit();
    } catch (e) { this.error.set(errorMessage(e)); }
    finally { this.saving.set(false); }
  }
}
