import { Component, input, output, signal, inject, OnInit } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { MatButtonModule } from "@angular/material/button";
import { A11yModule } from "@angular/cdk/a11y";
import { Api, ExpenseCategory, errorMessage } from "./api";

@Component({
  selector: "category-editor", standalone: true, imports: [FormsModule, MatButtonModule, A11yModule],
  template: `
    <div class="modal-backdrop" (keydown.escape)="!saving() && closed.emit()">
      <section class="dialog compact" role="dialog" aria-modal="true" aria-labelledby="category-editor-title" cdkTrapFocus [cdkTrapFocusAutoCapture]="true">
        <header class="dialog-heading"><h2 id="category-editor-title">Categorias do {{type()==='invoice'?'documento':'movimento'}}</h2><button class="icon-button" aria-label="Fechar categorias" [disabled]="saving()" (click)="closed.emit()">×</button></header>
        <div class="dialog-body">
          <p>{{record().number || record().supplier || record().description}}</p>
          <p class="hint">As escolhas manuais prevalecem sobre a IA e a herança das faturas.</p>
          <details class="details-panel category-evidence"><summary>Origem e justificação das categorias</summary>
            @if(record().classification?.reason){<p>{{record().classification.reason}}</p>}
            @for(c of record().categoryDetails || [];track c.id){
              <article><strong>{{c.path}}</strong>
                @for(e of c.evidence;track $index){<p><small>{{origin(e.origin)}}</small> · {{e.reason}}</p>}
              </article>
            }
          </details>
          <label>Pesquisar categorias<input [(ngModel)]="query" placeholder="Nome ou família" /></label>
          <div class="category-choice-list">
            @for(c of visible();track c.id){<label class="category-choice"><input type="checkbox" [checked]="selected().has(c.id)" [disabled]="saving() || c.archived&&!selected().has(c.id)" (change)="toggle(c.id)" />
              <span>{{c.path}}<small>{{c.archived?'Arquivada':c.origin==='REFERENCE'?'Referência SNC':c.protected?'Protegida':'Evolução automática'}}</small></span>
            </label>}
          </div>
          @if(record().classification?.rejectedCategoryIds?.length){<p class="hint">Categorias removidas manualmente: {{rejectedNames()}}.</p>}
          <details class="details-panel"><summary>Criar uma categoria</summary>
            <label>Nome da nova categoria<input [(ngModel)]="newName" /></label>
            <label>Família da nova categoria<select [(ngModel)]="newParent"><option value="">Sem família</option>@for(c of catalog();track c.id){@if(!c.archived){<option [value]="c.id">{{c.path}}</option>}}</select></label>
            <button mat-stroked-button [disabled]="saving() || !newName.trim()" (click)="create()">Criar e selecionar</button>
          </details>
          @if(error()){<p class="error" role="alert">{{error()}}</p>}
        </div>
        <footer class="dialog-footer"><button class="text-button" [disabled]="saving()" (click)="save(true)">Permitir IA nas removidas</button><button mat-flat-button [disabled]="saving()" (click)="save(false)">Guardar categorias</button></footer>
      </section>
    </div>
  `,
})
export class CategoryEditor implements OnInit {
  private api = inject(Api);
  readonly type = input.required<"invoice" | "movement">();
  readonly record = input.required<any>();
  readonly closed = output<void>();
  readonly changed = output<void>();
  readonly catalog = signal<ExpenseCategory[]>([]);
  readonly selected = signal(new Set<string>());
  readonly saving = signal(false);
  readonly error = signal("");
  query = ""; newName = ""; newParent = "";
  origin(value: string) { return value === "AI" ? "IA" : value === "INHERITED" ? "Herdada da fatura" : value === "LEGACY" ? "Categoria migrada" : "Escolha manual"; }
  async ngOnInit() {
    this.selected.set(new Set((this.record().categoryDetails ?? []).map((c: any) => c.id)));
    try { this.catalog.set(await this.api.get<ExpenseCategory[]>("/categories", { archived: true })); }
    catch(e) { this.error.set(errorMessage(e)); }
  }
  visible() { return this.catalog().filter(c => !c.mergedIntoId && (!c.archived || this.selected().has(c.id)) && c.path.toLowerCase().includes(this.query.toLowerCase())); }
  toggle(id: string) { const s = new Set(this.selected()); if(s.has(id))s.delete(id);else s.add(id);this.selected.set(s); }
  rejectedNames() { return this.record().classification.rejectedCategoryIds.map((id: string) => this.catalog().find(c => c.id===id)?.name ?? id).join(", "); }
  async create() {
    this.saving.set(true); this.error.set("");
    try {
      const c = await this.api.post<ExpenseCategory>("/categories", { name: this.newName.trim(), parentId: this.newParent || null, aliases: [] });
      this.catalog.update(list => [...list,c].sort((a,b)=>a.path.localeCompare(b.path)));
      this.selected.update(s => new Set([...s,c.id])); this.newName="";
    } catch(e) { this.error.set(errorMessage(e)); } finally { this.saving.set(false); }
  }
  async save(allowAi: boolean) {
    this.saving.set(true); this.error.set("");
    try {
      await this.api.put(`/${this.type()==='invoice'?'invoices':'movements'}/${this.record().id}/categories`, {
        version: this.record().classification?.version ?? 0, categoryIds: [...this.selected()], allowAi,
      });
      this.changed.emit(); this.closed.emit();
    } catch(e) { this.error.set(errorMessage(e)); } finally { this.saving.set(false); }
  }
}
