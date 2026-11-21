import { expect, test, Page, Request, Route } from "@playwright/test";

const documentId = "e245b1ab-5314-4ad8-a91f-c75510593001";
const recordId = "e245b1ab-5314-4ad8-a91f-c75510593002";
const alertId = "e245b1ab-5314-4ad8-a91f-c75510593003";
const draftUrl = "**/api/v1/documents/" + documentId + "/draft";
const snackbar = (page: Page) => page.locator("mat-snack-bar-container");
const success = (action: string) => action + ": operação concluída com sucesso.";
const invoiceDraft = () => ({
  supplier: "Fornecedor de teste", issuedOn: "2026-01-29", total: "62.21",
  currency: "EUR", type: "INVOICE", lines: [], categories: [],
});

async function reply(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function holdSave(page: Page, url: string, response: unknown) {
  let release = () => {};
  const gate = new Promise<void>(resolve => { release = resolve; });
  const requests: Request[] = [];
  await page.route(url, async route => {
    requests.push(route.request());
    await gate;
    await reply(route, response);
  });
  return { requests, release };
}

async function setupDocument(page: Page, entry: "documents" | "alerts", kind = "INVOICE") {
  const document = {
    id: documentId, version: 3, filename: "Fatura-feedback.txt", kind, status: "OPEN",
    mediaType: "text/plain", pages: 1, text: "Original preservado", layout: {}, draft: invoiceDraft(),
  };
  const other = { ...document, id: recordId, filename: "Outro-original.txt" };
  const alert = {
    id: alertId, documentId, entityType: "document", entityId: documentId, version: 0,
    message: "Rever fatura de teste", code: "INVALID_INVOICE", status: "OPEN", proposal: null, repair: null,
  };
  await page.route("**/api/v1/documents", route => reply(route, [document, other]));
  await page.route("**/api/v1/documents/*", route =>
    reply(route, route.request().url().endsWith(recordId) ? other : document));
  await page.route("**/api/v1/documents/*/attempts", route => reply(route, []));
  await page.route(draftUrl, route => reply(route, { ...document, version: 4 }));
  await page.route("**/api/v1/alerts", route => reply(route, [alert]));
  await page.route("**/api/v1/alerts/" + alertId, route => reply(route, alert));
  await page.goto("/");
  const navigation = page.getByRole("navigation", { name: "Navegação principal" });
  await navigation.getByRole("button", { name: entry === "documents" ? /Extrações/ : /Alertas/ }).click();
  if (entry === "documents") await page.getByRole("button", { name: "▧ " + document.filename, exact: true }).click();
  else await page.getByRole("button", { name: "Consultar →", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Rever extração", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Guardar sem resolver", exact: true })).toBeEnabled();
  return { document, other, alert, dialog };
}

