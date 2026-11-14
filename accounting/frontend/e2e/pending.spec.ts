import { expect, test, Page } from "@playwright/test";

let created:{resource:string;id:string}[]=[];
async function get(page:Page,path:string){const r=await page.request.get('/api/v1'+path);expect(r.ok(),await r.text()).toBeTruthy();return r.json();}
async function write(page:Page,path:string,body:any={},method='POST'){
  const csrf=await get(page,'/csrf');const r=await page.request.fetch('/api/v1'+path,{method,data:body,headers:{'X-XSRF-TOKEN':csrf.token}});
  const text=await r.text();expect(r.ok(),text).toBeTruthy();return text?JSON.parse(text):null;
}
async function create(page:Page,resource:string,body:any){const row=await write(page,'/'+resource,body);created.push({resource,id:row.id});return row;}
async function open(page:Page,view:string,year:number){await page.goto('/');if(view)await page.getByRole('button',{name:view,exact:true}).click();await page.getByLabel('Ano',{exact:true}).fill(String(year));await page.getByLabel('Ano',{exact:true}).press('Tab');}
function pdf(){
  const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << >> >>'];
  let text='%PDF-1.4\n';const offsets=[0];objects.forEach((object,n)=>{offsets.push(Buffer.byteLength(text));text+=`${n+1} 0 obj\n${object}\nendobj\n`;});
  const start=Buffer.byteLength(text);text+='xref\n0 4\n0000000000 65535 f \n'+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('');
  text+=`trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`;return Buffer.from(text);
}
async function upload(page:Page,kind:string,name:string,accountId?:string){
  const csrf=await get(page,'/csrf');const r=await page.request.post('/api/v1/documents',{multipart:{files:{name,mimeType:'application/pdf',buffer:pdf()},kind,...(accountId?{accountId}:{})},headers:{'X-XSRF-TOKEN':csrf.token}});
  expect(r.ok(),await r.text()).toBeTruthy();return (await r.json())[0];
}
async function source(page:Page,kind:'INVOICE'|'STATEMENT',name:string){
  const account=kind==='STATEMENT'?await write(page,'/accounts',{name:'E2E pending account'}):null;
  const document=await upload(page,kind,name,account?.id);
  const invoice=(number:string,total:string)=>({supplier:number,number,issuedOn:'2016-01-01',total,currency:'EUR',type:'INVOICE',lines:[],categories:[],custom:'keep-'+number});
  const movement=(description:string,amount:string)=>({description,bookedOn:'2016-01-01',amount,direction:'DEBIT',custom:'keep-'+description});
  const draft=kind==='INVOICE'?{invoices:[invoice('E2E-SOURCE-I-A','100.00'),invoice('E2E-SOURCE-I-B','50.00')],reference:'preserve-source'}
    :{movements:[movement('E2E-SOURCE-M-A','100.00'),movement('E2E-SOURCE-M-B','50.00')],reference:'preserve-source'};
  await write(page,'/documents/'+document.id+'/draft',{version:document.version,draft,resolve:true},'PUT');
  const resource=kind==='INVOICE'?'invoices':'movements',records=(await get(page,'/documents/'+document.id+'/records'))[resource];
  for(const row of records){created.push({resource,id:row.id});const current=await get(page,`/${resource}/${row.id}`);await write(page,`/${resource}/${row.id}`,{...current,[kind==='INVOICE'?'issuedOn':'bookedOn']:null,validate:false},'PUT');}
  return {document,resource,records};
}
test.beforeEach(async({page})=>{
  created=[];const csrf=await get(page,'/csrf');const r=await page.request.post('/api/v1/session',{form:{username:'gestor',password:'conta-e2e-dev'},headers:{'X-XSRF-TOKEN':csrf.token}});expect(r.ok()).toBeTruthy();
});
test.afterEach(async({page})=>{for(const row of created){const current=await get(page,`/${row.resource}/${row.id}`);const csrf=await get(page,'/csrf');const response=await page.request.delete(`/api/v1/${row.resource}/${row.id}?version=${current.version}`,{headers:{'X-XSRF-TOKEN':csrf.token}});expect(response.ok(),await response.text()).toBeTruthy();}});

