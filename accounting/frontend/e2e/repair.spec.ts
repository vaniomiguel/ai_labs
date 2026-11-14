import { expect, test, Page } from "@playwright/test";

const docId="e245b1ab-5314-4ad8-a91f-c75510592001",alertId="e245b1ab-5314-4ad8-a91f-c75510592002",jobId="e245b1ab-5314-4ad8-a91f-c75510592003";
const valid=()=>({supplier:"Fornecedor IA",issuedOn:"2026-01-01",total:"100.00",currency:"EUR",type:"INVOICE",lines:[],categories:[]});
async function setup(page:Page,initial:any={supplier:"",issuedOn:null,total:null,currency:"EUR",type:"INVOICE",lines:[],categories:[]}) {
  const csrf=await(await page.request.get("/api/v1/csrf")).json();
  expect((await page.request.post("/api/v1/session",{form:{username:"gestor",password:"conta-e2e-dev"},headers:{"X-XSRF-TOKEN":csrf.token}})).ok()).toBeTruthy();
  const document={id:docId,version:0,filename:"Fatura-correção.pdf",kind:"INVOICE",status:"OPEN",mediaType:"text/plain",text:"Documento sintético completo",pages:1,layout:{},draft:initial};
  const state:any={alert:{id:alertId,version:0,documentId:docId,entityType:"document",entityId:docId,code:"INVALID_INVOICE",message:"Falta a data da fatura; Falta o fornecedor",status:"OPEN",proposal:null,repair:null},posts:0,gets:0,applies:0};
  const reply=(route:any,value:any,status=200)=>route.fulfill({status,contentType:"application/json",body:JSON.stringify(value)});
  await page.route("**/api/v1/alerts",route=>reply(route,[state.alert]));
  await page.route("**/api/v1/documents/"+docId,route=>reply(route,document));
  await page.route("**/api/v1/documents/"+docId+"/attempts",route=>reply(route,[]));
  await page.route("**/api/v1/alerts/"+alertId,route=>{state.gets++;return reply(route,state.alert);});
  state.task=(status:string,outcome:string|null=null,extra:any={})=>{
    state.alert={...state.alert,repair:{jobId,state:status,outcome,sourceVersion:0,error:null,attempts:0,canApply:outcome==="READY",blockingIssues:[],context:null,...extra}};
  };
  const fields=['supplier','issuedOn','total','currency','type','supplierTaxId','number','description','lines'];
  const blank=(v:any)=>v===null||v===undefined||v===''||Array.isArray(v)&&!v.length;
  const rows=(draft:any)=>draft.invoices||[draft];
  const key=(row:any,multi:boolean)=>multi?String(row.id):'root';
  const suggestions=(local:any)=>rows(local).flatMap((row:any,itemIndex:number)=>{
    const id=key(row,!!local.invoices),proposed=rows(state.alert.proposal?.draft||{}).find((candidate:any)=>key(candidate,!!local.invoices)===id)||{};
    const saved=rows(document.draft).find((candidate:any)=>key(candidate,!!local.invoices)===id)||{};
    return fields.filter(field=>!blank(proposed[field])&&JSON.stringify(row[field]??null)!==JSON.stringify(proposed[field])).map(field=>({key:id+':'+field,itemId:id,itemIndex,field,current:row[field]??null,proposed:proposed[field],protected:!!state.protected?.includes(field)||JSON.stringify(row[field]??null)!==JSON.stringify(saved[field]??null),kind:blank(row[field])&&!state.protected?.includes(field)?'COMPLEMENT':'CONFLICT'}));
  });
  state.result=(draft:any=valid(),outcome="READY",issues:string[]=[])=>{
    state.task("DONE",outcome,{canApply:outcome==="READY",blockingIssues:outcome==="REVIEW_REQUIRED"?["Falta a data da fatura"]:[],context:{model:"Modelo de teste",vision:true,textComplete:true,textCharacters:100,pages:[1]}});
    state.alert={...state.alert,proposal:{draft,issues,documentVersion:document.version,jobId,evidence:[{field:'supplier',quote:'Fornecedor IA no original'}]}};
    state.alert.proposal.changes=suggestions(document.draft);
  };
  await page.route("**/api/v1/alerts/"+alertId+"/ai",async route=>{
    state.posts++;
    if(state.submitted)await state.submitted();
    if(!state.alert.repair)state.task("QUEUED");
    return reply(route,state.alert,202);
  });
  state.preview=(body:any)=>{
    const draft=structuredClone(document.draft),local=structuredClone(body.draft);
    for(const row of rows(draft)) {
      const id=key(row,!!draft.invoices);if(!body.selectedItems.includes(id))continue;
      Object.assign(row,rows(local).find((candidate:any)=>key(candidate,!!draft.invoices)===id));
    }
    const changes=suggestions(draft);
    for(const change of changes)if(body.selectedItems.includes(change.itemId)&&(change.kind==='COMPLEMENT'||body.acceptedFields.includes(change.key))) {
      const row=rows(draft).find((r:any)=>key(r,!!draft.invoices)===change.itemId);row[change.field]=change.proposed;
    }
    const touched=JSON.stringify(draft)!==JSON.stringify(document.draft);
    const blockingIssues=rows(draft).filter((row:any)=>body.selectedItems.includes(key(row,!!draft.invoices))).flatMap((row:any)=>[!row.issuedOn?'Falta a data da fatura':null,!row.supplier?'Falta o fornecedor':null,row.total==null?'Falta um total válido':null]).filter(Boolean);
    return {changes,blockingIssues,canApplyDraft:touched,canValidate:touched&&!blockingIssues.length,draft};
  };
  await page.route("**/api/v1/alerts/"+alertId+"/preview",route=>{state.lastPreview=route.request().postDataJSON();return reply(route,state.preview(state.lastPreview));});
  await page.route("**/api/v1/alerts/"+alertId+"/apply",route=>{
    state.applies++;state.lastApply=route.request().postDataJSON();const result=state.preview(state.lastApply);
    document.draft=result.draft;document.version++;document.status=state.lastApply.validate?'DONE':'OPEN';
    state.alert.repair={...state.alert.repair,outcome:'STALE',canApply:false};return reply(route,document);
  });
  await page.goto("/");
  const open=async()=>{
    await page.getByRole("button",{name:/Alertas/,exact:false}).first().click();
    await page.locator("article").filter({hasText:state.alert.message}).getByRole("button",{name:"Consultar →",exact:true}).click();
    await expect(page.getByRole("dialog",{name:"Rever extração",exact:true})).toBeVisible();
  };
  await open();return {state,open,dialog:page.getByRole("dialog",{name:"Rever extração",exact:true})};
}

