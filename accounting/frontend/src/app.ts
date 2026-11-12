import { Component, signal, inject, computed, OnDestroy } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { MatButtonModule } from "@angular/material/button";
import { Api, euros, errorMessage, labels, JobCounts, JobItem, JobPage } from "./api";
import { FieldsEditor } from "./fields-editor";
import { PdfViewer } from "./pdf-viewer";
import { ReconciliationTable } from "./reconciliation";

/** Owner workspace joining reconciliation, document review and auditable financial records. */
@Component({
  selector: "conta-app",
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    FieldsEditor,
    PdfViewer,
    ReconciliationTable,
  ],
  templateUrl: "./app.html",
})
export class AppComponent implements OnDestroy {
  private api = inject(Api);
  private readonly activeOperations = signal(0);
  readonly working = computed(() => this.activeOperations() > 0);
  readonly money = euros;
  readonly labels = labels;
  readonly user = signal("");
  readonly loading = signal(false);
  readonly error = signal("");
  readonly notice = signal("");
  readonly view = signal("reconciliation");
  readonly state = signal<any>({
    invoices: [],
    movements: [],
    allocations: [],
    candidates: [],
    months: [],
    years: [],
    accounts: [],
    categories: [],
    documents: [],
    alerts: [],
    jobs: [],
    history: [],
    imports: [],
    summary: {},
    aiSuggestions: [],
    reconciliation: { items: [], total: 0, page: 0, size: 100 },
  });
  readonly assistant = signal<any>(null);
  readonly executions = signal<any[]>([]);
  readonly counts = signal<{jobs: number; alerts: number; jobStates?: JobCounts}>({ jobs: 0, alerts: 0 });
  readonly processing = signal<JobPage | null>(null);
  readonly jobDetail = signal<JobItem | null>(null);
  readonly selectedJobId = signal<string | null>(null);
  readonly jobsLoading = signal(false);
  readonly processingFilters = [
    { value: "active", label: "Pendentes" },
    { value: "QUEUED", label: "Em fila" },
    { value: "RUNNING", label: "A processar" },
    { value: "WAITING", label: "A aguardar modelo" },
    { value: "DONE", label: "Concluídas" },
    { value: "FAILED", label: "Falhadas" },
    { value: "all", label: "Todas" },
  ];
  processingState = "active";
  processingPage = 0;
  private processingRequest = 0;
  readonly editor = signal<any>(null);
  readonly reviewDocument = signal<any>(null);
  readonly activeAlert = signal<any>(null);
  readonly timeline = signal<any[]>([]);
  readonly assignment = signal<any>(null);
  readonly assignmentMovements = signal<any[]>([]);
  readonly allInvoices = signal<any[]>([]);
  readonly confirmDelete = signal<any>(null);
  username = "gestor";
  password = "";
  uploadKind = "INVOICE";
  uploadAccount = "";
  uploading = signal(false);
  recordKind = "INVOICE";
  mappingKind = "HISTORY";
  readonly columnNames = Array.from({ length: 26 }, (_, i) =>
    String.fromCharCode(65 + i),
  );
  readonly mappingNames: Record<string, string> = {
    date: "Data",
    description: "Descrição",
    received: "Recebido",
    payment: "Pagamento",
    balance: "Saldo",
    debit: "Débito",
    credit: "Crédito",
    amount: "Montante",
  };
  filters: any = {
    year: new Date().getFullYear(),
    month: "",
    day: "",
    q: "",
    from: "",
    to: "",
    category: "",
    status: "",
    direction: "",
    min: "",
    max: "",
    time: "",
    page: 0,
  };
  readonly months = [
    "Janeiro",
    "Fevereiro",
    "Março",
    "Abril",
    "Maio",
    "Junho",
    "Julho",
    "Agosto",
    "Setembro",
    "Outubro",
    "Novembro",
    "Dezembro",
  ];
  readonly navigation = [
    ["reconciliation", "⇄", "Reconciliação"],
    ["invoices", "▤", "Faturas"],
    ["movements", "↗", "Movimentos"],
    ["documents", "▧", "Extrações"],
    ["processing", "◌", "Processamento"],
    ["alerts", "◉", "Alertas"],
    ["balances", "▥", "Saldos"],
    ["history", "↥", "Histórico"],
    ["settings", "⚙", "Categorias e contas"],
    ["assistant", "✧", "Assistente de IA"],
  ];
  private events?: EventSource;
  private progressRevision = "";
  private financialRequest = 0;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  constructor() {
    void this.restore();
  }
  /** Restores the session without reporting a normal first-time sign-in as an error. */
  async restore() {
    try {
      const me = await this.api.get("/me");
      this.user.set(me.username);
      await this.api.get("/csrf");
      await this.refresh();
      this.connect();
    } catch {
      /* The sign-in screen is the expected anonymous state. */
    }
  }
  async login() {
    await this.run(async () => {
      await this.api.signIn(this.username, this.password);
      this.password = "";
      this.user.set((await this.api.get("/me")).username);
      await this.refresh();
      this.connect();
    });
  }
  async logout() {
    await this.run(async () => {
      await this.api.post("/logout");
      this.events?.close();
      this.user.set("");
    });
  }
  navigate(view: string) {
    this.view.set(view);
    this.filters.page = 0;
    if (view === "processing") {
      this.processingState = "active";
      this.processingPage = 0;
      this.closeJob();
    }
    if (view === "assistant") void this.loadAssistant();
    else void this.refresh();
  }
  /** Reloads server-derived amounts; the browser never calculates financial balances. */
  async refresh() {
    if (this.view() === "processing") return this.loadProcessing();
    if (this.view() === "assistant") return this.loadAssistant();
    const request = ++this.financialRequest;
    const view = this.view();
    this.loading.set(true);
    try {
      const [
        invoices,
        movements,
        allocations,
        candidates,
        months,
        years,
        accounts,
        categories,
        documents,
        alerts,
        jobs,
        history,
        imports,
        summary,
        aiSuggestions,
        reconciliation,
      ] = await Promise.all([
        this.api.get("/invoices", this.filters),
        this.api.get("/movements", this.filters),
        this.api.get("/allocations"),
        this.api.get("/reconciliation/candidates"),
        this.api.get("/balances/" + this.filters.year),
        this.api.get("/balances"),
        this.api.get("/accounts"),
        this.api.get("/categories"),
        this.api.get("/documents"),
        this.api.get("/alerts"),
        this.api.get("/jobs"),
        this.api.get("/history"),
        this.api.get("/imports"),
        this.api.get("/reconciliation/summary", {
          year: this.filters.year,
          month: this.filters.month,
        }),
        this.api.get("/reconciliation/ai-suggestions"),
        this.view() === "reconciliation" ? this.api.get("/reconciliation/entries", this.filters) : Promise.resolve(this.state().reconciliation),
      ]);
      if (request !== this.financialRequest || this.view() !== view) return;
      this.state.set({
        invoices: invoices.items,
        invoiceTotal: invoices.total,
        movements: movements.items,
        movementTotal: movements.total,
        allocations,
        candidates,
        months,
        years,
        accounts,
        categories,
        documents,
        alerts,
        jobs,
        history,
        imports,
        summary,
        aiSuggestions,
        reconciliation,
      });
      this.counts.update((previous) => ({
        ...previous,
        alerts: alerts.filter((a: any) => a.status === "OPEN").length,
      }));
      if (!this.uploadAccount && accounts.length)
        this.uploadAccount = accounts[0].id;
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      if (request === this.financialRequest) this.loading.set(false);
    }
  }
  /** Queue filters are global and do not reuse the financial year/month filters. */
  async loadProcessing() {
    const request = ++this.processingRequest;
    const selectedId = this.selectedJobId();
    this.jobsLoading.set(true);
    try {
      const [page, detail] = await Promise.all([
        this.api.get<JobPage>("/jobs/page", { state: this.processingState, page: this.processingPage, size: 50 }),
        selectedId ? this.api.get<JobItem>("/jobs/" + selectedId) : Promise.resolve(null),
      ]);
      if (request !== this.processingRequest || this.view() !== "processing") return;
      if (this.processingPage > 0 && this.processingPage * 50 >= page.total) {
        this.processingPage = Math.max(0, Math.ceil(page.total / 50) - 1);
        await this.loadProcessing();
        return;
      }
      this.processing.set(page);
      this.jobDetail.set(detail);
      this.counts.update((previous) => ({ ...previous, jobs: page.counts.active, jobStates: page.counts }));
    } catch (error) {
      if (request === this.processingRequest) this.error.set(errorMessage(error));
    } finally {
      if (request === this.processingRequest) this.jobsLoading.set(false);
    }
  }
  filterJobs(state: string) {
    this.processingState = state;
    this.processingPage = 0;
    void this.loadProcessing();
  }
  jobCount(state: string): number {
    const counts = this.counts().jobStates ?? this.processing()?.counts;
    return counts?.[state === "all" ? "total" : state as keyof JobCounts] ?? 0;
  }
  async openJob(id: string) {
    this.view.set("processing");
    this.selectedJobId.set(id);
    this.jobDetail.set(null);
    await this.loadProcessing();
    if (this.selectedJobId() === id) {
      setTimeout(() => document.getElementById("job-detail-heading")?.focus());
    }
  }
  closeJob() {
    ++this.processingRequest;
    this.selectedJobId.set(null);
    this.jobDetail.set(null);
    this.jobsLoading.set(false);
  }
  async openJobTarget(job: JobItem) {
    const target = job.target;
    if (!target.available) return;
    if (target.type === "document") await this.openDocument(target.id);
    else if (target.type === "invoice" || target.type === "movement") {
      await this.openJobRecord(target.type, target.id);
    } else if (target.type === "alert") {
      await this.run(async () => {
        const alerts = await this.api.get<any[]>("/alerts");
        const alert = alerts.find((item) => item.id === target.id);
        if (alert) await this.openAlert(alert);
      });
    }
  }
  async openJobRecord(type: "invoice" | "movement", id: string) {
    await this.run(async () => {
      await this.editRecord(type === "invoice" ? "INVOICE" : "MOVEMENT",
        await this.api.get("/" + (type === "invoice" ? "invoices" : "movements") + "/" + id));
    });
  }
  async loadAssistant() {
    await this.run(async () => {
      this.assistant.set(await this.api.get("/assistant"));
      this.executions.set(await this.api.get("/assistant/executions"));
    });
  }
  async saveModels() {
    await this.run(async () => {
      await this.api.post("/assistant/settings", {
        models: this.assistant().selected,
        crossYearMonths: this.assistant().crossYearMonths,
      });
      this.notice.set("Configuração guardada. Aplica-se às próximas tarefas.");
    });
  }
  async saveWindow() {
    await this.run(async () => {
      await this.api.post("/assistant/settings", {
        crossYearMonths: this.assistant().crossYearMonths,
      });
      await this.api.post("/reconciliation/run");
      await this.refresh();
    });
  }
  async upload(event: Event) {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length) return;
    this.uploading.set(true);
    await this.run(async () => {
      const form = new FormData();
      for (const file of Array.from(input.files!)) form.append("files", file);
      form.append("kind", this.uploadKind);
      if (this.uploadAccount) form.append("accountId", this.uploadAccount);
      await this.api.post("/documents", form);
      this.notice.set(
        "Documentos arquivados. O processamento continua em segundo plano.",
      );
      await this.refresh();
    });
    input.value = "";
    this.uploading.set(false);
  }
  newRecord(kind: string) {
    this.recordKind = kind;
    this.timeline.set([]);
    this.editor.set(
      kind === "INVOICE"
        ? { currency: "EUR", type: "INVOICE", lines: [], categories: [] }
        : {
            accountId: this.uploadAccount || null,
            currency: "EUR",
            direction: "DEBIT",
            kind: "UNCLASSIFIED",
          },
    );
  }
  async editRecord(kind: string, row: any) {
    this.recordKind = kind;
    this.timeline.set([]);
    this.editor.set(structuredClone(row));
    await this.run(async () =>
      this.timeline.set(
        await this.api.get(
          "/audit/" +
            (kind === "INVOICE" ? "invoice" : "movement") +
            "/" +
            row.id,
        ),
      ),
    );
  }
  async saveRecord(validate: boolean) {
    await this.run(async () => {
      const data = { ...this.editor(), validate };
      const resource =
        this.recordKind === "INVOICE" ? "/invoices" : "/movements";
      if (data.id) await this.api.put(resource + "/" + data.id, data);
      else await this.api.post(resource, data);
      this.editor.set(null);
      this.notice.set(
        validate
          ? "Registo validado e saldos atualizados."
          : "Rascunho guardado; fica fora dos saldos.",
      );
      await this.refresh();
    });
  }
  async attach(event: Event) {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length) return;
    await this.run(async () => {
      const form = new FormData();
      form.append("files", input.files![0]);
      form.append("kind", "SUPPORT");
      const docs = await this.api.post("/documents", form);
      this.editor.set({ ...this.editor(), documentId: docs[0].id });
    });
  }
  requestRemoval(kind: string, row: any) {
    this.confirmDelete.set({ ...row, kind });
  }
  async removeRecord() {
    const row = this.confirmDelete();
    if (!row) return;
    await this.run(async () => {
      await this.api.delete(
        (row.kind === "INVOICE" ? "/invoices/" : "/movements/") + row.id,
        { version: row.version },
      );
      this.confirmDelete.set(null);
      await this.refresh();
    });
  }
  async openDocument(id: string, alert: any = null) {
    await this.run(async () => {
      this.activeAlert.set(alert);
      this.reviewDocument.set(await this.api.get("/documents/" + id));
      this.timeline.set(await this.api.get("/documents/" + id + "/attempts"));
    });
  }
  async saveDraft(resolve: boolean) {
    await this.run(async () => {
      const doc = this.reviewDocument();
      this.reviewDocument.set(
        await this.api.put("/documents/" + doc.id + "/draft", {
          version: doc.version,
          draft: doc.draft,
          resolve,
        }),
      );
      this.notice.set(
        resolve
          ? "Dados validados e publicados."
          : "Alterações guardadas. O alerta continua em aberto.",
      );
      if (resolve) this.activeAlert.set(null);
      await this.refresh();
    });
  }
  async reprocess() {
    await this.run(async () => {
      const doc = this.reviewDocument();
      await this.api.post("/documents/" + doc.id + "/reprocess", {
        version: doc.version,
      });
      this.reviewDocument.set(null);
      this.notice.set("Nova extração agendada.");
      await this.refresh();
    });
  }
  async openAlert(alert: any) {
    if (alert.documentId) await this.openDocument(alert.documentId, alert);
    else if (alert.entityType === "invoice") {
      await this.editRecord(
        "INVOICE",
        await this.api.get("/invoices/" + alert.entityId),
      );
    } else if (alert.entityType === "movement") {
      await this.editRecord(
        "MOVEMENT",
        await this.api.get("/movements/" + alert.entityId),
      );
    } else {
      this.navigate("documents");
      this.notice.set(alert.message);
    }
  }
  async repair() {
    await this.run(async () => {
      await this.api.post("/alerts/" + this.activeAlert().id + "/ai");
      this.notice.set(
        "Tentativa de correção autorizada. A proposta ficará disponível neste alerta.",
      );
    });
  }
  async refreshProposal() {
    await this.run(async () => {
      const alerts = await this.api.get<any[]>("/alerts");
      this.activeAlert.set(alerts.find((a) => a.id === this.activeAlert()?.id));
    });
  }
  async applyProposal() {
    await this.run(async () => {
      this.reviewDocument.set(
        await this.api.post("/alerts/" + this.activeAlert().id + "/apply"),
      );
      this.activeAlert.set(null);
      this.notice.set("Proposta aplicada e validada com a tua confirmação.");
      await this.refresh();
    });
  }
  proposalChanges(): any[] {
    const proposal = this.activeAlert()?.proposal?.draft;
    if (!proposal) return [];
    const draft = this.reviewDocument()?.draft ?? {};
    return Object.keys(proposal)
      .filter((k) => JSON.stringify(draft[k]) !== JSON.stringify(proposal[k]))
      .map((k) => ({ field: k, before: draft[k], after: proposal[k] }));
  }
  async retry(job: any) {
    await this.run(async () => {
      await this.api.post("/jobs/" + job.id + "/retry");
      await this.refresh();
    });
  }
  async reconcile() {
    await this.run(async () => {
      await this.api.post("/reconciliation/run");
      await this.refresh();
      this.notice.set("Reconciliação atualizada.");
    });
  }
  async assign(invoice: any, candidate: any = null, reverses: any = null) {
    await this.run(async () => {
      this.assignmentMovements.set(
        (await this.api.get("/movements", { size: 500 })).items.filter((m: any) => !m.excluded && m.reviewStatus !== 'OPEN'),
      );
      this.assignment.set({
        invoiceId: invoice.id,
        movementId: candidate?.movementId ?? "",
        amount: candidate?.amount ?? invoice.remaining,
        reversesId: reverses?.id ?? null,
        evidence: "Confirmado pelo gestor",
        invoice,
      });
    });
  }
  async searchAssignment(text: string) {
    await this.run(async () =>
      this.assignmentMovements.set(
        (await this.api.get("/movements", { q: text, size: 500 })).items.filter((m: any) => !m.excluded && m.reviewStatus !== 'OPEN'),
      ),
    );
  }
  async assignFromMovement(movement: any) {
    await this.run(async () => {
      this.allInvoices.set((await this.api.get("/invoices", { size: 500 })).items.filter((i: any) => !i.excluded && i.reviewStatus !== 'OPEN' && i.remaining !== '0.00'));
      this.assignmentMovements.set([movement]);
      this.assignment.set({ invoiceId: '', movementId: movement.id, amount: movement.remaining,
        reversesId: null, evidence: 'Confirmado pelo gestor', invoice: null, fromMovement: true });
    });
  }
  async searchAssignmentInvoices(text: string) {
    await this.run(async () => this.allInvoices.set((await this.api.get("/invoices", { q: text, size: 500 })).items
      .filter((i: any) => !i.excluded && i.reviewStatus !== 'OPEN' && i.remaining !== '0.00')));
  }
  async allocate() {
    await this.run(async () => {
      await this.api.post("/allocations", this.assignment());
      this.assignment.set(null);
      await this.refresh();
    });
  }
  async undo(allocation: any) {
    await this.run(async () => {
      await this.api.delete("/allocations/" + allocation.id);
      await this.refresh();
    });
  }
  async reverse(allocation: any) {
    const invoice = await this.api.get("/invoices/" + allocation.invoiceId);
    await this.assign(invoice, null, allocation);
  }
  async link(row: any, invoiceId: string) {
    if (!invoiceId) return;
    await this.run(async () => {
      await this.api.post("/history/" + row.id + "/link", { invoiceId });
      await this.refresh();
    });
  }
  async loadLinkOptions() {
    this.allInvoices.set(
      (await this.api.get("/invoices", { size: 500 })).items,
    );
  }
  fromLegacy(row: any) {
    this.newRecord("INVOICE");
    this.editor.set({
      ...this.editor(),
      issuedOn: row.date,
      description: row.description,
      total: row.received,
      documentId: row.documentId,
    });
  }
  async saveCategory(data: any) {
    await this.run(async () => {
      await this.api.post("/categories", {
        ...data,
        aliases:
          typeof data.aliasesText === "string"
            ? data.aliasesText
                .split(";")
                .map((s: string) => s.trim())
                .filter(Boolean)
            : data.aliases,
      });
      if (!data.id) this.newCategory = { name: "", aliasesText: "" };
      await this.refresh();
    });
  }
  async deleteCategory(data: any) {
    await this.run(async () => {
      await this.api.delete("/categories/" + data.id, {
        version: data.version,
      });
      await this.refresh();
    });
  }
  async saveAccount(data: any) {
    await this.run(async () => {
      await this.api.post("/accounts", data);
      if (!data.id) this.newAccount = { name: "", iban: "" };
      await this.refresh();
    });
  }
  newCategory: any = { name: "", aliasesText: "" };
  newAccount: any = { name: "", iban: "" };
  mapping: any = {
    headerRow: 0,
    date: 0,
    description: 1,
    received: 2,
    payment: 3,
    balance: 4,
  };
  async saveMapping(kind: string) {
    await this.run(async () => {
      await this.api.post("/assistant/excel-mapping/" + kind, this.mapping);
      this.notice.set(
        "Mapeamento guardado. Reprocessa o Excel para o aplicar.",
      );
    });
  }
  sourceName(id: string) {
    return (
      this.state().documents.find((d: any) => d.id === id)?.filename ??
      "Documento original"
    );
  }
  invoiceName(id: string) {
    const i = this.state().invoices.find((i: any) => i.id === id);
    return i ? i.number || i.supplier : id.slice(0, 8);
  }
  movementName(id: string) {
    return (
      this.state().movements.find((m: any) => m.id === id)?.description ??
      id.slice(0, 8)
    );
  }
  allocationsFor(id: string) {
    return this.state().allocations.filter((a: any) => a.invoiceId === id);
  }
  candidatesFor(id: string) {
    return this.state()
      .candidates.filter((c: any) => c.invoiceId === id)
      .slice(0, 5);
  }
  aiFor(id: string) {
    return this.state().aiSuggestions.find((s: any) => s.invoiceId === id);
  }
  currentMonth() {
    return this.state().summary;
  }
  title() {
    return this.navigation.find((v) => v[0] === this.view())?.[2] ?? "Conta";
  }
  filteredHistory() {
    return this.state().history.filter(
      (r: any) =>
        (!this.filters.year || r.year === +this.filters.year) &&
        (!this.filters.month || r.month === +this.filters.month) &&
        (!this.filters.q ||
          String(r.description)
            .toLowerCase()
            .includes(this.filters.q.toLowerCase())),
    );
  }
  filteredDocuments() {
    return this.state().documents.filter(
      (d: any) =>
        !this.filters.q ||
        d.filename.toLowerCase().includes(this.filters.q.toLowerCase()),
    );
  }
  /** Keeps review drafts untouched when background progress updates the surrounding lists. */
  private connect() {
    this.events?.close();
    this.progressRevision = "";
    this.events = new EventSource("/api/v1/events");
    this.events.addEventListener("progress", (event: MessageEvent) => {
      const next = JSON.parse(event.data);
      if (
        next.revision !== this.progressRevision ||
        next.jobs !== this.counts().jobs ||
        next.alerts !== this.counts().alerts
      ) {
        this.progressRevision = next.revision;
        this.counts.set(next);
        clearTimeout(this.refreshTimer);
        this.refreshTimer = setTimeout(() => void this.refresh(), 800);
      }
    });
  }
  private async run(operation: () => Promise<unknown>) {
    this.activeOperations.update((n) => n + 1);
    this.error.set("");
    try {
      await operation();
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.activeOperations.update((n) => n - 1);
    }
  }
  ngOnDestroy() {
    this.events?.close();
    clearTimeout(this.refreshTimer);
  }
}