async function setupRecord(page: Page, kind: "INVOICE" | "MOVEMENT") {
  const row = {
    ...(kind === "INVOICE" ? invoiceDraft() : {
      description: "Movimento de teste", bookedOn: "2026-01-29", amount: "62.21",
      direction: "DEBIT", kind: "UNCLASSIFIED", currency: "EUR",
    }),
    id: recordId, version: 3, reviewStatus: "OPEN", reviewIssues: [], categoryDetails: [],
  };
  const resource = kind === "INVOICE" ? "invoices" : "movements";
  await page.route("**/api/v1/" + resource + "?*", route => reply(route, { items: [row], total: 1 }));
  await page.route("**/api/v1/" + resource + "/" + recordId, route => reply(route, row));
  await page.route("**/api/v1/audit/**", route => reply(route, []));
  await page.goto("/");
  await page.getByRole("button", { name: kind === "INVOICE" ? "▤ Faturas" : "↗ Movimentos", exact: true }).click();
  const title = kind === "INVOICE" ? "Fornecedor de teste" : "Movimento de teste";
  await page.getByRole("button", { name: title, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Editar registo", exact: true });
  await expect(dialog.getByRole("button", { name: "Guardar rascunho", exact: true })).toBeEnabled();
  return { row, resource, dialog };
}

test.beforeEach(async ({ page }) => {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  const login = await page.request.post("/api/v1/session", {
    form: { username: "gestor", password: "conta-e2e-dev" }, headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(login.ok()).toBeTruthy();
});

for (const entry of ["documents", "alerts"] as const) {
  for (const resolve of [false, true]) {
    const action = resolve ? "Validar e resolver" : "Guardar sem resolver";
    test(`${action} closes document review opened from ${entry}`, async ({ page }) => {
      const { document, dialog } = await setupDocument(page, entry);
      await dialog.getByLabel("Fornecedor", { exact: true }).fill("Fornecedor corrigido");
      const saved = page.waitForRequest(request => request.url().endsWith("/" + documentId + "/draft"));
      await dialog.getByRole("button", { name: action, exact: true }).click();
      const request = await saved;
      expect(request.method()).toBe("PUT");
      expect(request.postDataJSON()).toMatchObject({
        version: document.version, resolve, draft: { supplier: "Fornecedor corrigido" },
      });
      await expect(dialog).not.toBeVisible();
      await expect(snackbar(page)).toContainText(success(action));
      await expect(page.locator(".success.banner")).toHaveCount(0);
      await snackbar(page).getByRole("button", { name: "Fechar", exact: true }).click();
      await expect(snackbar(page)).toHaveCount(0);
    });
  }
}

for (const kind of ["INVOICE", "MOVEMENT"] as const) {
  for (const validate of [false, true]) {
    const action = validate ? "Validar e guardar" : "Guardar rascunho";
    test(`${action} confirms a ${kind} save and preserves its period filters`, async ({ page }) => {
      const { row, resource, dialog } = await setupRecord(page, kind);
      const year = await page.getByLabel("Ano", { exact: true }).inputValue();
      const saved = page.waitForRequest(request =>
        request.url().endsWith("/" + resource + "/" + row.id) && request.method() === "PUT");
      await dialog.getByRole("button", { name: action, exact: true }).click();
      expect((await saved).postDataJSON()).toMatchObject({ id: row.id, version: row.version, validate });
      await expect(dialog).not.toBeVisible();
      await expect(snackbar(page)).toContainText(success(action));
      await expect(snackbar(page)).toContainText(validate ? "saldos atualizados" : "fica fora dos saldos");
      await expect(page.getByLabel("Ano", { exact: true })).toHaveValue(year);
      await expect(page.locator(".success.banner")).toHaveCount(0);
    });
  }
}

for (const status of [422, 409, 500]) {
  for (const target of ["document", "record"] as const) {
    test(`${target} save failure ${status} retains edits without success feedback`, async ({ page }) => {
      const setup = target === "document"
        ? await setupDocument(page, "documents") : await setupRecord(page, "INVOICE");
      const url = target === "document" ? draftUrl : "**/api/v1/invoices/" + recordId;
      await page.route(url, route => reply(route, { detail: "Não foi possível guardar os dados de teste." }, status));
      await setup.dialog.getByLabel("Fornecedor", { exact: true }).fill("Manter esta edição");
      const action = target === "document" ? "Validar e resolver" : "Validar e guardar";
      await setup.dialog.getByRole("button", { name: action, exact: true }).click();
      await expect(setup.dialog.locator(".dialog-footer .error")).toContainText("Não foi possível guardar");
      await expect(setup.dialog.getByLabel("Fornecedor", { exact: true })).toHaveValue("Manter esta edição");
      await expect(setup.dialog.getByRole("button", { name: action, exact: true })).toBeEnabled();
      await expect(snackbar(page)).toHaveCount(0);
    });
  }
}

for (const target of ["document", "record"] as const) {
  test(`${target} save prevents duplicate submissions and waits for confirmation`, async ({ page }) => {
    const setup = target === "document"
      ? await setupDocument(page, "documents") : await setupRecord(page, "INVOICE");
    const url = target === "document" ? draftUrl : "**/api/v1/invoices/" + recordId;
    const response = "document" in setup ? setup.document : setup.row;
    const pending = await holdSave(page, url, response);
    const save = setup.dialog.getByRole("button", { name: target === "document" ? "Guardar sem resolver" : "Guardar rascunho", exact: true });
    const validate = setup.dialog.getByRole("button", { name: target === "document" ? "Validar e resolver" : "Validar e guardar", exact: true });
    await save.click();
    await expect.poll(() => pending.requests.length).toBe(1);
    await expect(save).toBeDisabled();
    await expect(validate).toBeDisabled();
    await save.dispatchEvent("click");
    await validate.dispatchEvent("click");
    await expect(setup.dialog).toBeVisible();
    await expect(snackbar(page)).toHaveCount(0);
    pending.release();
    await expect(setup.dialog).not.toBeVisible();
    await expect(snackbar(page)).toContainText("operação concluída com sucesso");
    expect(pending.requests).toHaveLength(1);
  });
}

for (const reopen of [false, true]) {
  test(`late document save ${reopen ? "leaves a newer review open" : "does not reopen a closed review"}`, async ({ page }) => {
    const { document, other, dialog } = await setupDocument(page, "documents");
    const pending = await holdSave(page, draftUrl, document);
    await dialog.getByRole("button", { name: "Guardar sem resolver", exact: true }).click();
    await expect.poll(() => pending.requests.length).toBe(1);
    await dialog.getByRole("button", { name: "Fechar revisão", exact: true }).click();
    if (reopen) {
      await page.getByRole("button", { name: "▧ " + other.filename, exact: true }).click();
      await expect(dialog.getByRole("heading", { name: other.filename, exact: true })).toBeVisible();
    }
    const refreshed = page.waitForResponse(response => response.url().endsWith("/api/v1/documents"));
    pending.release();
    await refreshed;
    if (reopen) await expect(dialog.getByRole("heading", { name: other.filename, exact: true })).toBeVisible();
    else await expect(dialog).not.toBeVisible();
    await expect(snackbar(page)).toHaveCount(0);
  });
}

test("late record save leaves a newer manual editor open", async ({ page }) => {
  const { row, dialog } = await setupRecord(page, "INVOICE");
  const pending = await holdSave(page, "**/api/v1/invoices/" + row.id, row);
  await dialog.getByRole("button", { name: "Guardar rascunho", exact: true }).click();
  await expect.poll(() => pending.requests.length).toBe(1);
  await dialog.getByRole("button", { name: "Fechar editor", exact: true }).click();
  await page.getByRole("button", { name: "+ Nova fatura", exact: true }).click();
  await dialog.getByLabel("Fornecedor", { exact: true }).fill("Novo rascunho local");
  const refreshed = page.waitForResponse(response => response.url().includes("/api/v1/invoices?"));
  pending.release();
  await refreshed;
  await expect(dialog.getByLabel("Fornecedor", { exact: true })).toHaveValue("Novo rascunho local");
  await expect(snackbar(page)).toHaveCount(0);
});

for (const kind of ["ARCHIVE", "SUPPORT"]) {
  test(`${kind} documents still permit saving but block validation`, async ({ page }) => {
    const { dialog } = await setupDocument(page, "documents", kind);
    await expect(dialog.getByRole("button", { name: "Validar e resolver", exact: true })).toBeDisabled();
    await dialog.getByRole("button", { name: "Guardar sem resolver", exact: true }).click();
    await expect(dialog).not.toBeVisible();
    await expect(snackbar(page)).toContainText(success("Guardar sem resolver"));
  });
}

test("a refresh failure after persistence preserves the successful close and snackbar", async ({ page }) => {
  const { dialog } = await setupDocument(page, "documents");
  await page.route("**/api/v1/documents", route => reply(route, { detail: "Atualização indisponível." }, 500));
  await dialog.getByRole("button", { name: "Validar e resolver", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(snackbar(page)).toContainText(success("Validar e resolver"));
  await expect(page.getByRole("alert")).toContainText("Atualização indisponível.");
});

test("saving an alert draft closes review and stops its repair polling", async ({ page }) => {
  const { alert, dialog } = await setupDocument(page, "alerts");
  let polls = 0;
  await page.route("**/api/v1/alerts/" + alertId, route => {
    polls++;
    return reply(route, { ...alert, repair: { jobId: recordId, state: "RUNNING", outcome: null } });
  });
  await dialog.getByRole("button", { name: "Atualizar proposta", exact: true }).click();
  await expect(dialog.locator(".repair-status")).toContainText("A analisar");
  await dialog.getByRole("button", { name: "Guardar sem resolver", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(snackbar(page)).toContainText(success("Guardar sem resolver"));
  const completedPolls = polls;
  await page.waitForTimeout(2300);
  expect(polls).toBe(completedPolls);
});

test("mobile keyboard saves show an announced snackbar that expires after seven seconds", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { dialog } = await setupDocument(page, "documents");
  const save = dialog.getByRole("button", { name: "Guardar sem resolver", exact: true });
  await save.focus();
  await page.keyboard.press("Enter");
  await expect(dialog).not.toBeVisible();
  await expect(snackbar(page)).toContainText(success("Guardar sem resolver"));
  await expect(snackbar(page).locator('[aria-live="polite"]')).toContainText(success("Guardar sem resolver"));
  await page.screenshot({ path: testInfo.outputPath("save-feedback-mobile.png"), fullPage: true });
  await expect(snackbar(page)).toHaveCount(0, { timeout: 9000 });
});