test("request shows a spinner immediately and the proposal arrives automatically without losing local edits",async({page},testInfo)=>{
  const {state,dialog}=await setup(page);let release!:()=>void;
  state.submitted=()=>new Promise<void>(resolve=>{release=resolve;});
  await dialog.getByRole("button",{name:"✧ Pedir tentativa de correção à IA",exact:true}).click();
  await expect(dialog.getByRole("button",{name:"A enviar pedido…",exact:true})).toBeDisabled();
  await expect(dialog.getByRole("progressbar",{name:"A enviar pedido",exact:true})).toBeVisible();release();
  await expect(dialog.locator(".repair-status")).toContainText("Em fila");state.task("RUNNING");
  await expect(dialog.getByRole("progressbar",{name:"A analisar",exact:true})).toBeVisible({timeout:7000});
  await dialog.getByLabel("Fornecedor",{exact:true}).fill("Fornecedor editado localmente");
  await page.screenshot({path:testInfo.outputPath("repair-processing.png"),fullPage:true});
  state.result();
  await expect(dialog.getByText("Proposta disponível",{exact:true})).toBeVisible({timeout:7000});
  await expect(dialog.getByRole("progressbar")).toHaveCount(0);
  await expect(dialog.getByLabel("Fornecedor",{exact:true})).toHaveValue("Fornecedor editado localmente");
  await expect(dialog.locator(".proposal")).toContainText("Fornecedor IA");
  expect(state.posts).toBe(1);expect(state.applies).toBe(0);
  await expect(dialog.getByRole("button",{name:"Aplicar proposta e validar",exact:true})).toBeEnabled();
  await page.screenshot({path:testInfo.outputPath("repair-result.png"),fullPage:true});
  await dialog.getByRole("button",{name:"Aplicar proposta e validar",exact:true}).click();
  await expect.poll(()=>state.applies).toBe(1);
});

