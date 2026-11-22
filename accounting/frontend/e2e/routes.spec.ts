import { expect, Page, test } from "@playwright/test";
import type { AlertView, CategoryDetail, Classification, JobTarget } from "../src/api";

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

test("invoice history links classification results and filters old jobs without losing the draft", async ({ page }) => {
  const createdAt = "2026-01-01T10:00:00Z";
  const legacyJobId = "e245b1ab-5314-4ad8-a91f-c75510594006";
  const category: CategoryDetail = { id: "software", name: "Licenças de software", path: "Licenças de software",
    archived: false, origins: ["AI"], evidence: [] };
  const classification: Classification = { version: 4, state: "PENDING", reason: null, jobId, jobState: "RUNNING",
    categories: [], rejectedCategoryIds: [], catalogRevision: 0 };
  const job = { id: jobId, kind: "CLASSIFY_INVOICE", state: "DONE", attempts: 0, createdAt, updatedAt: createdAt,
    targetId: invoiceId, targetVersion: 4, target: { type: "invoice", id: invoiceId, label: "Fatura Tuxera", available: true } };
  const legacyJob = { ...job, id: legacyJobId, kind: "NORMALIZE_CATEGORIES" };
  const scheduled = { id: "old", actor: "gestor", action: "REQUEST_AI_CLASSIFICATION", createdAt,
    after: { jobState: "QUEUED" } };
  const result = { id: "result", actor: "SYSTEM", action: "AI_CLASSIFICATION_RESULT", createdAt,
    after: { jobId, state: "CLASSIFIED", categoryNames: [category.name], reason: "O artigo identifica uma licença." } };
  let finished = false;

  await page.route("**/api/v1/**", route => route.fulfill({ json: [] }));
  await mockContextSession(page);
  await page.route("**/api/v1/invoices?*", route => route.fulfill({ json: { items: [], total: 0 } }));
  await page.route("**/api/v1/events", route => route.fulfill({
    contentType: "text/event-stream",
    body: `retry: 300\nevent: progress\ndata: {"revision":"${finished}","jobs":${finished ? 0 : 1},"alerts":0}\n\n`,
  }));
  await page.route(`**/api/v1/invoices/${invoiceId}`, route => route.fulfill({ json: { ...draft, id: invoiceId, version: 0,
    classification: finished ? { ...classification, version: 5, state: "CLASSIFIED", jobState: "DONE", categories: [category] }
      : classification } }));
  await page.route(`**/api/v1/audit/invoice/${invoiceId}`, route => route.fulfill({ json: finished ? [scheduled, result] : [scheduled] }));
  await page.route(`**/api/v1/jobs/${jobId}`, route => route.fulfill({ json: job }));
  await page.route(jobsPageUrl, route => {
    const query = new URL(route.request().url()).searchParams;
    expect(query.get("classificationInvoiceId")).toBe(invoiceId);
    const items = query.get("state") === "active" ? [] : [job, legacyJob];
    return route.fulfill({ json: { ...emptyJobsPage, items, total: items.length,
      counts: { ...emptyJobsPage.counts, DONE: 2, total: 2 } } });
  });

  await page.goto(invoicePath);
  const editor = page.getByRole("dialog", { name: "Editar registo" });
  const supplier = editor.getByLabel("Fornecedor", { exact: true });
  await supplier.fill("Alteração por guardar");
  const history = editor.locator("details").filter({ has: page.getByText("Histórico de alterações", { exact: true }) });
  await history.getByText("Histórico de alterações", { exact: true }).click();
  await expect(history.getByText(/Classificação agendada/)).toBeVisible();
  await expect(history.getByRole("button", { name: "Consultar tarefa", exact: true })).toHaveCount(0);
  finished = true;
  await expect(history.getByText("O artigo identifica uma licença.")).toBeVisible();
  await expect(history.getByText(category.name, { exact: true })).toBeVisible();
  await expect(supplier).toHaveValue("Alteração por guardar");
  await history.getByRole("button", { name: "Consultar tarefa", exact: true }).click();
  await expect(page).toHaveURL(`/processing/${jobId}?classificationInvoiceId=${invoiceId}`);
  await expect(page.locator(".job-detail")).toContainText("Fatura Tuxera");
  await page.getByRole("button", { name: "Fechar detalhe da tarefa" }).click();
  await expect(supplier).toHaveValue("Alteração por guardar");

  await history.getByText("Histórico de alterações", { exact: true }).click();
  await history.getByRole("button", { name: "Ver tarefas de classificação" }).click();
  await expect(page.getByRole("button", { name: "Remover Classificação da fatura" })).toBeVisible();
  await page.getByRole("button", { name: "Pendentes 0" }).click();
  await expect(page.getByText("Não existem tarefas neste estado.")).toBeVisible();
  await page.getByRole("button", { name: "Concluídas 2" }).click();
  await expect(page.getByRole("row").filter({ hasText: "Fatura Tuxera" })).toHaveCount(2);
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

test("every sidebar section has its own URL and record creation stays on its list", async ({ page }) => {
  await signIn(page);
  await page.goto("/");
  await expect(page).toHaveURL("/reconciliation");
  const navigation = page.getByRole("navigation", { name: "Navegação principal" });
  const addInvoice = page.getByRole("button", { name: "+ Nova fatura", exact: true });
  const addMovement = page.getByRole("button", { name: "+ Movimento", exact: true });
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
    await expect(addInvoice).toHaveCount(path === "/invoice" ? 1 : 0);
    await expect(addMovement).toHaveCount(path === "/movement" ? 1 : 0);
  }
});

for (const [path, buttonName, field] of [
  ["/invoice", "+ Nova fatura", "Fornecedor"],
  ["/movement", "+ Movimento", "Data do movimento"],
] as const) {
  test(`${buttonName} opens its form from the list on a narrow screen`, async ({ page }) => {
    await signIn(page);
    await page.setViewportSize({ width: 320, height: 720 });
    await page.goto(path);

    const actionHeader = page.locator(path === "/invoice" ? ".page-heading" : ".record-list-heading");
    const addRecord = actionHeader.getByRole("button", { name: buttonName, exact: true });
    await expect(addRecord).toBeVisible();
    expect(await actionHeader.evaluate(header => header.scrollWidth)).toBeLessThanOrEqual(
      await actionHeader.evaluate(header => header.clientWidth),
    );
    if (path === "/movement") {
      await expect(actionHeader.getByRole("button", { name: "Importar extrato", exact: true })).toBeVisible();
    }

    await addRecord.click();
    await expect(page).toHaveURL(`${path}/new`);
    const editor = page.getByRole("dialog", { name: "Editar registo", exact: true });
    await expect(editor.getByLabel(field, { exact: true })).toBeVisible();
    if (path === "/movement") {
      await expect(editor.getByText("Data valor", { exact: true })).toHaveCount(0);
    }
    await editor.getByRole("button", { name: "Fechar editor", exact: true }).click();
    await expect(page).toHaveURL(path);
    await expect(editor).toHaveCount(0);
  });
}

test("movement editor shows only the booking date for a saved movement", async ({ page }) => {
  await signIn(page);
  await mockRecord(page, "movement", movementId, {
    bookedOn: "2026-02-01", valueOn: "2026-02-03", description: "Pagamento",
  });
  await page.goto(`/movement/${movementId}/edit`);

  const editor = page.getByRole("dialog", { name: "Editar registo" });
  await expect(editor.getByLabel("Data do movimento", { exact: true })).toHaveValue("2026-02-01");
  await expect(editor.getByText("Data valor", { exact: true })).toHaveCount(0);
});

test("bank statement review can still add a draft movement", async ({ page }) => {
  await signIn(page);
  await page.route(`**/api/v1/documents/${documentId}`, route => route.fulfill({ json: {
    id: documentId, filename: "Extrato de teste.txt", kind: "STATEMENT", status: "OPEN",
    mediaType: "text/plain", draft: { movements: [] },
  } }));
  await page.route(`**/api/v1/documents/${documentId}/attempts`, route => route.fulfill({ json: [] }));
  const reviewPath = `/document/${documentId}/review`;
  await page.goto(reviewPath);

  const review = page.getByRole("dialog", { name: "Rever extração", exact: true });
  await expect(review).toBeVisible();
  const draftRows = review.getByRole("button", { name: "Remover movimento", exact: true });
  await expect(draftRows).toHaveCount(0);
  await review.getByRole("button", { name: "+ Movimento", exact: true }).click();
  await expect(draftRows).toHaveCount(1);
  await expect(page).toHaveURL(reviewPath);
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

test("invoice category actions share a header and remain usable on a small screen", async ({ page }) => {
  await signIn(page);
  const reason = "Sem categoria escolhida pelo utilizador";
  await mockRecord(page, "invoice", invoiceId, {
    ...draft, classification: { state: "REVIEW", reason, categories: [] },
  });
  await page.goto(invoicePath);

  const editor = page.getByRole("dialog", { name: "Editar registo" });
  const categories = editor.getByRole("region", { name: "Categorias" });
  const header = categories.locator("header");
  const edit = header.getByRole("button", { name: "Editar categorias", exact: true });
  const classify = header.getByRole("button", { name: "Classificar com IA", exact: true });
  await expect(edit).toBeVisible();
  await expect(classify).toBeVisible();
  const [editBox, classifyBox] = await Promise.all([edit.boundingBox(), classify.boundingBox()]);
  if (!editBox || !classifyBox) throw new Error("Category actions must have visible bounds");
  expect(classifyBox.x).toBeGreaterThan(editBox.x + editBox.width);
  expect(Math.abs(classifyBox.y + classifyBox.height / 2 - editBox.y - editBox.height / 2)).toBeLessThanOrEqual(1);

  await page.setViewportSize({ width: 320, height: 720 });
  const supplier = editor.getByLabel("Fornecedor", { exact: true });
  await supplier.fill("Fornecedor editado localmente");
  await expect(categories).toContainText(reason);
  expect(await categories.evaluate(card => card.scrollWidth <= card.clientWidth)).toBe(true);
  await edit.focus();
  await edit.press("Tab");
  await expect(classify).toBeFocused();
  await edit.press("Enter");
  const categoryDialog = page.getByRole("dialog", { name: "Categorias do documento" });
  await expect(categoryDialog).toBeVisible();
  await categoryDialog.getByRole("button", { name: "Fechar categorias" }).click();
  await expect(supplier).toHaveValue("Fornecedor editado localmente");
  await expect(page).toHaveURL(invoicePath);
});

test("invoice editor retries AI category review and applies the chosen suggestion without replacing the draft", async ({ page }) => {
  await signIn(page);
  const original: CategoryDetail = { id: "initial", name: "Seguros", path: "Seguros", archived: false, origins: ["AI"], evidence: [] };
  const revised: CategoryDetail = { ...original, id: "revised", name: "Comunicação", path: "Comunicação", origins: ["MANUAL"] };
  let classification: Classification = { version: 4, state: "CLASSIFIED", reason: null, categories: [original], rejectedCategoryIds: [], catalogRevision: 0 };
  let posts = 0;
  let approvals = 0;
  let revision = 0;
  await page.route("**/api/v1/events", route => route.fulfill({
    contentType: "text/event-stream",
    body: `retry: 300\nevent: progress\ndata: {"revision":"${revision}","jobs":1,"alerts":0}\n\n`,
  }));
  await mockRecord(page, "invoice", invoiceId, draft);
  await page.route(`**/api/v1/invoices/${invoiceId}`, route => {
    if (route.request().method() === "PUT") {
      const payload = route.request().postDataJSON();
      expect(payload).toMatchObject({ version: 0, validate: true, supplier: "Fornecedor editado localmente" });
      expect(payload.categoryConfirmation).toBeUndefined();
      return route.fulfill({ json: { id: invoiceId } });
    }
    expect(route.request().method()).toBe("GET");
    return route.fulfill({ json: { ...draft, id: invoiceId, version: 0, classification, categoryDetails: classification.categories } });
  });
  await page.route(`**/api/v1/invoices/${invoiceId}/categories/review`, route => {
    expect(route.request().postDataJSON()).toEqual({ version: classification.version });
    posts++;
    classification = { ...classification, version: classification.version + 1, jobId,
      jobKind: "REVIEW_INVOICE_CATEGORIES", jobState: "QUEUED", jobError: null };
    return route.fulfill({ status: 202, json: classification });
  });
  await page.route(`**/api/v1/invoices/${invoiceId}/categories/review/apply`, route => {
    expect(route.request().postDataJSON()).toEqual({ version: classification.version, reviewJobId: jobId, categoryIds: [revised.id] });
    approvals++;
    classification = { ...classification, version: classification.version + 1,
      categories: [revised], jobKind: null, jobId: null, jobState: null, review: null };
    return route.fulfill({ json: classification });
  });

  await page.goto(invoicePath);
  const editor = page.getByRole("dialog", { name: "Editar registo" });
  const categories = editor.getByRole("region", { name: "Categorias" });
  const review = categories.getByRole("button", { name: "Rever categorias com IA", exact: true });
  const supplier = editor.getByLabel("Fornecedor", { exact: true });
  await supplier.fill("Fornecedor editado localmente");
  await expect(categories).toContainText("A IA usa os dados guardados da fatura");
  await expect(categories.getByRole("button", { name: original.name, exact: true })).toBeVisible();
  await review.click();
  await expect(categories).toContainText("Revisão de categorias agendada");
  for (const [jobState, status] of [["RUNNING", "A rever categorias"], ["WAITING", "Revisão a aguardar IA"], ["FAILED", "Revisão de categorias falhou"]] as const) {
    classification = { ...classification, jobState, jobError: jobState === "FAILED" ? "A resposta do modelo é inválida." : null };
    revision++;
    await expect(categories).toContainText(status);
  }
  await expect(categories).toContainText("A resposta do modelo é inválida.");
  await review.click();
  await expect(categories).toContainText("Revisão de categorias agendada");
  classification = { ...classification, jobState: "DONE", review: {
    summary: "A fatura documenta um serviço de comunicação.",
    assessments: [{ categoryId: original.id, fit: "POOR_FIT", reason: "A descrição não menciona um seguro." }],
    suggestions: [{ categoryId: revised.id, path: revised.path, evidence: "Carregamento de telemóvel",
      reason: "É um serviço de telecomunicações.", replacesCategoryIds: [original.id] }],
  } };
  revision++;
  const report = categories.getByRole("region", { name: "Revisão das categorias por IA" });
  const originalChoice = report.getByRole("checkbox", { name: original.name, exact: true });
  const suggestion = report.getByRole("checkbox", { name: revised.name, exact: true });
  const approve = report.getByRole("button", { name: "Guardar alterações", exact: true });
  await expect(report).toContainText("Pouco adequada");
  await expect(report).toContainText("Carregamento de telemóvel");
  await expect(report).toContainText("Alternativa a Seguros");
  await expect(originalChoice).toBeChecked();
  await expect(suggestion).not.toBeChecked();
  await expect(approve).toBeDisabled();
  await expect(categories.getByRole("button", { name: original.name, exact: true })).toBeVisible();
  await expect(categories.getByRole("button", { name: revised.name, exact: true })).toHaveCount(0);
  await originalChoice.uncheck();
  await suggestion.check();
  revision++;
  await expect(suggestion).toBeChecked();
  await expect(originalChoice).not.toBeChecked();
  await approve.click();
  await expect(categories.getByRole("button", { name: revised.name, exact: true })).toBeVisible();
  await expect(categories.getByRole("button", { name: original.name, exact: true })).toHaveCount(0);
  await expect(report).toHaveCount(0);
  await expect(supplier).toHaveValue("Fornecedor editado localmente");
  await expect(page).toHaveURL(invoicePath);
  expect(posts).toBe(2);
  expect(approvals).toBe(1);
  await editor.getByRole("button", { name: "Validar e guardar", exact: true }).click();
  await expect(editor).not.toBeVisible();
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

test("alert status filter defaults to open and preserves the selection when returning from a resolved alert", async ({ page }) => {
  await signIn(page);
  const open = { id: alertId, status: "OPEN", message: "Confirmar os dados da extração" };
  const resolvedId = "e245b1ab-5314-4ad8-a91f-c75510594006";
  const resolved = {
    id: resolvedId, status: "RESOLVED", message: "Corrigir os dados da fatura", documentId: null,
    entityType: "invoice", entityId: invoiceId, resolvedBy: "gestor", resolvedAt: "2026-11-10T12:00:00Z",
  };
  await page.route("**/api/v1/events", route => route.fulfill({ status: 204 }));
  await page.route("**/api/v1/alerts", route => route.fulfill({ json: [open, resolved] }));
  await page.route(`**/api/v1/alerts/${resolvedId}`, route => route.fulfill({ json: resolved }));
  await page.route(`**/api/v1/invoices/${invoiceId}`, route => route.fulfill({ json: {
    ...draft, id: invoiceId, version: 1, categoryDetails: [], reviewStatus: "CORRECTED",
  } }));
  await page.route(`**/api/v1/audit/invoice/${invoiceId}`, route => route.fulfill({ json: [] }));

  await page.goto("/alert");
  const filter = page.getByRole("combobox", { name: "Estado", exact: true });
  const openCard = page.getByRole("article").filter({ hasText: open.message });
  const resolvedCard = page.getByRole("article").filter({ hasText: resolved.message });
  const badge = page.getByRole("navigation").getByRole("button", { name: /Alertas/ }).locator(".nav-count");
  await expect(filter).toHaveValue("OPEN");
  await expect(openCard).toBeVisible();
  await expect(resolvedCard).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Lista de alertas 1", exact: true })).toBeVisible();

  await filter.selectOption({ label: "Todos" });
  await expect(openCard).toBeVisible();
  await expect(resolvedCard).toBeVisible();
  await expect(page.getByRole("heading", { name: "Lista de alertas 2", exact: true })).toBeVisible();
  await expect(badge).toHaveText("1");

  await filter.selectOption({ label: "Resolvidos" });
  await expect(openCard).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Lista de alertas 1", exact: true })).toBeVisible();
  await expect(badge).toHaveText("1");
  await expect(resolvedCard).toContainText(/gestor · 10\/11\/2026/);
  await resolvedCard.getByRole("button", { name: "Consultar →" }).click();
  await expect(page).toHaveURL(`/alert/${resolvedId}/review`);
  const editor = page.getByRole("dialog", { name: "Editar registo" });
  await expect(editor.getByRole("region", { name: "Questões do alerta" })).toContainText(resolved.message);
  await editor.getByRole("button", { name: "Fechar editor" }).click();
  await expect(page).toHaveURL("/alert");
  await expect(filter).toHaveValue("RESOLVED");
  await expect(resolvedCard).toBeVisible();

  await page.reload();
  await expect(filter).toHaveValue("OPEN");
  await expect(openCard).toBeVisible();
  await expect(resolvedCard).toHaveCount(0);
});

async function createAlertForDismissal(page: Page) {
  await signIn(page);
  const { token } = await (await page.request.get("/api/v1/csrf")).json();
  const headers = { "X-XSRF-TOKEN": token };
  const filename = `lifecycle-${Date.now()}.pdf`;
  const upload = await page.request.post("/api/v1/documents", {
    headers,
    multipart: { kind: "SUPPORT", files: { name: filename, mimeType: "application/pdf", buffer: Buffer.from(filename) } },
  });
  expect(upload.ok()).toBeTruthy();
  const [source] = await upload.json();
  const invoice = await page.request.post("/api/v1/invoices", {
    headers, data: { ...draft, total: "100.00", documentId: source.id },
  });
  expect(invoice.ok()).toBeTruthy();
  const { id } = await invoice.json();
  const alerts: AlertView[] = await (await page.request.get("/api/v1/alerts")).json();
  const alert = alerts.find(alert => alert.entityId === id);
  if (!alert) throw new Error("O alerta da fatura de teste não foi criado.");
  return { alert, filename };
}

test("dismissal persists and permanent deletion requires confirmation", async ({ page }) => {
  const { alert, filename } = await createAlertForDismissal(page);
  await page.goto("/alert");
  const filter = page.getByRole("combobox", { name: "Estado", exact: true });
  const card = page.getByRole("article").filter({ hasText: filename });
  const remove = card.getByRole("button", { name: "Eliminar", exact: true });
  const confirmation = page.getByRole("alertdialog", { name: "Eliminar este alerta?" });

  await remove.click();
  await expect(confirmation).toContainText("O alerta será eliminado permanentemente. Esta ação é irreversível.");
  await expect(confirmation).toContainText(alert.message);
  await expect(confirmation.getByRole("button", { name: "Cancelar" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(confirmation).toHaveCount(0);
  await expect(card).toBeVisible();
  await expect(remove).toBeFocused();

  await card.getByRole("button", { name: "Ocultar" }).click();
  await expect(card).toHaveCount(0);
  await filter.selectOption({ label: "Todos" });
  await expect(card).toHaveCount(0);
  await page.reload();
  await expect(filter).toHaveValue("OPEN");
  await filter.selectOption({ label: "Ocultados" });
  await expect(card).toBeVisible();

  await card.getByRole("button", { name: "Consultar →" }).click();
  const detail = page.getByRole("dialog", { name: "Alerta ocultado" });
  await expect(detail).toContainText(alert.message);
  await detail.getByRole("button", { name: "Fechar" }).click();
  await expect(filter).toHaveValue("dismissed");

  await remove.click();
  await confirmation.getByRole("button", { name: "Eliminar permanentemente" }).click();
  await expect(confirmation).toHaveCount(0);
  await expect(filter).toHaveValue("dismissed");
  await expect(card).toHaveCount(0);
  expect((await page.request.get(`/api/v1/alerts/${alert.id}`)).status()).toBe(404);
  expect((await page.request.get(`/api/v1/audit/alert/${alert.id}`)).status()).toBe(404);
});

test("a rejected alert mutation keeps the alert and shows the server error", async ({ page }) => {
  await signIn(page);
  const alert = { id: alertId, status: "RESOLVED", version: 1, message: "Alerta com revisão concorrente" };
  const failure = { status: 409, json: { detail: "O registo foi alterado. Atualiza a página." } };
  await page.route("**/api/v1/events", route => route.fulfill({ status: 204 }));
  await page.route("**/api/v1/alerts", route => route.fulfill({ json: [alert] }));
  await page.route(`**/api/v1/alerts/${alert.id}/dismiss`, route => route.fulfill(failure));
  await page.route(`**/api/v1/alerts/${alert.id}?version=1`, route => route.fulfill(failure));

  await page.goto("/alert");
  const filter = page.getByRole("combobox", { name: "Estado", exact: true });
  await filter.selectOption({ label: "Resolvidos" });
  const card = page.getByRole("article").filter({ hasText: alert.message });
  await card.getByRole("button", { name: "Ocultar" }).click();
  await expect(page.getByRole("alert")).toContainText(failure.json.detail);
  await expect(card).toBeVisible();

  await card.getByRole("button", { name: "Eliminar", exact: true }).click();
  const confirmation = page.getByRole("alertdialog", { name: "Eliminar este alerta?" });
  await confirmation.getByRole("button", { name: "Eliminar permanentemente" }).click();
  await expect(confirmation.getByRole("alert")).toContainText(failure.json.detail);
  await page.keyboard.press("Escape");
  await expect(confirmation).toHaveCount(0);
  await expect(card).toBeVisible();
  await expect(filter).toHaveValue("RESOLVED");
});

test("alert status filter shows an empty resolved list and resets at sign-out", async ({ page }) => {
  await signIn(page);
  const open = { id: alertId, status: "OPEN", message: "Validar extração pendente" };
  await page.route("**/api/v1/alerts", route => route.fulfill({ json: [open] }));
  await page.goto("/alert");
  await expect(page.getByText(open.message, { exact: true })).toBeVisible();
  const filter = page.getByRole("combobox", { name: "Estado", exact: true });

  await filter.selectOption({ label: "Resolvidos" });
  await expect(page.getByText("Não existem alertas resolvidos.", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Lista de alertas 0", exact: true })).toBeVisible();
  await expect(page.getByRole("article")).toHaveCount(0);

  await page.getByRole("button", { name: /Terminar sessão/ }).click();
  await expect(page).toHaveURL("/login");
  await page.getByLabel("Palavra-passe", { exact: true }).fill("conta-e2e-dev");
  await page.getByRole("button", { name: "Entrar" }).click();
  await page.getByRole("navigation").getByRole("button", { name: /Alertas/ }).click();
  await expect(filter).toHaveValue("OPEN");
  await expect(page.getByText(open.message, { exact: true })).toBeVisible();
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
