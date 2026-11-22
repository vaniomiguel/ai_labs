import { expect, test, type Page, type Request } from "@playwright/test";

const documentsApi = "**/api/v1/documents";
const archivedNotice = "Documentos arquivados. O processamento continua em segundo plano.";

type UploadFile = { name: string; mimeType: string; buffer: Buffer };

const invoiceActions = (page: Page) => page.locator(".page-heading").getByRole("group", { name: "Ações de faturas" });

test.beforeEach(async ({ page }) => {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  const response = await page.request.post("/api/v1/session", {
    form: { username: "gestor", password: "conta-e2e-dev" },
    headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(response.ok()).toBeTruthy();
});

async function chooseFiles(page: Page, format: "PDF" | "ZIP", files: UploadFile[]) {
  const actions = invoiceActions(page);
  const button = actions.getByRole("button", { name: `Importar ${format}`, exact: true });
  await button.focus();
  const [chooser] = await Promise.all([page.waitForEvent("filechooser"), button.press("Enter")]);
  const extension = `.${format.toLowerCase()}`;
  expect(await chooser.element().getAttribute("accept")).toBe(extension);
  expect(chooser.isMultiple()).toBeTruthy();
  await chooser.setFiles(files);
  return actions.locator(`input[type="file"][accept="${extension}"]`);
}

async function expectInvoiceHeaderActions(page: Page) {
  const actions = invoiceActions(page);
  for (const name of ["Importar PDF", "Importar ZIP", "+ Nova fatura"]) {
    await expect(actions.getByRole("button", { name, exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name, exact: true })).toHaveCount(1);
  }
  for (const extension of [".pdf", ".zip"]) {
    const input = actions.locator(`input[type="file"][accept="${extension}"]`);
    await expect(input).toHaveCount(1);
    await expect(input).toBeHidden();
  }
}

function isDocumentUpload(request: Request) {
  return request.method() === "POST" && new URL(request.url()).pathname === "/api/v1/documents";
}

async function expectUpload(request: Request, kind: string, filenames: string[]) {
  const body = request.postDataBuffer();
  expect(body).not.toBeNull();
  const form = await new Response(new Uint8Array(body ?? []), {
    headers: { "Content-Type": request.headers()["content-type"] },
  }).formData();
  expect(form.get("kind")).toBe(kind);
  expect(form.has("accountId")).toBeFalsy();
  expect(form.getAll("files").map(file => typeof file === "string" ? file : file.name)).toEqual(filenames);
}

async function createInvoice(page: Page) {
  const csrf = await (await page.request.get("/api/v1/csrf")).json();
  const supplier = "Fornecedor do teste de importação ZIP";
  const response = await page.request.post("/api/v1/invoices", {
    data: { supplier, number: "ZIP-2067/1", issuedOn: "2067-02-12", total: "20.00", currency: "EUR", type: "INVOICE", validate: true },
    headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(response.ok()).toBeTruthy();
  return supplier;
}

test("PDF import is keyboard accessible on a narrow invoice list and preserves filters", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 });
  const uploadResponse = Promise.withResolvers<void>();
  await page.route(documentsApi, async route => {
    if (isDocumentUpload(route.request())) await uploadResponse.promise;
    await route.continue();
  });
  await page.goto("/invoice");
  await page.getByRole("combobox", { name: "Mês", exact: true }).selectOption("2");
  await expect(page.getByText("Ainda não há faturas neste período")).toBeVisible();

  await expectInvoiceHeaderActions(page);
  const actions = invoiceActions(page);
  const pdf = actions.getByRole("button", { name: "Importar PDF", exact: true });
  const zip = actions.getByRole("button", { name: "Importar ZIP", exact: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);

  const files = [1, 2].map(index => ({
    name: `fatura-importacao-${index}.pdf`, mimeType: "application/pdf",
    buffer: Buffer.from(`%PDF-1.4\n% invoice import fixture ${index}\n`),
  }));
  const requested = page.waitForRequest(isDocumentUpload);
  const input = await chooseFiles(page, "PDF", files);
  try {
    await expectUpload(await requested, "INVOICE", files.map(file => file.name));
    await expect(pdf).toBeDisabled();
    await expect(zip).toBeDisabled();
  } finally {
    uploadResponse.resolve();
  }

  await expect(page.getByText(archivedNotice)).toBeVisible();
  await expect(pdf).toBeEnabled();
  await expect(input).toHaveValue("");
  await expect(page).toHaveURL("/invoice");
  await expect(page.getByRole("combobox", { name: "Mês", exact: true })).toHaveValue("2");
});

test("ZIP import can retry the same file and shows its archived original", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  const supplier = await createInvoice(page);
  const file = {
    name: "arquivo-importacao-faturas.zip", mimeType: "application/zip",
    buffer: Buffer.from("504b0506000000000000000000000000000000000000", "hex"),
  };
  const uploadError = "Não foi possível receber o ZIP.";
  let rejectUpload = true;
  await page.route(documentsApi, route => {
    if (rejectUpload && isDocumentUpload(route.request())) {
      return route.fulfill({ status: 422, json: { detail: uploadError } });
    }
    return route.continue();
  });
  await page.goto("/invoice");
  const year = page.getByLabel("Ano", { exact: true });
  await year.fill("2067");
  await year.press("Tab");
  await expect(page.getByRole("row").filter({ hasText: supplier })).toBeVisible();
  await expectInvoiceHeaderActions(page);
  const heading = await page.locator(".page-heading").boundingBox();
  const actions = await invoiceActions(page).boundingBox();
  expect(heading).not.toBeNull();
  expect(actions).not.toBeNull();
  if (heading && actions) {
    expect(actions.x).toBeGreaterThan(heading.x);
    expect(actions.x + actions.width).toBeCloseTo(heading.x + heading.width, 0);
  }

  const requested = page.waitForRequest(isDocumentUpload);
  const input = await chooseFiles(page, "ZIP", [file]);
  await expectUpload(await requested, "INVOICE_ARCHIVE", [file.name]);
  await expect(page.getByRole("alert")).toContainText(uploadError);
  await expect(input).toHaveValue("");
  await expect(page).toHaveURL("/invoice");

  rejectUpload = false;
  await chooseFiles(page, "ZIP", [file]);
  await expect(page.getByText(archivedNotice)).toBeVisible();
  await expect(page).toHaveURL("/invoice");
  await expect(year).toHaveValue("2067");

  await page.getByRole("navigation").getByRole("button", { name: "Extrações" }).click();
  const archive = page.getByRole("row").filter({ hasText: file.name });
  await expect(archive).toContainText("Arquivo ZIP de faturas");
  await expect(archive).toContainText("Em fila");
  await archive.getByRole("button", { name: "Consultar original →" }).click();

  const dialog = page.getByRole("dialog", { name: "Consultar arquivo de faturas" });
  await expect(dialog.getByRole("link", { name: "Descarregar original" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Consultar tarefa" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Guardar sem resolver" })).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: "Extrair novamente" })).toHaveCount(0);
});