test("closing stops polling and reopening restores the completed proposal",async({page})=>{
  const {state,open,dialog}=await setup(page);
  await dialog.getByRole("button",{name:"✧ Pedir tentativa de correção à IA",exact:true}).click();
  await expect(dialog.locator(".repair-status")).toContainText("Em fila");
  await dialog.getByRole("button",{name:"Fechar revisão",exact:true}).click();
  await page.waitForTimeout(300);const gets=state.gets;
  await page.waitForTimeout(2200);expect(state.gets).toBe(gets);
  state.result();await open();
  await expect(dialog.getByText("Proposta disponível",{exact:true})).toBeVisible();expect(state.posts).toBe(1);
});

test("waiting and failed tasks explain the problem and allow a new attempt",async({page})=>{
  const {state,dialog}=await setup(page);
  await dialog.getByRole("button",{name:"✧ Pedir tentativa de correção à IA",exact:true}).click();
  state.task("WAITING",null,{error:"Ollama indisponível"});
  await expect(dialog.locator(".repair-status")).toContainText("A aguardar modelo",{timeout:7000});
  await expect(dialog.locator(".repair-status")).toContainText("Ollama indisponível");
  state.task("FAILED",null,{error:"Resposta inválida após as tentativas"});
  await expect(dialog.locator(".repair-status")).toContainText("Falhou",{timeout:14000});
  await expect(dialog.getByRole("progressbar")).toHaveCount(0);
  await expect(dialog.getByRole("button",{name:"Pedir nova análise à IA",exact:true})).toBeEnabled();
});

test("an incomplete result allows draft complements and explains why validation is blocked",async({page})=>{
  const {state,dialog}=await setup(page);
  state.submitted=()=>{state.result({...valid(),issuedOn:null},"REVIEW_REQUIRED",["A data não é identificável"]);};
  await dialog.getByRole("button",{name:"✧ Pedir tentativa de correção à IA",exact:true}).click();
  await expect(dialog.getByText("Análise concluída · precisa de revisão",{exact:true})).toBeVisible();
  await expect(dialog.locator(".repair-issues")).toContainText("Falta a data da fatura");
  await expect(dialog.getByRole("button",{name:"Aplicar proposta e validar",exact:true})).toBeDisabled();
  await expect(dialog.getByRole("button",{name:"Aplicar ao rascunho",exact:true})).toBeEnabled();
  await expect(dialog.locator('#repair-validation-reason')).toContainText('Falta a data');
  expect(state.applies).toBe(0);
});

test("an unchanged result is explicit instead of looking stuck",async({page})=>{
  const {state,dialog}=await setup(page,valid());state.submitted=()=>{state.result(valid(),"NO_CHANGES");};
  await dialog.getByRole("button",{name:"✧ Pedir tentativa de correção à IA",exact:true}).click();
  await expect(dialog.getByText("Análise concluída · sem alterações",{exact:true})).toBeVisible();
  await expect(dialog.getByRole("button",{name:"Aplicar proposta e validar",exact:true})).toBeDisabled();
  await expect(dialog.getByRole("progressbar")).toHaveCount(0);
});

test("a stale result cannot be applied and a late response cannot reopen a closed dialog",async({page})=>{
  const {state,dialog}=await setup(page);state.submitted=()=>{state.result();state.alert.repair.outcome="STALE";state.alert.repair.canApply=false;};
  await dialog.getByRole("button",{name:"✧ Pedir tentativa de correção à IA",exact:true}).click();
  await expect(dialog.getByText("Proposta desatualizada",{exact:true})).toBeVisible();
  await expect(dialog.getByRole("button",{name:"Aplicar proposta e validar",exact:true})).toBeDisabled();
  let release!:()=>void;
  await page.route("**/api/v1/alerts/"+alertId,async route=>{await new Promise<void>(r=>{release=r;});await route.fulfill({contentType:"application/json",body:JSON.stringify({...state.alert,message:"Resposta antiga"})});});
  await dialog.getByRole("button",{name:"Atualizar proposta",exact:true}).click();
  await expect.poll(()=>!!release).toBe(true);
  await dialog.getByRole("button",{name:"Fechar revisão",exact:true}).click();release();
  await expect(dialog).not.toBeVisible();await expect(page.getByText("Resposta antiga",{exact:true})).toHaveCount(0);
});

