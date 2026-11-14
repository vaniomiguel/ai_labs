import { expect, test, Page } from "@playwright/test";

async function write(page: Page, path: string, body: unknown, method = "POST") {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  const response = await page.request.fetch("/api/v1"+path, { method, data: body, headers: { "X-XSRF-TOKEN":csrf.token } });
  const text = await response.text(); expect(response.ok(),text).toBeTruthy();return text?JSON.parse(text):null;
}
async function get(page: Page,path:string) { const r=await page.request.get("/api/v1"+path);expect(r.ok()).toBeTruthy();return r.json(); }
async function references(page:Page) { return get(page,"/categories"); }
async function invoice(page:Page,year:number,number:string,total="100.00") {
  return write(page,"/invoices",{issuedOn:`${year}-01-01`,supplier:"Categorias navegador",number,description:"Despesa de teste",total,validate:true});
}
async function movement(page:Page,year:number,description:string) {
  const account=await write(page,"/accounts",{name:"Categorias navegador"});
  return write(page,"/movements",{accountId:account.id,bookedOn:`${year}-01-02`,description,amount:"100.00",direction:"DEBIT",validate:true});
}
async function assign(page:Page,type:"invoices"|"movements",record:any,categoryIds:string[]) {
  const current=await get(page,`/${type}/${record.id}`);
  return write(page,`/${type}/${record.id}/categories`,{version:current.classification.version,categoryIds,allowAi:false},"PUT");
}
async function open(page:Page,view:string,year:number) {
  await page.goto("/");if(view)await page.getByRole("button",{name:view,exact:true}).click();
  await page.getByLabel("Ano",{exact:true}).fill(String(year));await page.getByLabel("Ano",{exact:true}).press("Tab");
}
test.beforeEach(async({page})=>{
  const csrf=await(await page.request.get("/api/v1/csrf")).json();
  const login=await page.request.post("/api/v1/session",{form:{username:"gestor",password:"conta-e2e-dev"},headers:{"X-XSRF-TOKEN":csrf.token}});
  expect(login.ok()).toBeTruthy();
});

test("movement categories support multiple tags, manual removal and independent financial revisions",async({page})=>{
  const catalog=await references(page);const tags=catalog.filter((c:any)=>["6233","6263","6224"].includes(c.referenceCode));
  const record=await movement(page,2080,"E2E-CATEGORIAS-MOVIMENTO");
  await assign(page,"movements",record,tags.map((c:any)=>c.id));
  const after=await get(page,"/movements/"+record.id);expect(after.version).toBe(record.version);
  await open(page,"↗ Movimentos",2080);
  const row=page.locator("tr").filter({hasText:"E2E-CATEGORIAS-MOVIMENTO"});await expect(row).toHaveCount(1);
  await expect(page.getByRole("columnheader",{name:"Categorias",exact:true})).toBeVisible();
  await expect(row).toContainText("Natureza: Natureza não definida");
  await expect(row.getByText("+1 categorias",{exact:true})).toBeVisible();
  await row.getByRole("button",{name:"Editar categorias",exact:true}).click();
  const dialog=page.getByRole("dialog",{name:"Categorias do movimento",exact:true});
  await dialog.getByText("Origem e justificação das categorias",{exact:true}).click();
  await expect(dialog.locator(".category-evidence")).toContainText("Escolha manual");
  await expect(dialog.locator(".category-evidence")).toContainText("Serviços diversos › Seguros");
  await dialog.getByRole("checkbox",{name:/Serviços diversos › Seguros/}).uncheck();
  await dialog.getByRole("button",{name:"Guardar categorias",exact:true}).click();await expect(dialog).not.toBeVisible();
  await expect(row.getByRole("button",{name:"Seguros",exact:true})).toHaveCount(0);
  const updated=await get(page,"/movements/"+record.id);expect(updated.classification.rejectedCategoryIds).toContain(tags.find((c:any)=>c.referenceCode==="6263").id);
  expect(updated.version).toBe(record.version);
});

test("reconciliation shows inherited categories and updates them when invoice choices change",async({page})=>{
  const catalog=await references(page);const insurance=catalog.find((c:any)=>c.referenceCode==="6263"),paper=catalog.find((c:any)=>c.referenceCode==="6233"),people=catalog.find((c:any)=>c.referenceCode==="63");
  const a=await invoice(page,2081,"E2E-CAT-GRP-A","50.00"),b=await invoice(page,2081,"E2E-CAT-GRP-B","50.00"),m=await movement(page,2081,"E2E-CAT-GRP-PAGAMENTO");
  await assign(page,"invoices",a,[insurance.id]);await assign(page,"invoices",b,[paper.id]);
  await write(page,"/allocations",{invoiceId:a.id,movementId:m.id,amount:"50.00"});await write(page,"/allocations",{invoiceId:b.id,movementId:m.id,amount:"50.00"});
  await open(page,"",2081);const row=page.locator("tr.reconciliation-entry");await expect(row).toHaveCount(1);
  await expect(row.getByRole("button",{name:"Seguros",exact:true})).toHaveCount(1);
  await expect(row.getByRole("button",{name:"Material de escritório",exact:true})).toHaveCount(1);
  const bank=await get(page,"/movements/"+m.id);expect(bank.categoryDetails.every((c:any)=>c.origins.includes("INHERITED"))).toBeTruthy();
  await assign(page,"invoices",a,[people.id]);await page.getByRole("button",{name:"Atualizar dados",exact:true}).click();
  await expect(row.getByRole("button",{name:"Seguros",exact:true})).toHaveCount(0);
  await expect(row.getByRole("button",{name:"Gastos com o pessoal",exact:true})).toBeVisible();
});

