import { expect, test, Page } from "@playwright/test";

async function post(page: Page, path: string, body: unknown) {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  const response = await page.request.post("/api/v1" + path, { data: body, headers: { "X-XSRF-TOKEN": csrf.token } });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}
async function account(page: Page) { return post(page, "/accounts", { name: "Conta de teste" }); }
async function invoice(page: Page, year: number, ref: string, total = "100.00", day = "01-01") {
  return post(page, "/invoices", { issuedOn: `${year}-${day}`, supplier: "Fornecedor Teste Navegador", number: ref, total, currency: "EUR", type: "INVOICE", validate: true });
}
async function movement(page: Page, accountId: string, year: number, description: string, amount = "60.00", day = "01-02", direction = "DEBIT") {
  return post(page, "/movements", { accountId, bookedOn: `${year}-${day}`, description, amount, currency: "EUR", direction, kind: "UNCLASSIFIED", validate: true });
}
async function openYear(page: Page, year: number) {
  await page.goto("/");
  await page.getByLabel("Ano", { exact: true }).fill(String(year));
  await page.getByLabel("Ano", { exact: true }).press("Tab");
  await expect(page.locator("reconciliation-table")).toBeVisible();
}
const rows = (page: Page) => page.locator("tr.reconciliation-entry");

test.beforeEach(async ({ page }) => {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  const login = await page.request.post("/api/v1/session", {
    form: { username: "gestor", password: "conta-e2e-dev" }, headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(login.ok()).toBeTruthy();
});

test("unmatched debit becomes one matched row with explicit amount and date differences", async ({ page }) => {
  const a = await account(page);
  await movement(page, a.id, 2090, "Pagamento E2E-PARTIAL-1001");
  await openYear(page, 2090);
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page)).toContainText("Sem fatura");
  await expect(rows(page)).toContainText("-60,00 €");
  await invoice(page, 2090, "E2E-PARTIAL-1001");
  await page.getByRole("button", { name: "⇄ Reconciliar", exact: true }).click();
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page)).toContainText("40,00 €");
  await expect(rows(page)).toContainText("Datas diferentes");
  await expect(rows(page)).toContainText("Valores diferentes");
  await expect(rows(page)).toContainText("2090-01-01");
  await expect(rows(page)).toContainText("2090-01-02");
  await expect(page.locator("tr.reconciliation-detail")).toHaveCount(0);
});

test("excludes invoice, movement or both and reincludes without hiding any records", async ({ page }) => {
  const a = await account(page);
  await invoice(page, 2091, "E2E-EXCLUDE-1001");
  await movement(page, a.id, 2091, "Pagamento E2E-EXCLUDE-1001");
  await openYear(page, 2091);
  for (const [choice, expected] of [["Só faturas", "-60,00 €"], ["Só movimentos", "100,00 €"], ["Ambos / todo o grupo", null]] as const) {
    await expect(rows(page)).toHaveCount(1);
    await page.getByRole("button", { name: "Excluir do saldo", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Excluir do saldo", exact: true });
    await dialog.getByRole("button", { name: choice, exact: true }).click();
    await dialog.getByRole("button", { name: "Excluir selecionados" }).click();
    await expect(dialog).not.toBeVisible();
    await expect(rows(page)).toHaveCount(2);
    if (expected) await expect(rows(page).filter({ hasNotText: "Excluído do saldo" })).toContainText(expected);
    await expect(rows(page).filter({ hasText: "Excluído do saldo" }).first()).toContainText("0,00 €");
    while (await page.getByRole("button", { name: "Voltar a incluir", exact: true }).count()) {
      const count = await page.getByRole("button", { name: "Voltar a incluir", exact: true }).count();
      const refreshed = page.waitForResponse(r => r.url().includes("/reconciliation/entries") && r.request().method() === "GET");
      await page.getByRole("button", { name: "Voltar a incluir", exact: true }).first().click();
      await refreshed;
      await expect(page.getByRole("button", { name: "Voltar a incluir", exact: true })).toHaveCount(count-1);
    }
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page)).toContainText("40,00 €");
  }
});

test("grouped payments occupy one row and support selecting individual records", async ({ page }) => {
  const a = await account(page);
  await invoice(page, 2092, "E2E-GROUP-1001", "60.00");
  await invoice(page, 2092, "E2E-GROUP-1002", "40.00");
  await movement(page, a.id, 2092, "Fornecedor Teste Navegador E2E-GROUP-1001 E2E-GROUP-1002", "100.00", "01-01");
  await openYear(page, 2092);
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page)).toContainText("2 faturas");
  await expect(rows(page)).toContainText("Correspondência");
  await expect(rows(page).getByText("Valores diferentes", { exact: true })).toHaveCount(0);
  await rows(page).getByRole("button", { name: "Ver detalhes" }).click();
  await expect(page.locator("tr.reconciliation-detail article")).toHaveCount(3);
  await rows(page).getByRole("button", { name: "Excluir do saldo", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Excluir do saldo", exact: true });
  await dialog.getByLabel("Fatura · E2E-GROUP-1001", { exact: true }).check();
  await dialog.getByRole("button", { name: "Excluir selecionados" }).click();
  await expect(rows(page)).toHaveCount(2);
  await expect(rows(page).filter({ hasNotText: "Excluído do saldo" })).toContainText("-60,00 €");
});

test("cross-year group shows full balance and filtered-period impact separately", async ({ page }) => {
  const a = await account(page);
  await invoice(page, 2093, "E2E-PERIOD-1001", "100.00", "12-10");
  await movement(page, a.id, 2094, "Pagamento E2E-PERIOD-1001", "60.00", "02-12");
  await openYear(page, 2094);
  await page.getByRole("combobox", { name: "Mês", exact: true }).selectOption("2");
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page).locator(".reconciliation-impact")).toContainText("40,00 €");
  await expect(rows(page)).toContainText("No período -60,00 €");
  await page.getByRole("combobox", { name: "Mês", exact: true }).selectOption("1");
  await expect(rows(page)).toHaveCount(0);
});

test("mobile layout scrolls within the table and details open from the keyboard", async ({ page }, testInfo) => {
  const a = await account(page);
  await invoice(page, 2095, "E2E-MOBILE-1001");
  await movement(page, a.id, 2095, "Pagamento E2E-MOBILE-1001", "120.00");
  await page.setViewportSize({ width: 390, height: 844 });
  await openYear(page, 2095);
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page)).toContainText("-20,00 €");
  const expand = rows(page).getByRole("button", { name: "Ver detalhes" });
  await expand.focus();
  await page.keyboard.press("Enter");
  await expect(rows(page).getByRole("button", { name: "Fechar detalhes" })).toHaveAttribute("aria-expanded", "true");
  await expect(page.locator("tr.reconciliation-detail")).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("mobile-reconciliation.png"), fullPage: true });
  const layout = await page.evaluate(() => ({
    width: window.innerWidth, document: document.documentElement.scrollWidth,
    overflowing: Array.from(document.querySelectorAll("body *"))
      .filter(e => !e.closest(".table-scroll") && e.getBoundingClientRect().right > window.innerWidth + 1)
      .map(e => ({ tag: e.tagName, class: e.className, right: e.getBoundingClientRect().right })),
  }));
  expect(layout.document, JSON.stringify(layout)).toBeLessThanOrEqual(layout.width+1);
});