test("opening another alert cannot be overwritten by an older document response",async({page})=>{
  const {state,dialog}=await setup(page);
  await dialog.getByRole("button",{name:"Fechar revisão",exact:true}).click();
  const otherId="e245b1ab-5314-4ad8-a91f-c75510592004",otherDoc="e245b1ab-5314-4ad8-a91f-c75510592005";
  const other={...state.alert,id:otherId,documentId:otherDoc,entityId:otherDoc,message:"Segundo documento para análise"};
  await page.route("**/api/v1/alerts",route=>route.fulfill({contentType:"application/json",body:JSON.stringify([state.alert,other])}));
  await page.route("**/api/v1/alerts/"+otherId,route=>route.fulfill({contentType:"application/json",body:JSON.stringify(other)}));
  await page.route("**/api/v1/documents/"+otherDoc,route=>route.fulfill({contentType:"application/json",body:JSON.stringify({id:otherDoc,version:0,filename:"Segundo-original.pdf",kind:"INVOICE",status:"OPEN",mediaType:"text/plain",draft:valid(),layout:{}})}));
  await page.route("**/api/v1/documents/"+otherDoc+"/attempts",route=>route.fulfill({contentType:"application/json",body:"[]"}));
  await page.getByRole("button",{name:"Atualizar dados",exact:true}).click();
  await expect(page.getByText(other.message,{exact:true})).toBeVisible();
  let release!:()=>void;
  await page.route("**/api/v1/documents/"+docId,async route=>{await new Promise<void>(r=>{release=r;});await route.fulfill({contentType:"application/json",body:JSON.stringify({id:docId,version:0,filename:"Resposta-antiga.pdf",kind:"INVOICE",status:"OPEN",mediaType:"text/plain",draft:valid(),layout:{}})});});
  await page.locator("article").filter({hasText:state.alert.message}).getByRole("button",{name:"Consultar →",exact:true}).click();
  await expect.poll(()=>!!release).toBe(true);
  await page.locator("article").filter({hasText:other.message}).getByRole("button",{name:"Consultar →",exact:true}).click();
  await expect(dialog.getByRole("heading",{name:"Segundo-original.pdf",exact:true})).toBeVisible();release();
  await expect(dialog.getByRole("heading",{name:"Segundo-original.pdf",exact:true})).toBeVisible();
  await expect(dialog.getByText("Resposta-antiga.pdf",{exact:true})).toHaveCount(0);
});

test("the processing indicator and result remain usable on a small screen",async({page},testInfo)=>{
  await page.setViewportSize({width:390,height:844});
  const {state,dialog}=await setup(page);state.submitted=()=>{state.task("RUNNING");};
  await dialog.getByRole("button",{name:"✧ Pedir tentativa de correção à IA",exact:true}).click();
  await expect(dialog.getByRole("progressbar",{name:"A analisar",exact:true})).toBeVisible();
  await page.screenshot({path:testInfo.outputPath("repair-mobile-processing.png"),fullPage:true});state.result();
  await expect(dialog.getByText("Proposta disponível",{exact:true})).toBeVisible({timeout:7000});
  const apply=dialog.getByRole("button",{name:"Aplicar proposta e validar",exact:true});await apply.scrollIntoViewIfNeeded();await expect(apply).toBeEnabled();
  const dimensions=await dialog.evaluate(element=>({width:element.clientWidth,scroll:element.scrollWidth}));expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.width+1);
  await page.screenshot({path:testInfo.outputPath("repair-mobile-result.png"),fullPage:true});
});


test("human conflicts default to keeping the value and can be accepted or reversed per field",async({page})=>{
  const {state,dialog}=await setup(page,{...valid(),supplier:'Nome humano',number:'NÚMERO-HUMANO'});state.protected=['supplier','number'];
  state.submitted=()=>state.result({...valid(),number:'NÚMERO-IA'});
  await dialog.getByRole('button',{name:'✧ Pedir tentativa de correção à IA',exact:true}).click();
  const supplier=dialog.locator('.proposal-change').filter({hasText:'Fornecedor · Sugestão de alteração'});
  await expect(supplier).toContainText('protegido');await expect(supplier).toContainText('Fornecedor IA no original');
  await expect(supplier.getByRole('button',{name:'Manter o meu valor',exact:true})).toHaveAttribute('aria-pressed','true');
  await expect(dialog.getByRole('button',{name:'Aplicar ao rascunho',exact:true})).toBeDisabled();
  await supplier.getByRole('button',{name:'Aceitar valor da IA',exact:true}).click();
  await expect(dialog.getByRole('button',{name:'Aplicar ao rascunho',exact:true})).toBeEnabled();
  await supplier.getByRole('button',{name:'Manter o meu valor',exact:true}).click();
  await expect(dialog.getByRole('button',{name:'Aplicar ao rascunho',exact:true})).toBeDisabled();
  await supplier.getByRole('button',{name:'Aceitar valor da IA',exact:true}).click();
  await dialog.getByRole('button',{name:'Aplicar ao rascunho',exact:true}).click();
  await expect.poll(()=>state.applies).toBe(1);expect(state.lastApply.acceptedFields).toEqual(['root:supplier']);expect(state.lastApply.validate).toBe(false);
  await expect(dialog.getByLabel('Fornecedor',{exact:true})).toHaveValue('Fornecedor IA');
  await expect(dialog.getByLabel('Número da fatura',{exact:true})).toHaveValue('NÚMERO-HUMANO');
});

