import { expect, Page, test } from "@playwright/test";

const invoiceId = "e245b1ab-5314-4ad8-a91f-c75510594001";
const documentId = "e245b1ab-5314-4ad8-a91f-c75510594002";
const alertId = "e245b1ab-5314-4ad8-a91f-c75510594003";
const jobId = "e245b1ab-5314-4ad8-a91f-c75510594004";
const invoicePath = `/invoice/${invoiceId}/edit`;
const alertPath = `/alert/${alertId}/review`;
const draft = { supplier: "Fornecedor da ligação", type: "INVOICE", currency: "EUR", lines: [] };

async function signIn(page: Page) {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  const response = await page.request.post("/api/v1/session", {
    form: { username: "gestor", password: "conta-e2e-dev" }, headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(response.ok()).toBeTruthy();
}

async function mockInvoice(page: Page) {
  const record = { ...draft, id: invoiceId, version: 0, categoryDetails: [], reviewStatus: "OPEN" };
  await page.route(`**/api/v1/invoices/${invoiceId}`, route => route.fulfill({ json: record }));
  await page.route(`**/api/v1/audit/invoice/${invoiceId}`, route => route.fulfill({ json: [] }));
}

test("every sidebar section has its own URL", async ({ page }) => {
  await signIn(page);
  await page.goto("/");
  await expect(page).toHaveURL("/reconciliation");
  const navigation = page.getByRole("navigation", { name: "Navegação principal" });
  for (const [name, path] of [
    ["Faturas", "/invoice"], ["Movimentos", "/movement"], ["Extrações", "/document"],
    ["Processamento", "/processing"], ["Alertas", "/alert"], ["Saldos", "/balances"],
    ["Histórico", "/history"], ["Categorias e contas", "/settings"],
    ["Assistente de IA", "/assistant"], ["Reconciliação", "/reconciliation"],
  ]) {
    const section = navigation.getByRole("button", { name });
    await section.click();
    await expect(page).toHaveURL(path);
    await expect(section).toHaveAttribute("aria-current", "page");
  }
});

test("sign-in returns to an invoice bookmark and its editor survives reload", async ({ page }) => {
  await mockInvoice(page);
  await page.goto(invoicePath);
  await expect(page).toHaveURL(/\/login\?returnTo=/);
  await page.getByLabel("Palavra-passe", { exact: true }).fill("conta-e2e-dev");
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page).toHaveURL(invoicePath);
  const editor = page.getByRole("dialog", { name: "Editar registo" });
  await expect(editor.getByLabel("Fornecedor", { exact: true })).toHaveValue(draft.supplier);
  await page.reload();
  await expect(editor.getByLabel("Fornecedor", { exact: true })).toHaveValue(draft.supplier);
  await editor.getByRole("button", { name: "Fechar editor" }).click();
  await expect(page).toHaveURL("/invoice");
});

test("alert review restores the original and its alert context through browser history", async ({ page }) => {
  await signIn(page);
  const alert = { id: alertId, documentId, entityType: "document", entityId: documentId,
    version: 0, status: "OPEN", message: "Validar extração da ligação", proposal: null, repair: null };
  await page.route("**/api/v1/alerts", route => route.fulfill({ json: [alert] }));
  await page.route(`**/api/v1/alerts/${alertId}`, route => route.fulfill({ json: alert }));
  await page.route(`**/api/v1/documents/${documentId}`, route => route.fulfill({ json: {
    id: documentId, filename: "Original da ligação.txt", kind: "INVOICE", status: "OPEN", draft,
  }}));
  await page.route(`**/api/v1/documents/${documentId}/attempts`, route => route.fulfill({ json: [] }));

  await page.goto("/alert");
  await page.getByRole("button", { name: "Consultar →", exact: true }).click();
  await expect(page).toHaveURL(alertPath);
  const review = page.getByRole("dialog", { name: "Rever extração" });
  await expect(review.getByRole("heading", { name: "Original da ligação.txt" })).toBeVisible();
  await page.goBack();
  await expect(page).toHaveURL("/alert");
  await expect(review).toHaveCount(0);
  await page.goForward();
  await expect(page).toHaveURL(alertPath);
  await page.reload();
  await expect(review.getByRole("button", { name: "✧ Pedir tentativa de correção à IA" })).toBeVisible();
});

test("a job opens the canonical record editor and closing restores the job", async ({ page }) => {
  await signIn(page);
  await mockInvoice(page);
  await page.route(`**/api/v1/jobs/${jobId}`, route => route.fulfill({ json: {
    id: jobId, kind: "REVIEW_INVOICE", state: "DONE", attempts: 0, targetId: invoiceId, targetVersion: 0,
    target: { type: "invoice", id: invoiceId, label: "Fatura do processamento", available: true },
  }}));
  const path = `/processing/${jobId}`;
  await page.goto(path);
  await page.getByRole("button", { name: "Consultar registo associado →" }).click();
  await expect(page).toHaveURL(invoicePath);
  await page.getByRole("button", { name: "Fechar editor" }).click();
  await expect(page).toHaveURL(path);
  await expect(page.getByRole("button", { name: "Consultar registo associado →" })).toBeVisible();
});

test("a missing reconciliation anchor exposes a way back to the list", async ({ page }) => {
  await signIn(page);
  await page.goto(`/reconciliation/${invoiceId}`);
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await expect(page).toHaveURL("/reconciliation");
});
