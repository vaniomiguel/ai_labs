import { Component, input, output, signal, inject, OnChanges, OnDestroy } from "@angular/core";
import { CommonModule } from "@angular/common";
import { MatButtonModule } from "@angular/material/button";
import { MatProgressSpinnerModule } from "@angular/material/progress-spinner";
import { Api, AlertView, euros, errorMessage } from "./api";

@Component({
  selector: "repair-panel", standalone: true,
  imports: [CommonModule, MatButtonModule, MatProgressSpinnerModule],
  template: `
    <section class="notice repair-panel" aria-label="Correção por IA">
      <strong>{{current()?.message || alert().message}}</strong>
      <div class="row-actions">
        <button mat-stroked-button [attr.aria-busy]="submitting()" [disabled]="pending() || current()?.status==='RESOLVED'" (click)="request()">
          @if(submitting()){<mat-spinner diameter="18" aria-label="A enviar pedido" />}{{submitting()?'A enviar pedido…':current()?.repair?'Pedir nova análise à IA':'✧ Pedir tentativa de correção à IA'}}
        </button>
        <button class="text-button" [disabled]="submitting()" (click)="refresh()">Atualizar proposta</button>
        @if(current()?.repair?.jobId){<button class="text-button" (click)="inspect.emit(current()!.repair!.jobId)">Ver processamento</button>}
      </div>
      @if(current()?.repair || submitting()){
        <div class="repair-status" role="status" aria-live="polite">
          @if(!submitting() && pending()){<mat-spinner diameter="22" [attr.aria-label]="status()" />}
          <div><strong>{{status()}}</strong>
            @if(current()?.repair?.state==='QUEUED' && !submitting()){<p>O pedido foi aceite e aguarda a sua vez.</p>}
            @if(current()?.repair?.state==='WAITING'){<p>A tentativa será retomada quando o modelo estiver disponível.</p>}
            @if(current()?.repair?.error){<p>{{current()!.repair!.error}}</p>}
            @if(current()?.repair?.outcome==='STALE'){<p>O documento ou o alerta foi alterado. Pede uma nova análise para usar os dados atuais.</p>}
          </div>
        </div>
      }
      @if(error()){<p class="error" role="alert">{{error()}}</p>}
      @if(current()?.proposal && !pending()){
        <details class="proposal" open>
          <summary>Proposta da IA · requer a tua confirmação</summary>
          @if(current()!.proposal.draft){
            @for(change of changes();track change.field){<div class="proposal-change">
              <strong>{{change.label}}</strong><div><section><small>Atual</small><pre>{{change.before}}</pre></section><span>→</span><section><small>Proposto</small><pre>{{change.after}}</pre></section></div>
            </div>}
            @if(!changes().length){<p>A análise não encontrou alterações aos dados apresentados.</p>}
            @if(issues().length){<div class="repair-issues"><strong>O que continua a precisar de revisão</strong><ul>@for(issue of issues();track $index){<li>{{issue}}</li>}</ul></div>}
            <button mat-flat-button [disabled]="!canApply()" (click)="apply.emit()">Aplicar proposta e validar</button>
          }@else{<p>{{current()!.proposal.summary || 'A análise não produziu uma correção de dados aplicável.'}}</p>
            @for(concern of current()!.proposal.concerns || [];track $index){<p>{{concern.message || concern.reason || concern.quote}}</p>}
          }
          @if(current()?.repair?.context;as context){<p class="hint">{{context.model}} · Texto integral{{context.pages.length?' e imagens de '+context.pages.length+' página(s)':''}}</p>}
        </details>
      }
    </section>
  `,
})
export class RepairPanel implements OnChanges, OnDestroy {
  private api=inject(Api);
  readonly alert=input.required<AlertView>();
  readonly document=input.required<any>();
  readonly progress=input("");
  readonly updated=output<AlertView>();
  readonly inspect=output<string>();
  readonly apply=output<void>();
  readonly current=signal<AlertView|null>(null);
  readonly submitting=signal(false);readonly error=signal("");
  private timer?: ReturnType<typeof setTimeout>;
  private sequence=0;private fetching=false;private destroyed=false;
  private identity="";private lastProgress="";private sourceVersion=-1;