test("applying safe additions preserves a supplier typed while the model was running",async({page})=>{
  const {state,dialog}=await setup(page);state.submitted=()=>{state.task('RUNNING');};
  await dialog.getByRole('button',{name:'✧ Pedir tentativa de correção à IA',exact:true}).click();
  await dialog.getByLabel('Fornecedor',{exact:true}).fill('Fornecedor escrito por mim');state.result();
  await expect(dialog.getByRole('button',{name:'Aplicar proposta e validar',exact:true})).toBeEnabled({timeout:7000});
  await dialog.getByRole('button',{name:'Aplicar proposta e validar',exact:true}).click();
  await expect.poll(()=>state.applies).toBe(1);expect(state.lastApply.acceptedFields).toEqual([]);
  expect(state.lastApply.draft.supplier).toBe('Fornecedor escrito por mim');
  await expect(dialog.getByLabel('Fornecedor',{exact:true})).toHaveValue('Fornecedor escrito por mim');
});

test("incomplete draft additions are actionable on mobile with the keyboard",async({page},testInfo)=>{
  await page.setViewportSize({width:390,height:844});
  const {state,dialog}=await setup(page);state.submitted=()=>state.result({...valid(),issuedOn:null},'REVIEW_REQUIRED');
  await dialog.getByRole('button',{name:'✧ Pedir tentativa de correção à IA',exact:true}).click();
  const save=dialog.getByRole('button',{name:'Aplicar ao rascunho',exact:true});await expect(save).toBeEnabled();
  await save.scrollIntoViewIfNeeded();await page.screenshot({path:testInfo.outputPath('repair-complements-mobile.png'),fullPage:true});
  await save.focus();await page.keyboard.press('Enter');await expect.poll(()=>state.applies).toBe(1);
  expect(state.lastApply.validate).toBe(false);await expect(dialog.getByLabel('Fornecedor',{exact:true})).toHaveValue('Fornecedor IA');
  await expect(dialog.getByLabel('Data de emissão',{exact:true})).toHaveValue('');
});


test("a multi-invoice proposal only validates the checked item and preserves edits on its sibling",async({page})=>{
  const first='5d833fd3-5338-4348-af45-721088000001',second='5d833fd3-5338-4348-af45-721088000002';
  const initial={invoices:[{...valid(),id:first,supplier:null,issuedOn:null},{...valid(),id:second,supplier:null,issuedOn:null}]};
  const {state,dialog}=await setup(page,initial);
  state.submitted=()=>state.result({invoices:[{...valid(),id:first},{...valid(),id:second}]});
  await dialog.getByRole('button',{name:'✧ Pedir tentativa de correção à IA',exact:true}).click();
  await expect(dialog.getByRole('checkbox',{name:'Comprovativo 2',exact:true})).toBeChecked();
  await dialog.getByLabel('Fornecedor',{exact:true}).nth(1).fill('Edição local do segundo comprovativo');
  await dialog.getByRole('checkbox',{name:'Comprovativo 2',exact:true}).uncheck();
  await expect(dialog.getByRole('button',{name:'Aplicar proposta e validar',exact:true})).toBeEnabled();
  await dialog.getByRole('button',{name:'Aplicar proposta e validar',exact:true}).click();
  await expect.poll(()=>state.applies).toBe(1);expect(state.lastApply.selectedItems).toEqual([first]);
  await expect(dialog.getByLabel('Fornecedor',{exact:true}).nth(0)).toHaveValue('Fornecedor IA');
  await expect(dialog.getByLabel('Fornecedor',{exact:true}).nth(1)).toHaveValue('Edição local do segundo comprovativo');
});
