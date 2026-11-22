import { Component, computed, input, linkedSignal, output, untracked } from "@angular/core";
import { CategoryDetail, InvoiceCategoryReview } from "./api";

@Component({
  selector: "invoice-category-review",
  standalone: true,
  template: `
    <section aria-label="Revisão das categorias por IA">
      <p>{{ report().summary }}</p>
      <fieldset [disabled]="disabled()">
        <legend>Categorias a guardar</legend>
        @for (assessment of report().assessments; track assessment.categoryId) {
          <div>
            <label class="category-choice">
              <input type="checkbox" [checked]="selected().has(assessment.categoryId)" (change)="toggle(assessment.categoryId)" />
              {{ categoryPath(assessment.categoryId) }}
            </label>
            <p><strong>{{ fitLabel[assessment.fit] }}</strong> · {{ assessment.reason }}</p>
          </div>
        }
        @if (report().suggestions.length) {
          <p>Sugestões da IA</p>
          @for (suggestion of report().suggestions; track suggestion.categoryId) {
            <div>
              <label class="category-choice">
                <input type="checkbox" [checked]="selected().has(suggestion.categoryId)" (change)="toggle(suggestion.categoryId)" />
                {{ suggestion.path }}
              </label>
              <p>{{ suggestion.reason }} · <q>{{ suggestion.evidence }}</q></p>
              @if (suggestion.replacesCategoryIds.length) {
                <p>Alternativa a {{ replacementPaths(suggestion.replacesCategoryIds) }}.</p>
              }
            </div>
          }
        }
        <button type="button" class="text-button" [disabled]="!hasChanges()" (click)="approveSelection()">
          Guardar alterações
        </button>
      </fieldset>
    </section>
  `,
})
export class InvoiceCategoryReviewPanel {
  readonly report = input.required<InvoiceCategoryReview>();
  readonly jobId = input.required<string>();
  readonly categories = input.required<CategoryDetail[]>();
  readonly disabled = input(false);
  readonly approved = output<string[]>();
  protected readonly selected = linkedSignal({
    source: this.jobId,
    computation: () => new Set(untracked(() => this.categories().map(category => category.id))),
  });
  protected readonly hasChanges = computed(() => {
    const selected = this.selected();
    const current = this.categories();
    return selected.size !== current.length || current.some(category => !selected.has(category.id));
  });
  protected readonly fitLabel = {
    GOOD_FIT: "Adequada",
    POOR_FIT: "Pouco adequada",
    INSUFFICIENT_EVIDENCE: "Evidência insuficiente",
  };
  private readonly paths = computed(() => new Map(this.categories().map(category => [category.id, category.path])));

  protected categoryPath(id: string) { return this.paths().get(id) ?? id; }

  protected replacementPaths(ids: string[]) { return ids.map(id => this.categoryPath(id)).join(", "); }

  protected approveSelection() { this.approved.emit([...this.selected()]); }

  protected toggle(id: string) {
    this.selected.update(current => {
      const selected = new Set(current);
      if (selected.has(id)) selected.delete(id);
      else selected.add(id);
      return selected;
    });
  }
}