test("catalog rename updates history and protected categories can be unlocked in a batch",async({page})=>{
  const catalog=await references(page),parent=catalog.find((c:any)=>c.referenceCode==="623");
  const category=await write(page,"/categories",{name:"E2E Categoria editável",parentId:parent.id,aliases:[]});
  const record=await invoice(page,2082,"E2E-CAT-RENAME");await assign(page,"invoices",record,[category.id]);
  await page.goto("/");await page.getByRole("button",{name:"⚙ Categorias e contas",exact:true}).click();
  const manager=page.locator("category-manager");await manager.getByLabel("Pesquisar catálogo",{exact:true}).fill("E2E Categoria editável");
  await manager.getByRole("button",{name:"Editar categoria",exact:true}).click();
  const form=manager.getByRole("region",{name:"Editar categoria",exact:true});
  await form.getByLabel("Nome da categoria",{exact:true}).fill("E2E Cabos personalizados");await form.getByRole("button",{name:"Guardar categoria",exact:true}).click();
  await expect(form).not.toBeVisible();
  await manager.getByLabel("Pesquisar catálogo",{exact:true}).fill("E2E Cabos personalizados");
  await manager.getByRole("checkbox",{name:"Selecionar E2E Cabos personalizados",exact:true}).check();
  await manager.getByRole("button",{name:"Permitir evolução automática",exact:true}).click();
  await expect(manager.locator(".category-catalog-row")).toContainText("Evolução automática");
  const edited=(await references(page)).find((c:any)=>c.id===category.id);
  await write(page,"/categories",{id:edited.id,version:edited.version,description:"Descrição atualizada em segundo plano",protected:false});
  await expect(manager.locator(".category-catalog-row")).toContainText("Descrição atualizada em segundo plano",{timeout:10000});
  const current=await get(page,"/invoices/"+record.id);expect(current.categories).toEqual(["E2E Cabos personalizados"]);expect(current.version).toBe(record.version);
});

test("manual merges and their undo preserve later assignments and visible history",async({page},testInfo)=>{
  const source=await write(page,"/categories",{name:"E2E Fusão USB-C",aliases:[]});
  const target=await write(page,"/categories",{name:"E2E Fusão USB",aliases:[]});
  const first=await invoice(page,2083,"E2E-CAT-MERGE-A"),later=await invoice(page,2083,"E2E-CAT-MERGE-B");
  await assign(page,"invoices",first,[source.id]);await assign(page,"invoices",later,[target.id]);
  await page.goto("/");await page.getByRole("button",{name:"⚙ Categorias e contas",exact:true}).click();
  const manager=page.locator("category-manager");await manager.getByLabel("Pesquisar catálogo",{exact:true}).fill("E2E Fusão");
  await manager.getByRole("checkbox",{name:"Selecionar E2E Fusão USB-C",exact:true}).check();
  await manager.getByRole("combobox",{name:"Destino da fusão",exact:true}).selectOption(target.id);
  await manager.getByRole("button",{name:"Fundir selecionadas no destino",exact:true}).click();
  await expect(manager.getByRole("checkbox",{name:"Selecionar E2E Fusão USB-C",exact:true})).toHaveCount(0);
  expect((await get(page,"/invoices/"+first.id)).categories).toEqual(["E2E Fusão USB"]);
  await manager.getByText(/^Histórico de evolução/).click();
  const change=manager.locator(".category-change").filter({hasText:"Fusão escolhida pelo utilizador"});
  await change.getByRole("button",{name:"Desfazer alteração",exact:true}).click();
  await expect(change).toContainText("Desfeita");
  expect((await get(page,"/invoices/"+first.id)).categories).toEqual(["E2E Fusão USB-C"]);
  expect((await get(page,"/invoices/"+later.id)).categories).toEqual(["E2E Fusão USB"]);
  await page.screenshot({path:testInfo.outputPath("category-catalog.png"),fullPage:true});
});

test("category editor works on a small screen and restores keyboard focus",async({page},testInfo)=>{
  const record=await movement(page,2084,"E2E-CATEGORIA-MOBILE");
  await page.setViewportSize({width:390,height:844});await open(page,"↗ Movimentos",2084);
  const row=page.locator("tr").filter({hasText:"E2E-CATEGORIA-MOBILE"});await row.getByRole("button",{name:"Editar categorias",exact:true}).click();
  const dialog=page.getByRole("dialog",{name:"Categorias do movimento",exact:true});await expect(dialog).toBeVisible();
  await dialog.getByLabel("Pesquisar categorias",{exact:true}).fill("Seguros");
  await dialog.getByRole("checkbox",{name:/Seguros/}).check();
  await page.screenshot({path:testInfo.outputPath("category-mobile-editor.png"),fullPage:true});
  await dialog.getByRole("button",{name:"Guardar categorias",exact:true}).focus();await page.keyboard.press("Enter");
  await expect(dialog).not.toBeVisible();await expect(row.getByRole("button",{name:"Seguros",exact:true})).toBeVisible();
  await expect(row.getByRole("button",{name:"Editar categorias",exact:true})).toBeFocused();
  await page.screenshot({path:testInfo.outputPath("category-mobile.png"),fullPage:true});
});
