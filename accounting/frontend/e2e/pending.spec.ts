import { expect, test, Page } from "@playwright/test";

let created:{resource:string;id:string}[]=[];
async function get(page:Page,path:string){const r=await page.request.get('/api/v1'+path);expect(r.ok(),await r.text()).toBeTruthy();return r.json();}
async function write(page:Page,path:string,body:any={},method='POST'){
  const csrf=await get(page,'/csrf');const r=await page.request.fetch('/api/v1'+path,{method,data:body,headers:{'X-XSRF-TOKEN':csrf.token}});
  const text=await r.text();expect(r.ok(),text).toBeTruthy();return text?JSON.parse(text):null;
}
async function create(page:Page,resource:string,body:any){const row=await write(page,'/'+resource,body);created.push({resource,id:row.id});return row;}
async function setYear(page: Page, year: number) {
  const field = page.getByLabel('Ano', { exact: true });
  await field.fill(String(year));
  await field.press('Tab');
  const filters = page.getByRole('group', { name: 'Filtros aplicados' });
  await expect(filters).toContainText(`Ano: ${year}`);
}
async function openSection(page: Page, name: string) {
  const section = page.getByRole('navigation').getByRole('button', { name, exact: true });
  await section.click();
  await expect(section).toHaveAttribute('aria-current', 'page');
}
async function open(page:Page,view:string,year:number){await page.goto('/');if(view)await openSection(page,view);await setYear(page,year);}
function pdf(name: string){
  const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << >> >>'];
  let text=`%PDF-1.4\n% ${name}\n`;const offsets=[0];objects.forEach((object,n)=>{offsets.push(Buffer.byteLength(text));text+=`${n+1} 0 obj\n${object}\nendobj\n`;});
  const start=Buffer.byteLength(text);text+='xref\n0 4\n0000000000 65535 f \n'+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('');
  text+=`trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`;return Buffer.from(text);
}
async function upload(page:Page,kind:string,name:string,accountId?:string){
  const csrf=await get(page,'/csrf');const r=await page.request.post('/api/v1/documents',{multipart:{files:{name,mimeType:'application/pdf',buffer:pdf(name)},kind,...(accountId?{accountId}:{})},headers:{'X-XSRF-TOKEN':csrf.token}});
  expect(r.ok(),await r.text()).toBeTruthy();return (await r.json())[0];
}
async function source(page:Page,kind:'INVOICE'|'STATEMENT',name:string){
  const account=kind==='STATEMENT'?await write(page,'/accounts',{name:'E2E pending account'}):null;
  const document=await upload(page,kind,name,account?.id);
  const invoice=(number:string,total:string)=>({supplier:number,number,issuedOn:null,total,currency:'EUR',type:'INVOICE',lines:[],custom:'keep-'+number});
  const movement=(description:string,amount:string)=>({description,bookedOn:null,amount,direction:'DEBIT',accountId:account.id,custom:'keep-'+description});
  const resource = kind === 'INVOICE' ? 'invoices' : 'movements';
  const items = kind === 'INVOICE'
    ? [invoice('E2E-SOURCE-I-A','100.00'), invoice('E2E-SOURCE-I-B','50.00')]
    : [movement('E2E-SOURCE-M-A','100.00'), movement('E2E-SOURCE-M-B','50.00')];
  const records = [];
  for (const item of items) records.push(await create(page, resource, {...item, validate: false}));
  const draft = {reference:'preserve-source', [resource]: items.map((item, index) => ({...item, id: records[index].id}))};
  await write(page, '/documents/' + document.id + '/draft', {version: document.version, draft, resolve: false}, 'PUT');
  for (const row of records)
    await write(page, `/${resource}/${row.id}`, {...row, documentId: document.id, validate: false}, 'PUT');
  return {document,resource,records};
}
test.beforeEach(async({page})=>{
  created=[];const csrf=await get(page,'/csrf');const r=await page.request.post('/api/v1/session',{form:{username:'gestor',password:'conta-e2e-dev'},headers:{'X-XSRF-TOKEN':csrf.token}});expect(r.ok()).toBeTruthy();
});
test.afterEach(async ({page}) => {
  for (const row of created) {
    const path = `/api/v1/${row.resource}/${row.id}`;
    const preview = await page.request.get(`${path}/deletion-impact`);
    if (preview.status() === 404) continue;
    expect(preview.ok(), await preview.text()).toBeTruthy();
    const { record, impactToken } = await preview.json();
    const csrf = await get(page, '/csrf');
    const response = await page.request.delete(path, {
      params: { version: record.version, impactToken }, headers: { 'X-XSRF-TOKEN': csrf.token },
    });
    expect(response.ok(), await response.text()).toBeTruthy();
  }
});

