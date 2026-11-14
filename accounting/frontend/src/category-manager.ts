import { Component, signal, inject, input, effect, output } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { MatButtonModule } from "@angular/material/button";
import { Api, ExpenseCategory, errorMessage } from "./api";

@Component({
  selector: "category-manager", standalone: true, imports: [CommonModule, FormsModule, MatButtonModule],
  templateUrl: "./category-manager.html",
})
export class CategoryManager {
  private api = inject(Api);
  readonly changed = output<void>();
  readonly catalogUpdates = input<ExpenseCategory[]>([]);
  readonly catalog = signal<ExpenseCategory[]>([]);
  readonly changes = signal<any[]>([]);
  readonly edit = signal<any>(null);
  readonly error = signal(""); readonly notice = signal(""); readonly saving = signal(false);
  readonly selected = signal(new Set<string>());
  query=""; showArchived=false; mergeTarget="";
  constructor() { effect(()=>{this.catalogUpdates();void this.load();}); }
  async load() {
    try { const [catalog,changes] = await Promise.all([this.api.get<ExpenseCategory[]>("/categories",{archived:true}),this.api.get<any[]>("/categories/changes")]);this.catalog.set(catalog);this.changes.set(changes); }
    catch(e) { this.error.set(errorMessage(e)); }
  }
  visible() { return this.catalog().filter(c => (this.showArchived||!c.archived)&&c.path.toLowerCase().includes(this.query.toLowerCase())); }
  categoryName(id:string) { return this.catalog().find(c=>c.id===id)?.name || "Categoria de destino"; }
  changeLabel(kind:string) { return ({EDIT:"Edição",CREATE:"Criação",GENERALIZE:"Generalização",MERGE:"Fusão",MOVE:"Mudança de família",ARCHIVE:"Arquivo",DELETE_UNUSED:"Eliminação"} as Record<string,string>)[kind] || kind; }
  changeNames(rows:any[]) { return [...new Set(rows.map(row=>row.mergedIntoId?this.categoryName(row.mergedIntoId):row.name))].join(", ") || "Sem categoria"; }
  begin(c?: ExpenseCategory) { this.edit.set(c ? { ...c, aliasesText:c.aliases.join("; ") } : { name:"",parentId:null,aliasesText:"",description:"",protected:true }); }
  toggle(id:string) { const s=new Set(this.selected());if(s.has(id))s.delete(id);else s.add(id);this.selected.set(s); }
  async run(action:()=>Promise<unknown>) {
    this.saving.set(true);this.error.set("");this.notice.set("");
    try { await action();await this.load();this.changed.emit(); } catch(e) {this.error.set(errorMessage(e));} finally {this.saving.set(false);}
  }
  async save() { const e=this.edit(); await this.run(async()=>{await this.api.post("/categories",{...e,aliases:e.aliasesText.split(";").map((s:string)=>s.trim()).filter(Boolean)});this.edit.set(null);}); }
  async archive(c:ExpenseCategory,permanently=false) { await this.run(()=>this.api.delete("/categories/"+c.id,{version:c.version,permanently})); }
  async restore(c:ExpenseCategory) { await this.run(()=>this.api.post("/categories",{id:c.id,version:c.version,archived:false})); }
  async protect(protectedCategory:boolean) {
    await this.run(()=>this.api.post("/categories/protection",{protected:protectedCategory,items:this.catalog().filter(c=>this.selected().has(c.id)).map(c=>({id:c.id,version:c.version}))}));
  }
  async merge() {
    const sources=[...this.selected()].filter(id=>id!==this.mergeTarget);
    await this.run(async()=>{await this.api.post("/categories/merge",{sourceIds:sources,targetId:this.mergeTarget,items:this.catalog().filter(c=>sources.includes(c.id)||c.id===this.mergeTarget).map(c=>({id:c.id,version:c.version})),reason:"Fusão escolhida pelo utilizador"});this.selected.set(new Set());this.mergeTarget="";});
  }
  async review() { await this.run(async()=>{await this.api.post("/categories/review");this.notice.set("Revisão do catálogo em fila. Consulta o progresso em Processamento.");}); }
  async undo(change:any) { await this.run(()=>this.api.post("/categories/changes/"+change.id+"/undo",{version:change.version})); }
}
