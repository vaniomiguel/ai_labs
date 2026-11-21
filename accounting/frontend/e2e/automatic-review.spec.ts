import { expect, test } from "@playwright/test";

test("invoice and movement badges distinguish inconclusive automatic reviews", async ({ page }) => {
  const csrfResponse = await page.request.get("/api/v1/csrf");
  const csrf = await csrfResponse.json();
  const login = await page.request.post("/api/v1/session", {
    form: { username: "gestor", password: "conta-e2e-dev" },
    headers: { "X-XSRF-TOKEN": csrf.token },
  });
  expect(login.ok()).toBeTruthy();

  await page.route("**/api/v1/invoices?*", (route) =>
    route.fulfill({ json: { total: 2, items: [
      {
        id: "invoice-review-partial", supplier: "Fatura com cobertura parcial", number: "FT 100",
        issuedOn: "2026-01-01", total: "100.00", remaining: "100.00", origin: "AI",
        reviewStatus: "AUTOMATIC", aiReview: { state: "REVIEWED", verdict: "SUGGESTION", model: "review-model" },
      },
      {
        id: "invoice-review-pass", supplier: "Fatura com revisão completa", number: "FT 200",
        issuedOn: "2026-01-01", total: "100.00", remaining: "100.00", origin: "AI",
        reviewStatus: "AUTOMATIC", aiReview: { state: "REVIEWED", verdict: "PASS", model: "review-model" },
      },
    ] } }),
  );
  await page.route("**/api/v1/movements?*", (route) =>
    route.fulfill({ json: { total: 1, items: [{
      id: "movement-review-missing", description: "Movimento sem fonte", bookedOn: "2026-01-02",
      amount: "8.00", direction: "DEBIT", origin: "RULES", reviewStatus: "AUTOMATIC",
      aiReview: { state: "REVIEWED", verdict: "SUGGESTION", model: null },
    }] } }),
  );

  await page.goto("/");
  await page.getByRole("button", { name: "▤ Faturas", exact: true }).click();
  const partial = page.getByRole("row").filter({ hasText: "Fatura com cobertura parcial" });
  await expect(partial).toContainText(/Revisão inconclusiva\s*·\s*review-model/);
  const complete = page.getByRole("row").filter({ hasText: "Fatura com revisão completa" });
  await expect(complete).toContainText(/Revisto por IA\s*·\s*review-model/);

  await page.getByRole("button", { name: "↗ Movimentos", exact: true }).click();
  const movement = page.getByRole("row").filter({ hasText: "Movimento sem fonte" });
  await expect(movement.getByText("✧ Revisão inconclusiva", { exact: true })).toBeVisible();
});