test('deleting a pending invoice erases its exclusive source and alert after confirmation', async ({page}) => {
  const filename = 'mistaken-invoice-deletion.pdf';
  const document = await upload(page, 'INVOICE', filename);
  const invoice = await create(page, 'invoices', {
    supplier: 'Fornecedor importado por engano', number: 'E2E-DELETE-PENDING',
    issuedOn: '2076-01-01', validate: false,
  });
  const path = `/invoices/${invoice.id}`;
  await write(page, `/documents/${document.id}/draft`, { version: document.version, draft: invoice }, 'PUT');
  await write(page, path, { ...invoice, documentId: document.id, validate: false }, 'PUT');
  const alerts = await get(page, '/alerts');
  const recordAlert = alerts.find((alert: { entityId: string }) => alert.entityId === invoice.id);

  await page.goto('/alert');
  const alertRow = page.getByRole('article').filter({hasText: recordAlert.message, has: page.getByText(filename, {exact: false})});
  await expect(alertRow).toBeVisible();
  await page.goto(`/invoice/${invoice.id}/edit`);
  const editor = page.getByRole('dialog', {name: 'Editar registo'});
  await editor.getByRole('button', {name: 'Eliminar', exact: true}).click();
  const warning = page.getByRole('alertdialog', {name: 'Eliminar esta fatura?'});
  await expect(warning).toContainText(filename);
  await expect(warning).toContainText('serão eliminados permanentemente');
  await expect(warning).toContainText(recordAlert.message);
  await warning.getByRole('button', {name: 'Cancelar'}).press('Escape');
  await expect(warning).toHaveCount(0);
  await expect(editor).toBeVisible();
  await expect(await page.request.get(`/api/v1${path}`)).toBeOK();

  await editor.getByRole('button', {name: 'Eliminar', exact: true}).click();
  await warning.getByRole('button', {name: 'Eliminar', exact: true}).click();
  await expect(warning).toHaveCount(0);
  await expect(editor).toHaveCount(0);
  await openSection(page, '▧ Extrações');
  await expect(page.getByText(filename, {exact: false})).toHaveCount(0);
  expect((await page.request.get(`/api/v1/documents/${document.id}/original`)).status()).toBe(404);
  expect((await page.request.get(`/api/v1/alerts/${recordAlert.id}`)).status()).toBe(404);
  expect((await page.request.get(`/api/v1${path}`)).status()).toBe(404);
  await page.goto('/alert');
  await expect(page.getByRole('heading', {name: /Lista de alertas/})).toBeVisible();
  await expect(alertRow).toHaveCount(0);
});

