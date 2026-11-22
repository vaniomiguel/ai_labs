import { expect, test, type Page, type Route } from "@playwright/test";

const mismatch = "Base e IVA não coincidem com o total";
const missingArticle = "Existem artigos sem total";
const articleSumMismatch = "Os artigos não explicam o total da fatura";
const totals = {
  supplier: "Fornecedor de teste de desconto", issuedOn: "2026-01-30",
  net: "1064.00", tax: "97.00", discount: "24.00", total: "1140.00",
};

async function csrfHeaders(page: Page) {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  return { "X-XSRF-TOKEN": csrf.token };
}

async function newInvoice(page: Page) {
  await page.goto("/invoice/new");
  const editor = page.getByRole("dialog", { name: "Editar registo" });
  await editor.getByLabel("Fornecedor", { exact: true }).fill(totals.supplier);
  await editor.getByLabel("Data de emissão", { exact: true }).fill(totals.issuedOn);
  await editor.getByLabel("Base sem IVA", { exact: true }).fill(totals.net);
  await editor.getByLabel("IVA", { exact: true }).fill(totals.tax);
  await editor.getByLabel("Desconto", { exact: true }).fill(totals.discount);
  await editor.getByLabel("Total com IVA", { exact: true }).fill(totals.total);
  return editor;
}

test.beforeEach(async ({ page }) => {
  const login = await page.request.post("/api/v1/session", {
    form: { username: "gestor", password: "conta-e2e-dev" }, headers: await csrfHeaders(page),
  });
  expect(login.ok()).toBeTruthy();
});

test("invoice save reports field errors and clears the corrected checks before resubmission", async ({ page }) => {
  const editor = await newInvoice(page);
  const total = editor.getByLabel("Total com IVA", { exact: true });
  const validate = editor.getByRole("button", { name: "Validar e guardar", exact: true });
  const article = editor.getByLabel("Total · artigo 1", { exact: true });
  await editor.getByRole("button", { name: "+ Artigo", exact: true }).click();
  const rejection = page.waitForResponse(response => response.url().endsWith("/api/v1/invoices") && response.status() === 422);
  await validate.click();
  expect(await (await rejection).json()).toMatchObject({ detail: `${mismatch}; ${missingArticle}` });
  await expect(total).toBeFocused();
  await expect(total).toHaveAccessibleDescription(mismatch);
  await expect(total).toHaveCSS("border-top-color", "rgb(162, 79, 64)");
  for (const name of ["Base sem IVA", "IVA", "Desconto"]) {
    const field = editor.getByLabel(name, { exact: true });
    await expect(field).toHaveAttribute("aria-invalid", "true");
    await expect(field).toHaveAccessibleDescription(mismatch);
  }
  await expect(article).toHaveAccessibleDescription(missingArticle);
  await expect(editor.locator(".dialog-footer .error")).toHaveCount(0);

  await total.fill("1137.00");
  await expect(total).not.toHaveAttribute("aria-invalid", "true");
  await expect(editor.getByText(mismatch, { exact: true })).toHaveCount(0);
  await expect(article).toHaveAccessibleDescription(missingArticle);
  await editor.getByRole("button", { name: "Remover artigo", exact: true }).click();
  await expect(editor.getByText(missingArticle, { exact: true })).toHaveCount(0);
  await validate.click();
  await expect(editor).toHaveCount(0);
});

test("an invoice alert shows current line errors and resolves through the same checks", async ({ page }) => {
  const create = await page.request.post("/api/v1/invoices", {
    headers: await csrfHeaders(page),
    data: { ...totals, total: "1137.00", lines: [{ total: "1064.00" }, { total: "97.00" }, { discount: "-24.00" }],
      issues: ["Confirma a origem do comprovativo"], validate: false },
  });
  expect(create.ok(), await create.text()).toBeTruthy();
  const invoice = await create.json();
  const response = await page.request.get("/api/v1/alerts");
  const alerts = await response.json() as { id: string; entityId: string }[];
  const alert = alerts.find(item => item.entityId === invoice.id);
  expect(alert).toBeDefined();
  await page.goto(`/alert/${alert!.id}/review`);

  const editor = page.getByRole("dialog", { name: "Rever alerta" });
  const article = editor.getByLabel("Total · artigo 3", { exact: true });
  const validate = editor.getByRole("button", { name: "Validar e resolver", exact: true });
  await expect(article).toHaveAccessibleDescription(missingArticle);
  await expect(editor.getByLabel("Total · artigo 1", { exact: true })).not.toHaveAttribute("aria-invalid", "true");
  await expect(editor.getByText(missingArticle, { exact: true })).toHaveCount(1);
  await expect(editor.getByText("Confirma a origem do comprovativo", { exact: true })).toBeVisible();
  await validate.click();
  await expect(article).toBeFocused();
  await article.fill("-23.00");
  const total = editor.getByLabel("Total com IVA", { exact: true });
  await expect(total).toHaveAccessibleDescription(articleSumMismatch);
  await expect(article).not.toHaveAttribute("aria-invalid", "true");
  await expect(editor.getByText(missingArticle, { exact: true })).toHaveCount(0);
  await article.fill("-24.00");
  await expect(total).not.toHaveAttribute("aria-invalid", "true");
  await validate.click();
  await expect(editor).toHaveCount(0);
  const resolved = await page.request.get(`/api/v1/alerts/${alert!.id}`);
  expect(await resolved.json()).toMatchObject({ status: "RESOLVED" });
});

test("an older draft check cannot restore a corrected error", async ({ page }) => {
  const editor = await newInvoice(page);
  const total = editor.getByLabel("Total com IVA", { exact: true });
  await editor.getByRole("button", { name: "Validar e guardar", exact: true }).click();
  await expect(total).toHaveAccessibleDescription(mismatch);

  let olderCheck: Route | undefined;
  await page.route("**/api/v1/invoices/validation", route => {
    if (route.request().postDataJSON().total === "1141.00") {
      olderCheck = route;
      return;
    }
    return route.continue();
  });
  await total.fill("1141.00");
  await expect.poll(() => olderCheck !== undefined).toBeTruthy();
  await total.fill("1137.00");
  await expect(total).not.toHaveAttribute("aria-invalid", "true");
  const oldResponse = page.waitForResponse(response => response.url().endsWith("/invoices/validation")
    && response.request().postDataJSON().total === "1141.00");
  await olderCheck!.fulfill({ json: { issues: [{ code: "INVOICE_TOTAL_MISMATCH", message: mismatch, fields: ["total"] }] } });
  await (await oldResponse).finished();
  await expect(editor.getByText(mismatch, { exact: true })).toHaveCount(0);
});