test('undated pending invoices are visible in both lists and validate without losing the selected filters',async({page},testInfo)=>{
  const row=await create(page,'invoices',{supplier:'E2E-UNDATED-INVOICE',number:'E2E-PENDING-I',total:'100.00',issuedOn:null,validate:false});
  await open(page,'',2070);let entry=page.locator('tr.reconciliation-entry').filter({hasText:'E2E-UNDATED-INVOICE'});
  await expect(entry).toHaveCount(1);await expect(entry).toContainText('Por validar');await expect(entry.locator('.reconciliation-impact')).toContainText('—');
  await page.screenshot({path:testInfo.outputPath('pending-reconciliation.png'),fullPage:true});
  await expect(entry).toContainText('100,00 €');await entry.getByRole('button',{name:'Rever e validar',exact:true}).click();
  let dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await expect(dialog).toContainText('fora dos cálculos');await dialog.getByRole('button',{name:'Fechar editor',exact:true}).click();
  await page.getByRole('button',{name:'▤ Faturas',exact:true}).click();const invoice=page.locator('tr').filter({hasText:'E2E-UNDATED-INVOICE'});
  await expect(invoice).toContainText('Provisório');await invoice.getByRole('button',{name:'Rever e validar',exact:true}).click();
  dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await dialog.getByLabel('Data de emissão',{exact:true}).fill('2070-01-01');
  await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  expect((await get(page,'/invoices/'+row.id)).reviewStatus).toBe('CORRECTED');await expect(page.getByLabel('Ano',{exact:true})).toHaveValue('2070');
  await expect(invoice.getByRole('button',{name:'Rever e validar',exact:true})).toHaveCount(0);
  await page.screenshot({path:testInfo.outputPath('pending-invoice-validated.png'),fullPage:true});
});

test('source filename identifies an invoice with missing fields and opens its original for review',async({page})=>{
  const doc=await upload(page,'INVOICE','E2E-Incomplete-original.pdf');
  await create(page,'invoices',{documentId:doc.id,supplier:null,total:null,issuedOn:null,validate:false});
  await open(page,'▤ Faturas',2071);await page.getByLabel('Pesquisar',{exact:true}).fill('E2E-Incomplete-original.pdf');await page.getByRole('button',{name:'Pesquisar',exact:true}).click();
  const row=page.locator('tr').filter({hasText:'E2E-Incomplete-original.pdf'});await expect(row).toContainText('Por validar');
  await row.getByRole('button',{name:'Rever e validar',exact:true}).click();const dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});
  await expect(dialog.locator('source-pdf')).toBeVisible();await expect(dialog.locator('source-pdf canvas')).toBeVisible({timeout:15000});
  await expect(dialog).toContainText('Falta a data');await expect(dialog).toContainText('Falta o fornecedor');await expect(dialog).toContainText('apenas a este item');
  await dialog.getByLabel('Fornecedor',{exact:true}).fill('Fornecedor corrigido');await dialog.getByLabel('Data de emissão',{exact:true}).fill('2016-01-01');await dialog.getByLabel('Total com IVA',{exact:true}).fill('56.64');
  await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('status')).toContainText('Registo validado. Pertence a outro período e fica fora dos filtros atuais.');
  await expect(page.getByLabel('Ano',{exact:true})).toHaveValue('2071');
});