  ngOnChanges() {
    const identity=this.alert().id+":"+this.document().id;
    const refresh=identity!==this.identity||this.lastProgress!==this.progress()||this.sourceVersion!==this.document().version;
    if(identity!==this.identity){this.sequence++;this.fetching=false;this.submitting.set(false);clearTimeout(this.timer);this.current.set(this.alert());this.error.set("");this.identity=identity;}
    this.lastProgress=this.progress();this.sourceVersion=this.document().version;
    if(refresh&&!this.submitting())void this.refresh();
  }
  pending() {return this.submitting()||!!this.current()?.repair&&this.current()?.repair?.outcome!=="STALE"&&["QUEUED","RUNNING","WAITING"].includes(this.current()!.repair!.state);}
  status() {
    if(this.submitting())return "A enviar pedido…";
    const task=this.current()?.repair;if(!task)return "";
    if(task.outcome==="STALE")return "Proposta desatualizada";
    if(task.state==="DONE")return task.outcome==="READY"?"Proposta disponível":task.outcome==="NO_CHANGES"?"Análise concluída · sem alterações":"Análise concluída · precisa de revisão";
    return ({QUEUED:"Em fila",RUNNING:"A analisar",WAITING:"A aguardar modelo",FAILED:"Falhou"} as Record<string,string>)[task.state];
  }
  private accept(alert:AlertView) {this.current.set(alert);this.updated.emit(alert);}
  private schedule() {clearTimeout(this.timer);if(!this.destroyed&&this.pending())this.timer=setTimeout(()=>void this.refresh(),this.current()?.repair?.state==="WAITING"?10000:2000);}
  async refresh() {
    if(this.destroyed||this.fetching||this.submitting())return;
    this.fetching=true;const sequence=this.sequence,id=this.alert().id;
    try{const next=await this.api.get<AlertView>("/alerts/"+id);if(!this.destroyed&&sequence===this.sequence){this.error.set("");this.accept(next);}}
    catch(e){if(!this.destroyed&&sequence===this.sequence)this.error.set(errorMessage(e));}
    finally{if(sequence===this.sequence){this.fetching=false;this.schedule();}}
  }
  async request() {
    if(this.pending())return;
    clearTimeout(this.timer);const sequence=++this.sequence,id=this.alert().id;
    this.fetching=false;this.submitting.set(true);this.error.set("");
    try{const next=await this.api.post<AlertView>("/alerts/"+id+"/ai");if(!this.destroyed&&sequence===this.sequence)this.accept(next);}
    catch(e){if(!this.destroyed&&sequence===this.sequence)this.error.set(errorMessage(e));}
    finally{if(sequence===this.sequence){this.submitting.set(false);this.schedule();}}
  }
  canApply(){return !this.pending()&&this.current()?.status==="OPEN"&&!!this.current()?.proposal?.draft&&
    (this.current()?.repair?.canApply??this.current()?.proposal?.documentVersion===this.document().version)&&this.changes().length>0;}
  issues():string[]{return [...new Set<string>([...(this.current()?.proposal?.issues||[]),...(this.current()?.repair?.blockingIssues||[])])];}
  changes():{field:string;label:string;before:string;after:string}[]{
    const proposal=this.current()?.proposal?.draft;if(!proposal)return [];
    const previous=this.document().draft||{};
    const flatten=(value:any)=>{
      const result:Record<string,any>={};
      const add=(row:any,prefix:string)=>Object.entries(row||{}).forEach(([key,v])=>{if(!["id","version","documentId","validate","issues","invoices"].includes(key))result[prefix+key]=v;});
      if(value.invoices?.length)value.invoices.forEach((row:any,n:number)=>add(row,"invoices."+n+"."));
      else add(value,proposal.invoices?.length||previous.invoices?.length?"invoices.0.":"");
      return result;
    };
    const before=flatten(previous),after=flatten(proposal);
    const names:Record<string,string>={supplier:"Fornecedor",supplierTaxId:"NIF do fornecedor",issuedOn:"Data de emissão",dueOn:"Vencimento",number:"Número da fatura",total:"Total",net:"Base sem IVA",tax:"IVA",discount:"Desconto",description:"Descrição",lines:"Artigos",currency:"Moeda",type:"Tipo",categories:"Categorias",movements:"Movimentos"};
    const format=(key:string,value:any)=>{
      if(value===null||value===undefined||value==="")return "Não preenchido";
      if(["total","net","tax","discount"].includes(key))return euros(value);
      if(Array.isArray(value))return value.length?value.map(row=>typeof row==='object'&&key==='lines'
        ?(row.description||'Artigo')+' · '+euros(row.total)
        :typeof row==='object'&&key==='movements'?[row.bookedOn,row.description,euros(row.amount)].filter(Boolean).join(' · ')
        :typeof row==='object'?JSON.stringify(row,null,2):row).join("\n"):"Nenhum";
      return String(value);
    };
    return [...new Set([...Object.keys(before),...Object.keys(after)])].filter(key=>JSON.stringify(before[key]??null)!==JSON.stringify(after[key]??null)).map(field=>{
      const parts=field.split('.'),key=parts.at(-1)!;
      return {field,label:(parts[0]==='invoices'?"Comprovativo "+(Number(parts[1])+1)+" · ":"")+(names[key]||key),before:format(key,before[field]),after:format(key,after[field])};
    });
  }
  ngOnDestroy(){this.destroyed=true;this.sequence++;clearTimeout(this.timer);}
}
