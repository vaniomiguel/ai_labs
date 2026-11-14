import { Component, input, output } from "@angular/core";
import { CategoryDetail, Classification } from "./api";

@Component({
  selector: "category-tags", standalone: true,
  template: `
    <div class="category-tags">
      @for(c of categories().slice(0,2); track c.id) {
        <button type="button" class="tag" [title]="explain(c)" (click)="filter.emit(c.id)">{{c.name}}{{c.archived?' · arquivada':''}}</button>
      }
      @if(categories().length>2) {
        <details class="more-categories"><summary>+{{categories().length-2}} categorias</summary><div>
          @for(c of categories().slice(2);track c.id){<button type="button" class="tag" [title]="explain(c)" (click)="filter.emit(c.id)">{{c.name}}{{c.archived?' · arquivada':''}}</button>}
        </div></details>
      }
      @if(!categories().length){<small [title]="classification()?.reason || ''">{{status()}}</small>}
      @if(categories().length && ['PENDING','WAITING','REVIEW','PROVISIONAL'].includes(classification()?.state || '')){<small [title]="classification()?.reason || ''">{{status()}}</small>}
      @if(editable()){<button type="button" class="text-button category-edit" (click)="manage.emit()">Editar categorias</button>}
    </div>
  `,
})
export class CategoryTags {
  readonly categories = input<CategoryDetail[]>([]);
  readonly classification = input<Classification | null>(null);
  readonly editable = input(false);
  readonly manage = output<void>();
  readonly filter = output<string>();
  explain(c: CategoryDetail) {
    return c.path + " · " + c.evidence.map(e => (e.origin === "AI" ? "IA" : e.origin === "INHERITED" ? "Fatura" : "Utilizador") + ": " + e.reason).join("; ");
  }
  status() {
    const state = this.classification()?.state;
    return state === "PENDING" ? "A classificar" : state === "WAITING" ? "A aguardar IA" : state === "REVIEW" ? "Rever categorias" : state === "PROVISIONAL" ? "Classificação provisória" : "Sem categorias";
  }
}
