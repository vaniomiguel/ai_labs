import { Component, input, output } from "@angular/core";
import { MatButtonModule } from "@angular/material/button";

export interface AppliedFilter {
  key: string;
  label: string;
  removable?: boolean;
}

@Component({
  selector: "applied-filters",
  imports: [MatButtonModule],
  template: `
    <div class="applied-filters" role="group" aria-label="Filtros aplicados">
      @if (items().length) {
        <ul class="filter-chips">
          @for (item of items(); track item.key) {
            <li>
              @if (item.removable === false) {
                <span class="filter-chip">{{ item.label }}</span>
              } @else {
                <button type="button" class="filter-chip" [attr.aria-label]="'Remover ' + item.label"
                  (click)="clearButton.focus(); removed.emit(item.key)">
                  {{ item.label }} <span aria-hidden="true">×</span>
                </button>
              }
            </li>
          }
        </ul>
      } @else {
        <span class="hint">Sem filtros aplicados</span>
      }
      <button #clearButton type="button" mat-button [attr.aria-disabled]="!canClear()"
        (click)="canClear() && cleared.emit()">Limpar filtros</button>
    </div>
  `,
})
export class AppliedFilters {
  readonly items = input.required<readonly AppliedFilter[]>();
  readonly canClear = input.required<boolean>();
  readonly removed = output<string>();
  readonly cleared = output<void>();
}
