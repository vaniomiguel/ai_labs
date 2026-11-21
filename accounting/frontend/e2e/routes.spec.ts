import { expect, Page, test } from "@playwright/test";
import type { JobTarget } from "../src/api";

const invoiceId = "e245b1ab-5314-4ad8-a91f-c75510594001";
const documentId = "e245b1ab-5314-4ad8-a91f-c75510594002";
const alertId = "e245b1ab-5314-4ad8-a91f-c75510594003";
const jobId = "e245b1ab-5314-4ad8-a91f-c75510594004";
const movementId = "e245b1ab-5314-4ad8-a91f-c75510594005";
const invoicePath = `/invoice/${invoiceId}/edit`;
const alertPath = `/alert/${alertId}/review`;
const draft = { supplier: "Fornecedor da ligação", type: "INVOICE", currency: "EUR", lines: [] };
const categoryEvidenceError = "Categoria sem evidência nos dados do registo";
const processingAlertMessage = `O processamento precisa de atenção: ${categoryEvidenceError}`;
const jobsPageUrl = "**/api/v1/jobs/page?*";
const emptyJobsPage = {
  items: [], total: 0, page: 0, size: 50,
  counts: { QUEUED: 0, RUNNING: 0, WAITING: 0, DONE: 0, FAILED: 0, active: 0, total: 0 },
};

