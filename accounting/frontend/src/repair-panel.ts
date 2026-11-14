import { Component, input, output, signal, inject, OnChanges, OnDestroy } from "@angular/core";
import { CommonModule } from "@angular/common";
import { MatButtonModule } from "@angular/material/button";
import { MatProgressSpinnerModule } from "@angular/material/progress-spinner";
import { Api, AlertView, FieldSuggestion, RepairApplyRequest, RepairPreview, euros, errorMessage } from "./api";

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
            @for(item of items();track item.id){
              <section class="repair-item" [attr.aria-label]="item.label">
                <label><input type="checkbox" [checked]="included().has(item.id)" (change)="toggleItem(item.id)" /> {{item.label}}</label>
                @for(change of changesFor(item.id);track change.key){<div class="proposal-change">
                  <strong>{{fieldName(change.field)}} · {{change.kind==='COMPLEMENT'?'Complemento':'Sugestão de alteração'}}</strong>
                  @if(change.protected){<small class="human-decision">Valor definido ou confirmado por ti · protegido</small>}
                  <div><section><small>Atual</small><pre>{{format(change.field,change.current)}}</pre></section><span>→</span><section><small>Proposto pela IA</small><pre>{{format(change.field,change.proposed)}}</pre></section></div>
                  @if(evidence(change);as proof){<p class="hint">Evidência: {{proof}}</p>}
                  @if(change.kind==='CONFLICT'){
                    <div class="row-actions" [attr.aria-label]="'Escolha para '+fieldName(change.field)">
                      <button mat-stroked-button [attr.aria-pressed]="!accepted().has(change.key)" (click)="choose(change.key,false)">Manter o meu valor</button>
                      <button mat-stroked-button [attr.aria-pressed]="accepted().has(change.key)" [disabled]="!included().has(item.id)" (click)="choose(change.key,true)">Aceitar valor da IA</button>
                    </div>
                  }
                </div>}
              </section>
            }
            @if(!changes().length){<p>A análise não encontrou complementos ou alterações aplicáveis aos dados apresentados.</p>}
            @if(current()?.proposal?.structureIssue){<p class="error">{{current()!.proposal.structureIssue}}</p>}
            @if(issues().length){<div class="repair-issues"><strong>O que continua a precisar de revisão</strong><ul>@for(issue of issues();track $index){<li>{{issue}}</li>}</ul></div>}
            <p class="hint">Os teus valores mantêm-se. Apenas os complementos e as alterações que aceitares serão guardados nos itens selecionados.</p>
            <div class="row-actions">
              <button mat-stroked-button [disabled]="!canDraft()" (click)="apply.emit(payload(false))">Aplicar ao rascunho</button>
              <button mat-flat-button [disabled]="!canApply()" (click)="apply.emit(payload(true))" aria-describedby="repair-validation-reason">Aplicar proposta e validar</button>
            </div>
            <p id="repair-validation-reason" class="hint" role="status">{{validationReason()}}</p>
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
  readonly edits=input(0);
  readonly updated=output<AlertView>();
  readonly inspect=output<string>();
  readonly apply=output<RepairApplyRequest>();
  readonly current=signal<AlertView|null>(null);
  readonly preview=signal<RepairPreview|null>(null);
  readonly accepted=signal(new Set<string>());
  readonly included=signal(new Set<string>());
  private previewSequence=0;private previewTimer?:ReturnType<typeof setTimeout>;private choiceIdentity="";private lastEdits=-1;
  readonly previewing=signal(false);
  readonly submitting=signal(false);readonly error=signal("");
  private timer?: ReturnType<typeof setTimeout>;
  private sequence=0;private fetching=false;private destroyed=false;
  private identity="";private lastProgress="";private sourceVersion=-1;

  ngOnChanges() {
    const identity=this.alert().id+":"+this.document().id;
    const refresh=identity!==this.identity||this.lastProgress!==this.progress()||this.sourceVersion!==this.document().version;
    if(identity!==this.identity){this.sequence++;this.previewSequence++;clearTimeout(this.previewTimer);this.previewing.set(false);this.accepted.set(new Set());this.included.set(new Set());this.fetching=false;this.submitting.set(false);clearTimeout(this.timer);this.current.set(this.alert());this.preview.set(null);this.choiceIdentity="";this.error.set("");this.identity=identity;}
    this.lastProgress=this.progress();this.sourceVersion=this.document().version;
    if(refresh&&!this.submitting())void this.refresh();
    if(this.lastEdits!==this.edits()){this.lastEdits=this.edits();this.accepted.set(new Set());this.queuePreview();}
  }
  pending() {return this.submitting()||!!this.current()?.repair&&this.current()?.repair?.outcome!=="STALE"&&["QUEUED","RUNNING","WAITING"].includes(this.current()!.repair!.state);}
  status() {
    if(this.submitting())return "A enviar pedido…";
    const task=this.current()?.repair;if(!task)return "";
    if(task.outcome==="STALE")return "Proposta desatualizada";
    if(task.state==="DONE")return task.outcome==="READY"?"Proposta disponível":task.outcome==="NO_CHANGES"?"Análise concluída · sem alterações":"Análise concluída · precisa de revisão";
    return ({QUEUED:"Em fila",RUNNING:"A analisar",WAITING:"A aguardar modelo",FAILED:"Falhou"} as Record<string,string>)[task.state];
  }
  private accept(alert:AlertView) {
    this.current.set(alert);this.updated.emit(alert);
    const identity=alert.id+":"+(alert.proposal?.jobId||alert.proposal?.documentVersion||"");
    if(identity!==this.choiceIdentity){this.choiceIdentity=identity;this.accepted.set(new Set());this.included.set(new Set(this.baseChanges().map(c=>c.itemId).filter(id=>alert.entityType==='document'||id===alert.entityId||id==='root'&&this.document().draft?.id===alert.entityId)));}
    if(alert.proposal?.draft&&!this.pending())this.queuePreview();
  }
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
    finally{if(sequence===this.sequence){this.submitting.set(false);this.schedule();if(this.current()?.proposal?.draft&&!this.pending())this.queuePreview();}}
  }
  private fresh(){return !this.pending()&&this.current()?.status==='OPEN'&&this.current()?.proposal?.documentVersion===this.document().version&&this.current()?.repair?.outcome!=='STALE';}
  canDraft(){return this.fresh()&&!this.previewing()&&!!this.preview()?.canApplyDraft;}
  canApply(){return this.canDraft()&&!!this.preview()?.canValidate;}
  issues():string[]{return [...new Set<string>([...(this.current()?.proposal?.issues||[]),...(this.preview()?.blockingIssues||this.current()?.repair?.blockingIssues||[])])];}
  validationReason(){
    if(!this.fresh())return 'A análise tem de estar concluída e corresponder à versão atual do documento.';
    if(this.previewing())return 'A verificar as escolhas…';
    if(!this.canDraft())return 'Seleciona um item com complementos ou aceita uma sugestão de alteração.';
    if(!this.canApply())return 'Podes guardar os complementos no rascunho. Para validar: '+(this.preview()?.blockingIssues||[]).join('; ');
    return 'A validação aplica-se apenas aos itens selecionados.';
  }
  payload(validate:boolean):RepairApplyRequest{return {documentVersion:this.document().version,proposalId:this.current()?.proposal?.jobId||this.current()!.id,draft:structuredClone(this.document().draft),acceptedFields:[...this.accepted()],selectedItems:[...this.included()],validate};}
  choose(key:string,accept:boolean){const selected=new Set(this.accepted());accept?selected.add(key):selected.delete(key);this.accepted.set(selected);this.queuePreview();}
  toggleItem(id:string){const selected=new Set(this.included());selected.has(id)?selected.delete(id):selected.add(id);this.included.set(selected);this.queuePreview();}
  private queuePreview(){clearTimeout(this.previewTimer);this.previewing.set(true);const sequence=++this.previewSequence;this.previewTimer=setTimeout(()=>void this.loadPreview(sequence),200);}
  private async loadPreview(sequence:number){
    if(!this.fresh()||!this.current()?.proposal?.draft){this.previewing.set(false);return;}
    try{const result=await this.api.post<RepairPreview>('/alerts/'+this.current()!.id+'/preview',this.payload(false));if(!this.destroyed&&sequence===this.previewSequence){this.preview.set(result);this.error.set('');}}
    catch(e){if(!this.destroyed&&sequence===this.previewSequence){this.preview.set(null);this.error.set(errorMessage(e));}}
    finally{if(sequence===this.previewSequence)this.previewing.set(false);}
  }
  private baseChanges():FieldSuggestion[]{return this.current()?.proposal?.changes||[];}
  changes():FieldSuggestion[]{return this.preview()?.changes||this.baseChanges();}
  changesFor(id:string){return this.changes().filter(c=>c.itemId===id);}
  items(){return [...new Map(this.changes().map(c=>[c.itemId,{id:c.itemId,label:(this.document().kind==='STATEMENT'?'Movimento ':'Comprovativo ')+(c.itemIndex+1)}])).values()];}
  fieldName(field:string){return ({supplier:'Fornecedor',supplierTaxId:'NIF do fornecedor',issuedOn:'Data de emissão',dueOn:'Vencimento',number:'Número da fatura',total:'Total',net:'Base sem IVA',tax:'IVA',discount:'Desconto',description:'Descrição',lines:'Artigos',currency:'Moeda',type:'Tipo',bookedOn:'Data do movimento',valueOn:'Data valor',amount:'Montante',direction:'Direção',bankReference:'Referência bancária',accountId:'Conta',kind:'Natureza do movimento'} as Record<string,string>)[field]||field;}
  format(field:string,value:any):string{
    if(value===null||value===undefined||value==='')return 'Não preenchido';
    if(['total','net','tax','discount','amount'].includes(field))return euros(value);
    if(Array.isArray(value))return value.length?value.map(row=>typeof row==='object'?Object.entries(row).filter(([k])=>!['id','version','invoiceId','position'].includes(k)).map(([k,v])=>this.fieldName(k)+': '+(v===null?'Não preenchido':v)).join(' · '):String(row)).join('\n'):'Nenhum';
    return String(value);
  }
  evidence(change:FieldSuggestion):string{
    const raw=this.current()?.proposal?.evidence;const evidence=Array.isArray(raw)?raw:[];
    return evidence.filter((e:any)=>e.field===change.field||e.field===`invoices[${change.proposalIndex??change.itemIndex}].${change.field}`||e.field===`movements[${change.proposalIndex??change.itemIndex}].${change.field}`).map((e:any)=>e.quote||e.reason||'').filter(Boolean).join(' · ');
  }
  ngOnDestroy(){this.destroyed=true;this.sequence++;this.previewSequence++;clearTimeout(this.timer);clearTimeout(this.previewTimer);}
}