test('movement deletion links to invoices and asks again after the reconciliation changes', async ({page}) => {
  const account = await write(page, '/accounts', {name: 'Conta de eliminação'});
  const invoice = await create(page, 'invoices', {
    supplier: 'Fornecedor de teste', number: 'E2E-DELETE-PAID', issuedOn: '2078-01-01', total: '100.00', validate: true,
  });
  const movement = await create(page, 'movements', {
    accountId: account.id, description: 'Transferência parcial', bookedOn: '2078-01-02', amount: '40.00',
    direction: 'DEBIT', validate: true,
  });
  await open(page, '↗ Movimentos', 2078);
  const row = page.getByRole('row').filter({hasText: 'Transferência parcial'});
  await row.getByRole('button', {name: 'Eliminar movimento'}).click();
  const warning = page.getByRole('alertdialog', {name: 'Eliminar este movimento?'});
  await expect(warning).toContainText('40,00');
  await write(page, '/allocations', {invoiceId: invoice.id, movementId: movement.id, amount: '40.00'});
  await warning.getByRole('button', {name: 'Eliminar', exact: true}).click();
  await expect(warning).toContainText('Revê os dados atualizados e confirma novamente');
  const linked = warning.getByRole('link', {name: /E2E-DELETE-PAID/});
  await expect(linked).toHaveAttribute('target', '_blank');
  const opened = page.waitForEvent('popup');
  await linked.click();
  const detail = await opened;
  await expect(detail).toHaveURL(`/invoice/${invoice.id}/edit`);
  await detail.close();
  await expect(warning).toBeVisible();
  await warning.getByRole('button', {name: 'Eliminar', exact: true}).click();
  await expect(row).toHaveCount(0);
  expect(await get(page, `/invoices/${invoice.id}`)).toMatchObject({remaining: '100.00'});
  const activeAllocations = await get(page, '/allocations');
  expect(activeAllocations).not.toContainEqual(expect.objectContaining({movementId: movement.id}));
  await openSection(page, '⇄ Reconciliação');
  await setYear(page, 2078);
  const reconciliation = page.locator('reconciliation-table');
  await expect(reconciliation).toContainText('E2E-DELETE-PAID');
  await expect(reconciliation).not.toContainText('Transferência parcial');
});

  test('manual invoices edit and save only the invoice date', async ({page}) => {
    await open(page, '▤ Faturas', 2077);
    await page.getByRole('button', {name: '+ Nova fatura', exact: true}).click();
    const dialog = page.getByRole('dialog', {name: 'Editar registo', exact: true});
    const firstDate = dialog.getByLabel('Data de emissão', {exact: true});
    await expect(dialog.getByLabel('Vencimento', {exact: true})).toHaveCount(0);
    await dialog.getByLabel('Fornecedor', {exact: true}).fill('E2E-INVOICE-DATE');
    await firstDate.focus();
    await firstDate.press('ArrowLeft');
    await firstDate.press('ArrowLeft');
    await firstDate.pressSequentially('0129');
    await firstDate.pressSequentially('2016');
    await expect(firstDate).toHaveValue('2016-01-29');
    await dialog.getByLabel('Fornecedor', {exact: true}).click();
    await expect(firstDate).toHaveValue('2016-01-29');
    await firstDate.fill('2077-02-15');
    await expect(firstDate).toHaveValue('2077-02-15');
    await firstDate.clear();
    await firstDate.blur();
    await expect(firstDate).toHaveValue('');
    await firstDate.fill('2077-02-17');
    await firstDate.blur();
    const saved = page.waitForResponse(response =>
      response.url().endsWith('/api/v1/invoices') && response.request().method() === 'POST');
    await dialog.getByRole('button', {name: 'Guardar rascunho', exact: true}).click();
    const response = await saved;
    expect(response.ok(), await response.text()).toBeTruthy();
    const row = await response.json();
    created.push({resource: 'invoices', id: row.id});
    await expect(dialog).not.toBeVisible();
    await expect(page.locator('mat-snack-bar-container')).toContainText('Guardar rascunho: operação concluída com sucesso.');
    expect(await get(page, '/invoices/' + row.id)).toMatchObject({
      issuedOn: '2077-02-17', reviewStatus: 'OPEN',
    });
    expect(row).not.toHaveProperty('dueOn');
  });

test('undated pending invoices are visible in both lists and validate without losing the selected filters',async({page},testInfo)=>{
  const row=await create(page,'invoices',{supplier:'E2E-UNDATED-INVOICE',number:'E2E-PENDING-I',total:'100.00',issuedOn:null,validate:false});
  await open(page,'',2070);let entry=page.locator('tr.reconciliation-entry').filter({hasText:'E2E-UNDATED-INVOICE'});
  await expect(entry).toHaveCount(1);await expect(entry).toContainText('Por validar');await expect(entry.locator('.reconciliation-impact')).toContainText('—');
  await page.screenshot({path:testInfo.outputPath('pending-reconciliation.png'),fullPage:true});
  await expect(entry).toContainText('100,00 €');await entry.getByRole('button',{name:'Rever e validar',exact:true}).click();
  let dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await expect(dialog).toContainText('fora dos cálculos');
  await expect(dialog.getByRole('region', {name: 'Correção por IA', exact: true})).toHaveCount(0);
  await dialog.getByRole('button',{name:'Fechar editor',exact:true}).click();
  await openSection(page,'▤ Faturas');await setYear(page,2070);const invoice=page.locator('tr').filter({hasText:'E2E-UNDATED-INVOICE'});
  await expect(invoice).toContainText('Provisório');await invoice.getByRole('button',{name:'Rever e validar',exact:true}).click();
  dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await dialog.getByLabel('Data de emissão',{exact:true}).fill('2070-01-01');
  await dialog.getByLabel('Data de emissão',{exact:true}).blur();
  await expect(dialog.getByLabel('Vencimento',{exact:true})).toHaveCount(0);
  await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  const saved=await get(page,'/invoices/'+row.id);expect(saved).toMatchObject({reviewStatus:'CORRECTED',issuedOn:'2070-01-01'});expect(saved).not.toHaveProperty('dueOn');await expect(page.getByLabel('Ano',{exact:true})).toHaveValue('2070');
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
  await expect(dialog.getByRole('button', {name: '✧ Pedir tentativa de correção à IA', exact: true})).toBeVisible();
  await expect(dialog).toContainText('Falta a data');await expect(dialog).toContainText('Falta o fornecedor');await expect(dialog).toContainText('apenas a este item');
  await dialog.getByLabel('Fornecedor',{exact:true}).fill('Fornecedor corrigido');await dialog.getByLabel('Data de emissão',{exact:true}).fill('2016-01-01');await dialog.getByLabel('Total com IVA',{exact:true}).fill('56.64');
  await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  await expect(page.locator('mat-snack-bar-container')).toContainText('Validar e guardar: operação concluída com sucesso. Registo validado. Pertence a outro período e fica fora dos filtros atuais.');
  await expect(page.getByLabel('Ano',{exact:true})).toHaveValue('2071');
});