async function signIn(page: Page) {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  const response = await page.request.post("/api/v1/session", {
    form: { username: "gestor", password: "conta-e2e-dev" }, headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(response.ok()).toBeTruthy();
}

async function mockRecord(page: Page, type: "invoice" | "movement", id: string, fields: Record<string, unknown>) {
  const record = { ...fields, id, version: 0, categoryDetails: [], reviewStatus: "OPEN" };
  await page.route(`**/api/v1/${type}s/${id}`, route => route.fulfill({ json: record }));
  await page.route(`**/api/v1/audit/${type}/${id}`, route => route.fulfill({ json: [] }));
}

async function mockProcessingAlert(page: Page, target?: Pick<JobTarget, "type" | "id" | "available">) {
  const alert = {
    id: alertId, documentId: null, entityType: "job", entityId: jobId, version: 0,
    status: "OPEN", code: "PROCESSING_FAILED", message: processingAlertMessage, proposal: null, repair: null,
  };
  await page.route("**/api/v1/alerts", route => route.fulfill({ json: [alert] }));
  await page.route(`**/api/v1/alerts/${alertId}`, route => route.fulfill({ json: alert }));
  await page.route(`**/api/v1/jobs/${jobId}`, route => {
    if (!target) return route.fulfill({ status: 404, json: { detail: "Registo indisponível." } });
    return route.fulfill({ json: {
      id: jobId, kind: target.type === "invoice" ? "CLASSIFY_INVOICE" : "CLASSIFY_MOVEMENT",
      state: "FAILED", attempts: 5, targetId: target.id, targetVersion: 0, error: categoryEvidenceError,
      target: { ...target, label: "Registo da tarefa", documentId: null },
    } });
  });
}

const contextUsage = {
  promptTokens: 750, responseTokens: 50, contextTokens: 1000,
  capacitySource: "RUNNING_MODEL", doneReason: "stop", warning: "NEAR_CONTEXT_LIMIT",
};

async function mockContextSession(page: Page) {
  await page.route("**/api/v1/me", route => route.fulfill({ json: { username: "gestor" } }));
  await page.route("**/api/v1/csrf", route => route.fulfill({ json: { token: "test-csrf-token" } }));
}

test("processing keeps a model warning after the task leaves the active list", async ({ page }) => {
  await mockContextSession(page);
  const createdAt = "2026-01-01T10:00:00Z";
  const warning = { model: "test-model:9b", createdAt, usage: contextUsage };
  const job = {
    id: jobId, kind: "REVIEW_INVOICE", state: "RUNNING", attempts: 0, error: null, createdAt,
    updatedAt: createdAt, target: { type: "invoice", id: invoiceId, label: "Fatura longa", available: true },
  };
  let warned = false;
  let finished = false;
  await page.route("**/api/v1/events", route => route.fulfill({
    contentType: "text/event-stream",
    body: `retry: 300\nevent: progress\ndata: {"revision":"${warned}-${finished}","jobs":${finished ? 0 : 1},"alerts":0}\n\n`,
  }));
  await page.route(jobsPageUrl, route => route.fulfill({ json: {
    ...emptyJobsPage, items: finished ? [] : [{ ...job, contextWarning: warned ? warning : null }],
    total: finished ? 0 : 1, recentContextWarnings: warned ? [{ jobId, kind: job.kind, warning }] : [],
  }}));
  await page.route(`**/api/v1/jobs/${jobId}`, route =>
    route.fulfill({ json: { ...job, contextWarning: warning, state: finished ? "DONE" : "RUNNING" } }));

  await page.goto("/processing");
  const row = page.getByRole("row").filter({ hasText: "Fatura longa" });
  await expect(row).toBeVisible();
  await expect(row).not.toContainText("Contexto próximo do limite");
  warned = true;
  await expect(row).toContainText("Contexto próximo do limite");
  finished = true;
  await expect(row).toHaveCount(0);
  const recent = page.getByRole("region", { name: "Avisos recentes do modelo" });
  await expect(recent).toContainText("test-model:9b");
  await recent.getByRole("button").click();
  await expect(page).toHaveURL(`/processing/${jobId}`);
  const detail = page.locator(".job-detail");
  await expect(detail).toContainText("80% usados");
  await expect(detail).toContainText("Contexto do modelo em execução");
});

test("assistant history shows measured usage, failed requests and older executions", async ({ page }) => {
  await mockContextSession(page);
  let measuredCallFinished = false;
  await page.route("**/api/v1/events", route => route.fulfill({
    contentType: "text/event-stream",
    body: `retry: 300\nevent: progress\ndata: {"revision":"${measuredCallFinished}","jobs":1,"alerts":0}\n\n`,
  }));
  await page.route("**/api/v1/assistant", route => route.fulfill({ json: {
    endpoint: "http://localhost:11434", models: [], selected: {}, crossYearMonths: 3,
    recommendations: { models: [], ocrCandidates: [] },
  }}));
  const execution = { task: "extract-invoice", digest: "test-digest", promptVersion: "v1:test",
    durationMs: 50, createdAt: "2026-01-01T10:00:00Z", outcome: "VALID", error: null, response: "{}" };
  await page.route("**/api/v1/assistant/executions", route => route.fulfill({ json: [
    ...(measuredCallFinished ? [{ ...execution, id: "new", model: "test-model:9b", usage: contextUsage }] : []),
    { ...execution, id: "rejected", model: "small-model:1b", outcome: "FAILED", response: null,
      error: "O pedido excedeu a capacidade deste modelo.",
      usage: { ...contextUsage, promptTokens: null, responseTokens: null, doneReason: null,
        capacitySource: "CONFIGURED", warning: "CONTEXT_REJECTED" } },
    { ...execution, id: "legacy", model: "legacy-model:1b", usage: null },
  ] }));
  await page.goto("/assistant");
  const details = page.locator("details.execution");
  const legacy = details.filter({ hasText: "legacy-model:1b" });
  await legacy.locator("summary").click();
  await expect(legacy).not.toContainText("% usados");
  measuredCallFinished = true;
  const measured = details.filter({ hasText: "test-model:9b" });
  await expect(measured).toContainText("Contexto próximo do limite");
  await measured.locator("summary").click();
  await expect(measured).toContainText("Entrada: 750");
  await expect(measured).toContainText("Resposta: 50");
  await expect(measured).toContainText("80% usados");
  const rejected = details.filter({ hasText: "small-model:1b" });
  await rejected.locator("summary").click();
  await expect(rejected).toContainText("O pedido excedeu a capacidade deste modelo.");
  await expect(rejected).toContainText("Contexto configurado");
  await expect(rejected).not.toContainText("% usados");
});

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
  await mockRecord(page, "invoice", invoiceId, draft);
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
  await mockRecord(page, "invoice", invoiceId, draft);
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

const linkedProcessingRecords = [
  { type: "invoice", id: invoiceId, fields: draft, label: "Fornecedor", value: draft.supplier },
  { type: "movement", id: movementId, fields: { description: "Pagamento da ligação" },
    label: "Descrição original", value: "Pagamento da ligação" },
] as const;

for (const { type, id, fields, label, value } of linkedProcessingRecords) {
  test(`a processing alert opens its ${type} editor with category controls`, async ({ page }) => {
    await signIn(page);
    await mockRecord(page, type, id, fields);
    await mockProcessingAlert(page, { type, id, available: true });

    await page.goto("/alert");
    await page.getByRole("button", { name: "Consultar →", exact: true }).click();
    await expect(page).toHaveURL(alertPath);
    const editor = page.getByRole("dialog", { name: "Editar registo" });
    const alert = editor.getByRole("region", { name: "Alerta de processamento" });
    await expect(editor.getByLabel(label, { exact: true })).toHaveValue(value);
    await expect(alert).toContainText(processingAlertMessage);
    await expect(alert.getByRole("button", { name: "Consultar tarefa" })).toBeEnabled();
    await expect(editor.getByRole("button", { name: "Editar categorias" })).toBeEnabled();

    await page.reload();
    await expect(editor.getByLabel(label, { exact: true })).toHaveValue(value);
    await editor.getByRole("button", { name: "Fechar editor" }).click();
    await expect(page).toHaveURL("/alert");
    await expect(editor).toHaveCount(0);
  });
}

test("a processing alert keeps its task link after its target is deleted", async ({ page }) => {
  await signIn(page);
  await mockProcessingAlert(page, { type: "invoice", id: invoiceId, available: false });

  await page.goto(alertPath);
  const banner = page.getByRole("status").filter({ hasText: processingAlertMessage });
  await banner.getByRole("button", { name: "Consultar tarefa" }).click();
  await expect(page).toHaveURL(`/processing/${jobId}`);
  await expect(page.getByText(categoryEvidenceError, { exact: true })).toBeVisible();
});

test("a processing alert still shows its message when the task is missing", async ({ page }) => {
  await signIn(page);
  await mockProcessingAlert(page);

  const jobResponse = page.waitForResponse(response => response.url().endsWith(`/api/v1/jobs/${jobId}`));
  await page.goto(alertPath);
  expect((await jobResponse).status()).toBe(404);
  const banner = page.getByRole("status").filter({ hasText: processingAlertMessage });
  await expect(banner).toBeVisible();
  await expect(banner.getByRole("button", { name: "Consultar tarefa" })).toHaveCount(0);
  await banner.getByRole("button", { name: "Voltar" }).click();
  await expect(page).toHaveURL("/alert");
});

test("a rejected processing page shows the error and allows retry", async ({ page }) => {
  await signIn(page);
  await page.route(jobsPageUrl, route => route.fulfill({
    status: 503, json: { detail: "Não foi possível carregar as tarefas." },
  }));
  await page.goto("/processing");
  await expect(page.getByRole("alert")).toContainText("Não foi possível carregar as tarefas.");
  const retry = page.getByRole("button", { name: "Atualizar tarefas" });
  await expect(retry).toBeEnabled();
  await page.getByRole("button", { name: "Fechar mensagem" }).click();

  await page.route(jobsPageUrl, route => route.fulfill({ json: emptyJobsPage }));
  const response = page.waitForResponse(result => result.url().includes("/jobs/page?") && result.ok());
  await retry.click();
  await response;
  await expect(page.getByText("Não existem tarefas neste estado.")).toBeVisible();
  await expect(retry).toBeEnabled();
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("a late processing page rejection is ignored after navigation", async ({ page }) => {
  await signIn(page);
  const release = Promise.withResolvers<void>();
  await page.route(jobsPageUrl, async route => {
    await release.promise;
    await route.fulfill({ status: 503, json: { detail: "Falha de uma página anterior." } });
  });
  const request = page.waitForRequest(result => result.url().includes("/jobs/page?"));
  await page.goto("/processing");
  await request;
  await expect(page.getByRole("button", { name: "Atualizar tarefas" })).toBeDisabled();

  await page.getByRole("navigation").getByRole("button", { name: "Faturas" }).click();
  await expect(page).toHaveURL("/invoice");
  const response = page.waitForResponse(result => result.url().includes("/jobs/page?") && result.status() === 503);
  release.resolve();
  await (await response).finished();
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("the history invoice selector is named and can link an invoice by keyboard", async ({ page }) => {
  await signIn(page);
  const row = { id: documentId, year: new Date().getFullYear(), description: "Linha a associar", status: "OPEN" };
  await page.route("**/api/v1/history", route => route.fulfill({ json: [row] }));
  await page.route("**/api/v1/invoices?size=500", route => route.fulfill({
    json: { items: [{ id: invoiceId, number: "FT E2E", total: "12.00" }] },
  }));
  await page.route(`**/api/v1/history/${row.id}/link`, route => route.fulfill({ json: { ...row, invoiceId } }));
  await page.goto("/history");
  await page.getByRole("button", { name: "Escolher faturas para associar" }).click();
  const historyRow = page.getByRole("row").filter({ hasText: row.description });
  const selector = historyRow.getByRole("combobox", { name: "Associar fatura", exact: true });
  await expect(selector.getByRole("option", { name: /FT E2E/ })).toBeAttached();
  await historyRow.getByRole("button").first().focus();
  await page.keyboard.press("Tab");
  await expect(selector).toBeFocused();
  const link = page.waitForRequest(request => request.url().endsWith(`/history/${row.id}/link`));
  await page.keyboard.press("f");
  expect((await link).postDataJSON()).toEqual({ invoiceId });
});

test("a missing reconciliation anchor exposes a way back to the list", async ({ page }) => {
  await signIn(page);
  await page.goto(`/reconciliation/${invoiceId}`);
  await page.getByRole("button", { name: "Voltar", exact: true }).click();
  await expect(page).toHaveURL("/reconciliation");
});