test('pending movements show missing amount and direction without inventing a credit',async({page})=>{
  const account=await write(page,'/accounts',{name:'E2E pending movement'});
  const row=await create(page,'movements',{accountId:account.id,bookedOn:null,description:'E2E-UNDATED-MOVEMENT',amount:null,direction:null,validate:false});
  await open(page,'↗ Movimentos',2072);const movement=page.locator('tr').filter({hasText:'E2E-UNDATED-MOVEMENT'});
  await expect(movement).toContainText('Por validar');await expect(movement).toContainText('Direção em falta');await expect(movement).not.toContainText('0,00 €');
  await page.getByRole('button',{name:'⇄ Reconciliação',exact:true}).click();const entry=page.locator('tr.reconciliation-entry').filter({hasText:'E2E-UNDATED-MOVEMENT'});
  await expect(entry).toContainText('Direção em falta');await expect(entry).not.toContainText('0,00 €');await entry.getByRole('button',{name:'Rever e validar',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await dialog.getByLabel('Data do movimento',{exact:true}).fill('2072-01-01');await dialog.getByLabel('Montante positivo',{exact:true}).fill('60.00');await dialog.getByLabel('Direção',{exact:true}).selectOption('DEBIT');
  await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  expect((await get(page,'/movements/'+row.id)).reviewStatus).toBe('CORRECTED');await expect(entry.locator('.reconciliation-impact')).toContainText('-60,00 €');
});

test('validating a receipt preserves the other receipt and source metadata',async({page})=>{
  const {document,records}=await source(page,'INVOICE','E2E-Multiple-receipts.pdf');
  const before=await get(page,'/documents/'+document.id);await open(page,'▤ Faturas',2073);
  const row=page.locator('tr').filter({hasText:'E2E-SOURCE-I-A'});await row.getByRole('button',{name:'Rever e validar',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await dialog.getByLabel('Data de emissão',{exact:true}).fill('2073-01-01');await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  const after=await get(page,'/documents/'+document.id);expect(after.status).toBe('OPEN');expect(after.draft.reference).toBe('preserve-source');expect(after.draft.invoices[1]).toEqual(before.draft.invoices[1]);
  expect((await get(page,'/invoices/'+records[1].id)).reviewStatus).toBe('OPEN');
  const other=await get(page,'/invoices/'+records[1].id);await write(page,'/invoices/'+other.id,{...other,supplier:null,validate:false},'PUT');
  expect((await get(page,'/invoices/'+records[0].id)).reviewStatus).toBe('CORRECTED');
});

test('validating one bank row leaves its sibling pending and completes the source after the last row',async({page})=>{
  const {document,records}=await source(page,'STATEMENT','E2E-Multiple-movements.pdf');
  const before=await get(page,'/documents/'+document.id);await open(page,'↗ Movimentos',2074);
  const row=page.locator('tr').filter({hasText:'E2E-SOURCE-M-A'});await row.getByRole('button',{name:'Rever e validar',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await dialog.getByLabel('Data do movimento',{exact:true}).fill('2074-01-01');await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  const after=await get(page,'/documents/'+document.id);expect(after.status).toBe('OPEN');expect(after.draft.movements[1]).toEqual(before.draft.movements[1]);
  const other=await get(page,'/movements/'+records[1].id);expect(other.reviewStatus).toBe('OPEN');await write(page,'/movements/'+other.id,{...other,bookedOn:'2074-01-02',validate:true},'PUT');
  const finished=await get(page,'/documents/'+document.id);expect(finished.status).toBe('DONE');expect(finished.draft.movements.map((m:any)=>m.id)).toEqual(records.map((r:any)=>r.id));
});

test('pending and excluded markers remain independent after validation',async({page})=>{
  const row=await create(page,'invoices',{supplier:'E2E-PENDING-EXCLUDED',total:'100.00',issuedOn:null,validate:false});
  await write(page,'/reconciliation/exclusions',{excluded:true,items:[{type:'invoice',id:row.id,version:row.version}]});
  await open(page,'',2075);const entry=page.locator('tr.reconciliation-entry').filter({hasText:'E2E-PENDING-EXCLUDED'});await expect(entry).toContainText('Por validar');await expect(entry).toContainText('Excluído do saldo');
  await entry.getByRole('button',{name:'Rever e validar',exact:true}).click();const dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await dialog.getByLabel('Data de emissão',{exact:true}).fill('2075-01-01');await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  const updated=await get(page,'/invoices/'+row.id);expect(updated.excluded).toBe(true);expect(updated.reviewStatus).toBe('CORRECTED');await expect(entry.locator('.reconciliation-impact')).toContainText('0,00 €');
});

test('pending review is accessible on a small screen and keyboard users can save a draft',async({page},testInfo)=>{
  await page.setViewportSize({width:390,height:844});const row=await create(page,'invoices',{supplier:'E2E-PENDING-MOBILE',total:'75.00',issuedOn:null,validate:false});
  await open(page,'▤ Faturas',2076);const item=page.locator('tr').filter({hasText:'E2E-PENDING-MOBILE'}),button=item.getByRole('button',{name:'Rever e validar',exact:true});await button.focus();await page.keyboard.press('Enter');
  const dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await expect(dialog).toContainText('fora dos cálculos');await dialog.getByLabel('Número da fatura',{exact:true}).fill('MOBILE-DRAFT');
  await page.screenshot({path:testInfo.outputPath('pending-mobile-review.png'),fullPage:true});
  await dialog.getByRole('button',{name:'Guardar rascunho',exact:true}).click();await expect(dialog).not.toBeVisible();expect((await get(page,'/invoices/'+row.id)).reviewStatus).toBe('OPEN');await expect(item).toContainText('Por validar');
});
