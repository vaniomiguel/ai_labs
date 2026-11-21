import { expect, test, type Page, type Request } from "@playwright/test";

const invoiceList = /\/api\/v1\/invoices(?:\?|$)/;
const movementList = /\/api\/v1\/movements(?:\?|$)/;
const emptyPage = { items: [], total: 0, page: 0, size: 100 };

const applied = (page: Page) => page.getByRole("group", { name: "Filtros aplicados" });
const more = (page: Page) => page.getByRole("button", { name: "Mais filtros", exact: true });
const advanced = (page: Page) => page.getByRole("form", { name: "Mais filtros" });
const year = (page: Page) => page.getByLabel("Ano", { exact: true });
const month = (page: Page) => page.getByRole("combobox", { name: "Mês", exact: true });
const query = (page: Page) => page.getByLabel("Pesquisar", { exact: true });
const nav = (page: Page, name: string) => page.getByRole("navigation").getByRole("button", { name });
const params = (request: Request) => new URL(request.url()).searchParams;

async function setYear(page: Page, value: string) {
  await year(page).fill(value);
  await year(page).press("Tab");
}

async function search(page: Page, value: string) {
  await query(page).fill(value);
  await query(page).press("Enter");
}

test.beforeEach(async ({ page }) => {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  const login = await page.request.post("/api/v1/session", {
    form: { username: "gestor", password: "conta-e2e-dev" },
    headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(login.ok()).toBeTruthy();
});

test("advanced movement filters close, restore focus, and apply only on submit on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 760 });
  await page.route(movementList, route => route.fulfill({ json: emptyPage }));
  await page.goto("/movement");
  const day = advanced(page).getByLabel("Dia", { exact: true });
  await more(page).focus();
  await page.keyboard.press("Enter");
  await expect(more(page)).toHaveAttribute("aria-expanded", "true");
  await day.fill("12");
  await day.press("Escape");
  await expect(advanced(page)).toBeHidden();
  await expect(more(page)).toBeFocused();
  await expect(applied(page)).not.toContainText("Dia:");

  await more(page).click();
  await expect(day).toHaveValue("");
  await day.fill("5");
  await advanced(page).getByRole("button", { name: "Cancelar", exact: true }).click();
  await expect(more(page)).toBeFocused();
  await more(page).click();
  await expect(day).toHaveValue("");
  await day.fill("12");
  await advanced(page).getByLabel("Direção").selectOption("DEBIT");
  const filtered = page.waitForRequest(request => movementList.test(request.url()) && params(request).get("day") === "12");
  await advanced(page).getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  expect(params(await filtered).get("direction")).toBe("DEBIT");
  await expect(advanced(page)).toBeHidden();
  await expect(more(page)).toBeFocused();
  await expect(applied(page).getByRole("button", { name: "Remover Dia: 12" })).toBeVisible();
  await expect(applied(page)).toContainText("Direção: Débito");
  const longQuery = "Fornecedor".repeat(20);
  await search(page, longQuery);
  await expect(applied(page)).toContainText(longQuery);
  await more(page).click();
  await expect(day).toHaveValue("12");
  const bounds = await advanced(page).boundingBox();
  expect(bounds?.x).toBeGreaterThanOrEqual(0);
  expect((bounds?.x ?? 0) + (bounds?.width ?? 0)).toBeLessThanOrEqual(375);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
});

test("invoice and movement filters survive navigation independently and clear to defaults", async ({ page }) => {
  await page.goto("/invoice");
  const defaultYear = await year(page).inputValue();
  await setYear(page, "2067");
  await month(page).selectOption("2");
  await search(page, "Fornecedor de fevereiro");
  await expect(applied(page)).toContainText("Mês: Fevereiro");
  await expect(applied(page)).toContainText("Pesquisa: Fornecedor de fevereiro");

  await nav(page, "Movimentos").click();
  await expect(year(page)).toHaveValue(defaultYear);
  await expect(query(page)).toHaveValue("");
  await setYear(page, "2068");
  await search(page, "Débitos bancários");
  await page.goBack();
  await expect(year(page)).toHaveValue("2067");
  await expect(query(page)).toHaveValue("Fornecedor de fevereiro");

  const allYears = page.waitForRequest(request => invoiceList.test(request.url()) && !params(request).has("year"));
  await applied(page).getByRole("button", { name: "Remover Ano: 2067" }).click();
  expect(params(await allYears).get("month")).toBe("2");
  await expect(year(page)).toHaveValue("");
  await applied(page).getByRole("button", { name: "Limpar filtros" }).click();
  await expect(year(page)).toHaveValue(defaultYear);
  await expect(month(page)).toHaveValue("");
  await expect(query(page)).toHaveValue("");

  await page.goForward();
  await expect(year(page)).toHaveValue("2068");
  await expect(query(page)).toHaveValue("Débitos bancários");
  await page.reload();
  await expect(year(page)).toHaveValue(defaultYear);
  await expect(query(page)).toHaveValue("");
});

test("each secondary page shows and clears its own filters", async ({ page }) => {
  await page.goto("/reconciliation");
  const defaultYear = await year(page).inputValue();
  await more(page).click();
  await advanced(page).getByLabel("Participação no saldo").selectOption("excluded");
  await advanced(page).getByRole("button", { name: "Aplicar filtros" }).click();
  await expect(applied(page)).toContainText("Participação no saldo: Excluídos");

  await nav(page, "Saldos").click();
  await expect(more(page)).toHaveCount(0);
  await expect(query(page)).toHaveCount(0);
  await setYear(page, "2069");
  await month(page).selectOption("3");
  await expect(applied(page)).toContainText("Mês: Março");
  await applied(page).getByRole("button", { name: "Limpar filtros" }).click();
  await expect(year(page)).toHaveValue(defaultYear);

  await nav(page, "Histórico").click();
  await expect(more(page)).toHaveCount(0);
  await search(page, "Linha antiga");
  await expect(applied(page)).toContainText("Pesquisa: Linha antiga");
  await nav(page, "Extrações").click();
  const filename = page.getByLabel("Procurar ficheiro", { exact: true });
  await expect(filename).toHaveValue("");
  await filename.fill("extrato.pdf");
  await expect(applied(page)).toContainText("Ficheiro: extrato.pdf");
  await applied(page).getByRole("button", { name: "Remover Ficheiro: extrato.pdf" }).click();
  await expect(filename).toHaveValue("");

  await nav(page, "Categorias e contas").click();
  await page.getByLabel("Pesquisar catálogo").fill("Seguros");
  await page.getByLabel("Mostrar arquivadas").check();
  await expect(applied(page)).toContainText("Pesquisa: Seguros");
  await expect(applied(page)).toContainText("Inclui categorias arquivadas");
  await nav(page, "Processamento").click();
  await expect(applied(page)).toContainText("Estado: Pendentes");
  await applied(page).getByRole("button", { name: "Limpar filtros" }).click();
  await expect(page.getByRole("button", { name: /^Todas/ })).toHaveAttribute("aria-pressed", "true");
  await nav(page, "Categorias e contas").click();
  await expect(page.getByLabel("Pesquisar catálogo")).toHaveValue("Seguros");
  await applied(page).getByRole("button", { name: "Limpar filtros" }).click();
  await expect(page.getByLabel("Pesquisar catálogo")).toHaveValue("");
  await expect(page.getByLabel("Mostrar arquivadas")).not.toBeChecked();
  await nav(page, "Reconciliação").click();
  await expect(applied(page)).toContainText("Participação no saldo: Excluídos");
});

test("a category tag shows its readable path and resets pagination", async ({ page }) => {
  const category = { id: "test-category", name: "Serviços", path: "Fornecimentos › Serviços", archived: true, evidence: [] };
  await page.route(/\/api\/v1\/categories\?archived=true$/, route => route.fulfill({ json: [category] }));
  await page.route(invoiceList, route => route.fulfill({ json: {
    ...emptyPage,
    total: 101,
    items: [{ id: "test-invoice", supplier: "Fornecedor categorizado", reviewStatus: "OPEN", categoryDetails: [category] }],
  } }));
  await page.goto("/invoice");
  await page.getByRole("button", { name: "Seguinte →" }).click();
  await expect(page.getByText("Página 2", { exact: true })).toBeVisible();
  const request = page.waitForRequest(match => invoiceList.test(match.url()) && params(match).get("category") === category.id);
  await page.getByRole("button", { name: "Serviços · arquivada", exact: true }).click();
  expect(params(await request).get("page")).toBe("0");
  await expect(applied(page)).toContainText(`Categoria: ${category.path}`);
  await expect(page.getByText("Página 1", { exact: true })).toBeVisible();
  await more(page).click();
  await expect(advanced(page).getByLabel("Categoria")).toHaveValue(category.id);
});

test("a delayed invoice response cannot replace the latest search results", async ({ page }) => {
  const oldResponse = Promise.withResolvers<void>();
  await page.route(invoiceList, async route => {
    const query = params(route.request()).get("q");
    if (query === "Pesquisa antiga") await oldResponse.promise;
    const items = query ? [{ id: "test-invoice", supplier: query, reviewStatus: "OPEN" }] : [];
    await route.fulfill({ json: { ...emptyPage, items, total: items.length } });
  });
  await page.goto("/invoice");
  const first = page.waitForRequest(request => invoiceList.test(request.url()) && params(request).get("q") === "Pesquisa antiga");
  await search(page, "Pesquisa antiga");
  await first;
  await search(page, "Pesquisa recente");
  const row = page.getByRole("row").filter({ hasText: "Pesquisa recente" });
  const response = page.waitForResponse(result => invoiceList.test(result.url()) && params(result.request()).get("q") === "Pesquisa antiga");
  await expect(row).toBeVisible();
  oldResponse.resolve();
  await (await response).finished();
  await expect(row).toBeVisible();
  await expect(page.getByRole("row").filter({ hasText: "Pesquisa antiga" })).toHaveCount(0);
});