test('pending movements show missing amount and direction without inventing a credit',async({page})=>{
  const account=await write(page,'/accounts',{name:'E2E pending movement'});
  const row=await create(page,'movements',{accountId:account.id,bookedOn:null,description:'E2E-UNDATED-MOVEMENT',amount:null,direction:null,validate:false});
  await open(page,'↗ Movimentos',2072);const movement=page.locator('tr').filter({hasText:'E2E-UNDATED-MOVEMENT'});
  await expect(movement).toContainText('Por validar');await expect(movement).toContainText('Direção em falta');await expect(movement).not.toContainText('0,00 €');
  await openSection(page,'⇄ Reconciliação');await setYear(page,2072);const entry=page.locator('tr.reconciliation-entry').filter({hasText:'E2E-UNDATED-MOVEMENT'});
  await expect(entry).toContainText('Direção em falta');await expect(entry).not.toContainText('0,00 €');await entry.getByRole('button',{name:'Rever e validar',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await dialog.getByLabel('Data do movimento',{exact:true}).fill('2072-01-01');await dialog.getByLabel('Montante positivo',{exact:true}).fill('60.00');await dialog.getByLabel('Direção',{exact:true}).selectOption('DEBIT');
  await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  await expect(page.locator('mat-snack-bar-container')).toContainText('Validar e guardar: operação concluída com sucesso.');
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

test('validating bank rows independently leaves the source review open until explicitly resolved',async({page})=>{
  const {document,records}=await source(page,'STATEMENT','E2E-Multiple-movements.pdf');
  const before=await get(page,'/documents/'+document.id);await open(page,'↗ Movimentos',2074);
  const row=page.locator('tr').filter({hasText:'E2E-SOURCE-M-A'});await row.getByRole('button',{name:'Rever e validar',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Editar registo',exact:true});await dialog.getByLabel('Data do movimento',{exact:true}).fill('2074-01-01');await dialog.getByRole('button',{name:'Validar e guardar',exact:true}).click();await expect(dialog).not.toBeVisible();
  const after=await get(page,'/documents/'+document.id);expect(after.status).toBe('OPEN');expect(after.draft.movements[1]).toEqual(before.draft.movements[1]);
  const other=await get(page,'/movements/'+records[1].id);expect(other.reviewStatus).toBe('OPEN');await write(page,'/movements/'+other.id,{...other,bookedOn:'2074-01-02',validate:true},'PUT');
  const pending=await get(page,'/documents/'+document.id);
  expect(pending.status).toBe('OPEN');
  const finished = await write(page, '/documents/' + document.id + '/draft',
    {version: pending.version, draft: pending.draft, resolve: true}, 'PUT');
  expect(finished.status).toBe('DONE');
  expect(finished.draft.movements.map((m:any)=>m.id)).toEqual(records.map((r:any)=>r.id));
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
  await expect(page.locator('mat-snack-bar-container')).toContainText('Guardar rascunho: operação concluída com sucesso.');
});
