import { Component, signal, inject, computed, OnDestroy } from "@angular/core";
import { CommonModule, Location } from "@angular/common";
import { HttpErrorResponse } from "@angular/common/http";
import { ActivatedRouteSnapshot, NavigationEnd, Router, RouterOutlet } from "@angular/router";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { FormsModule } from "@angular/forms";
import { MatButtonModule } from "@angular/material/button";
import { MatSnackBar, MatSnackBarModule } from "@angular/material/snack-bar";
import { A11yModule } from "@angular/cdk/a11y";
import { AiExecutionItem, AlertView, Api, Classification, contextUsagePercent, contextWarningLabels, DocumentAttempt, euros, errorMessage, labels, JobCounts, JobItem, JobPage, ReconciliationEntry, ReconciliationPage, RepairApplyRequest, RepairPreview, ReviewableRecord } from "./api";
import type { AuditEvent, CategoryConfirmation, DeletionImpact, DeletionTarget } from "./api";
import { AppliedFilters } from "./applied-filters";
import { FinancialFilterBar } from "./financial-filters";
import { DEFAULT_CATALOG_FILTERS, defaultFinancialFilters, financialQuery, initialFinancialFilters, isFinancialFilterView } from "./workspace-filters";
import type { FinancialFilters } from "./workspace-filters";
import { FieldsEditor } from "./fields-editor";
import { PdfViewer } from "./pdf-viewer";
import { ReconciliationTable } from "./reconciliation";
import { CategoryTags } from "./category-tags";
import { CategoryEditor } from "./category-editor";
import { InvoiceCategoryAction } from "./invoice-category-action";
import { CategoryManager } from "./category-manager";
import { RepairPanel } from "./repair-panel";
import { DeletionDialog } from "./deletion-dialog";
import { DEFAULT_WORKSPACE_URL, RecordKind, recordView, WORKSPACE_PATHS, WorkspaceRoute, WorkspaceRouteKind, WorkspaceView } from "./workspace-routes";

interface WorkspaceOrigin { url: string; view: WorkspaceView }
interface StagedRecordRepair { alert: AlertView; selections: RepairApplyRequest[] }
interface SuspendedEditor { url: string; draft: ReviewableRecord; repair?: StagedRecordRepair }
type AlertStatusFilter = "OPEN" | "RESOLVED" | "dismissed" | "all";
type AlertAction = "dismiss" | "delete";
type DocumentUploadKind = "INVOICE" | "STATEMENT" | "HISTORY" | "ARCHIVE" | "INVOICE_ARCHIVE";
type WorkspaceJob = Pick<JobItem, "id" | "kind" | "targetId" | "state" | "error">;

function isVisibleOpenAlert(alert: AlertView): boolean {
  return alert.status === "OPEN" && !alert.dismissedAt;
}
interface RecordAlertSource {
  id: string; version: number; kind: string; mediaType: string; draft: Record<string, unknown>;
}
const UUID = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const EMPTY_RECONCILIATION_PAGE: ReconciliationPage = { items: [], total: 0, page: 0, size: 100 };

function classificationHistory(event: AuditEvent) {
  const scheduled = event.action === "REQUEST_AI_CLASSIFICATION";
  const reviewLabels: Record<string, string> = {
    REQUEST_AI_CATEGORY_REVIEW: "Revisão de categorias agendada",
    AI_CATEGORY_REVIEW_RESULT: "Resultado da revisão de categorias",
    APPLY_AI_CATEGORY_REVIEW: "Categorias guardadas após revisão",
  };
  const reviewLabel = reviewLabels[event.action];
  if (!scheduled && event.action !== "AI_CLASSIFICATION_RESULT" && !reviewLabel) return null;
  const snapshot = typeof event.after === "object" && event.after !== null ? event.after : {};
  const jobId = "jobId" in snapshot ? snapshot.jobId : null;
  const reason = "summary" in snapshot ? snapshot.summary : "reason" in snapshot ? snapshot.reason : null;
  const names = "categoryNames" in snapshot ? snapshot.categoryNames : null;
  const needsReview = "state" in snapshot && snapshot.state === "REVIEW";
  return {
    label: reviewLabel ?? (scheduled ? "Classificação agendada"
      : needsReview ? "Categorias por rever" : "Resultado da classificação por IA"),
    jobId: typeof jobId === "string" && UUID.test(jobId) ? jobId : null,
    reason: !scheduled && event.action !== "REQUEST_AI_CATEGORY_REVIEW" && typeof reason === "string" ? reason : null,
    categoryNames: Array.isArray(names) ? names.filter(name => typeof name === "string").join(", ") : "",
  };
}

/** Owner workspace joining reconciliation, document review and auditable financial records. */
@Component({
  selector: "conta-app",
  standalone: true,
  imports: [
    CommonModule,
    RouterOutlet,
    FormsModule,
    MatButtonModule,
    MatSnackBarModule,
    A11yModule,
    DeletionDialog,
    FieldsEditor,
    PdfViewer,
    ReconciliationTable, CategoryTags, CategoryEditor, InvoiceCategoryAction, CategoryManager, RepairPanel, AppliedFilters, FinancialFilterBar,
  ],
  templateUrl: "./app.html",
})
export class AppComponent implements OnDestroy {
  private api = inject(Api);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly snackBar = inject(MatSnackBar);
  private readonly activeOperations = signal(0);
  readonly working = computed(() => this.activeOperations() > 0);
  readonly money = euros;
  readonly labels = labels;
  readonly contextWarningLabels = contextWarningLabels;
  readonly contextUsagePercent = contextUsagePercent;
  readonly user = signal("");
  readonly loading = signal(false);
  readonly error = signal("");
  readonly notice = signal("");
  readonly view = signal<WorkspaceView>("reconciliation");
  readonly routeLoading = signal(false);
  readonly routeMessage = signal("");
  readonly processingAlert = signal<{ message: string; jobId: string | null } | null>(null);
  readonly routeKind = signal<WorkspaceRouteKind>("list");
  readonly routedGroup = signal<ReconciliationEntry | null>(null);
  readonly exclusionGroup = signal<ReconciliationEntry | null>(null);
  private sessionReady = false;
  private routeRequest = 0;
  private suspendedEditor?: SuspendedEditor;
  private groupRefreshRequest = 0;
  readonly state = signal<any>({
    invoices: [],
    movements: [],
    allocations: [],
    candidates: [],
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
    reconciliation: EMPTY_RECONCILIATION_PAGE,
  });
  readonly archiveImportJobs = computed(() => {
    const jobs: WorkspaceJob[] = this.state().jobs;
    return new Map(jobs.filter(job => job.kind === "IMPORT_ZIP").map(job => [job.targetId, job]));
  });
  readonly assistant = signal<any>(null);
  readonly executions = signal<AiExecutionItem[]>([]);
  private executionHistoryRequest = 0;
  readonly counts = signal<{jobs: number; alerts: number; jobStates?: JobCounts}>({ jobs: 0, alerts: 0 });
  readonly alertStatusFilter = signal<AlertStatusFilter>("OPEN");
  readonly visibleAlerts = computed(() => {
    const alerts: AlertView[] = this.state().alerts;
    const status = this.alertStatusFilter();
    if (status === "dismissed") return alerts.filter(alert => alert.dismissedAt);
    return alerts.filter(alert => !alert.dismissedAt && (status === "all" || alert.status === status));
  });
  readonly alertEmptyMessages: Record<AlertStatusFilter, string> = {
    OPEN: "Não existem alertas em aberto.",
    RESOLVED: "Não existem alertas resolvidos.",
    dismissed: "Não existem alertas ocultados.",
    all: "Não existem alertas.",
  };
  readonly alertRemoval = signal<AlertView | null>(null);
  readonly alertActionPending = signal(false);
  readonly alertRemovalError = signal("");
  readonly dismissedAlertDetail = signal<AlertView | null>(null);
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
  readonly processingState = signal("active");
  readonly classificationInvoiceId = signal<string | null>(null);
  readonly processingFilterChips = computed(() => {
    const state = this.processingState();
    const filter = this.processingFilters.find(filter => filter.value === state);
    const chips = filter && state !== "all" ? [{ key: "state", label: `Estado: ${filter.label}` }] : [];
    if (this.classificationInvoiceId()) chips.push({ key: "invoice", label: "Classificação da fatura" });
    return chips;
  });
  processingPage = 0;
  private processingRequest = 0;
  readonly editor = signal<any>(null);
  private editorRequest=0;
  readonly editorClassification = signal<Classification | null>(null);
  private editorClassificationRequest = 0;
  readonly categoryTarget = signal<{type:"invoice"|"movement";record:any}|null>(null);
  readonly reviewDocument = signal<any>(null);
  readonly activeAlert = signal<AlertView | null>(null);
  private readonly invoiceEditRepairAlert = signal<AlertView | null>(null);
  readonly recordRepairAlert = computed(() => this.activeAlert() ?? this.invoiceEditRepairAlert());
  readonly invoiceEditRepairError = signal("");
  readonly showsRecordCategories = computed(() => Boolean(this.editor()?.id)
    && (this.recordKind === "INVOICE" || this.activeAlert()?.status !== "OPEN"));
  readonly categoryConfirmation = computed<CategoryConfirmation | undefined>(() => {
    const classification = this.editorClassification();
    if (!this.showsRecordCategories() || !classification) return;
    const categoryIds = classification.categories
      .filter(category => category.origins.length === 1 && category.origins[0] === "AI")
      .map(category => category.id);
    if (categoryIds.length) return { version: classification.version, categoryIds };
  });
  readonly alertSource = signal<RecordAlertSource | null>(null);
  private stagedRecordRepair?: StagedRecordRepair;
  readonly recordAlertDocument = computed(() => {
    const source = this.alertSource();
    const record = this.editor();
    this.reviewEdits();
    if (!source || !record || !source.draft) return source;
    const draft = structuredClone(source.draft);
    const children = draft[source.kind === "INVOICE" ? "invoices" : "movements"];
    const rows = Array.isArray(children) && children.length ? children : [draft];
    const target = rows.find(row => row.id === record.id);
    if (!target) return source;
    Object.assign(target, record);
    return { ...source, draft };
  });
  readonly repairProgress = signal("");
  private documentRequest=0;
  readonly documentAttempts = signal<DocumentAttempt[]>([]);
  private readonly timeline = signal<AuditEvent[]>([]);
  private timelineRequest = 0;
  readonly historyEntries = computed(() => this.timeline().map(event => ({ event, classification: classificationHistory(event) })));
  readonly assignment = signal<any>(null);
  readonly assignmentMovements = signal<any[]>([]);
  readonly allInvoices = signal<any[]>([]);
  readonly deletionTarget = signal<DeletionTarget | null>(null);
  username = "gestor";
  password = "";
  uploadKind: DocumentUploadKind = "INVOICE";
  uploadAccount = "";
  uploading = signal(false);
  recordKind: RecordKind = "INVOICE";
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
  private readonly financialFilters = signal(initialFinancialFilters());
  readonly filterViews = computed(() => {
    const view = this.view();
    return isFinancialFilterView(view) ? [view] : [];
  });
  readonly filters = computed(() => this.financialFilters()[this.filterViews()[0] ?? "reconciliation"]);
  readonly recordsCurrent = computed(() => this.state().resultFilters?.[this.view()] === this.filters());
  readonly reconciliationPage = computed<ReconciliationPage>(() =>
    this.recordsCurrent() ? this.state().reconciliation : EMPTY_RECONCILIATION_PAGE,
  );
  readonly documentQuery = signal("");
  readonly documentFilterChips = computed(() => {
    const query = this.documentQuery().trim();
    return query ? [{ key: "query", label: `Ficheiro: ${query}` }] : [];
  });
  readonly catalogFilters = signal(DEFAULT_CATALOG_FILTERS);
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
  ] as const;
  private events?: EventSource;
  private progressRevision = "";
  private financialRequest = 0;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  constructor() {
    this.router.events.pipe(takeUntilDestroyed()).subscribe(event => {
      if (event instanceof NavigationEnd && this.sessionReady) void this.applyRoute();
    });
    void this.restore();
  }
  /** Restores the session without reporting a normal first-time sign-in as an error. */
  async restore() {
    try {
      const me = await this.api.get("/me");
      await this.api.get("/csrf");
      this.user.set(me.username);
      this.connect();
    } catch {
      /* The sign-in screen is the expected anonymous state. */
    }
    this.sessionReady = true;
    if (this.router.navigated) await this.applyRoute();
  }
  async login() {
    await this.run(async () => {
      await this.api.signIn(this.username, this.password);
      this.password = "";
      this.user.set((await this.api.get("/me")).username);
      this.connect();
      await this.router.navigateByUrl(this.signInDestination(), { replaceUrl: true });
    });
  }
  async logout() {
    await this.run(async () => {
      await this.api.post("/logout");
      this.events?.close();
      clearTimeout(this.refreshTimer);
      ++this.financialRequest;
      ++this.processingRequest;
      this.financialFilters.set(initialFinancialFilters());
      this.documentQuery.set("");
      this.catalogFilters.set(DEFAULT_CATALOG_FILTERS);
      this.alertStatusFilter.set("OPEN");
      this.processingState.set("active");
      this.classificationInvoiceId.set(null);
      this.processingPage = 0;
      this.processing.set(null);
      this.user.set("");
      await this.router.navigateByUrl("/login", { replaceUrl: true });
    });
  }
  navigate(view: WorkspaceView) {
    void this.router.navigate(["/", WORKSPACE_PATHS[view]]);
  }

  applyFilters(changes: Partial<FinancialFilters>) {
    this.updateFinancialFilters({ ...this.filters(), ...changes, page: 0 });
  }

  clearFilters() {
    this.updateFinancialFilters(defaultFinancialFilters());
  }

  changeFinancialPage(page: number) {
    this.updateFinancialFilters({ ...this.filters(), page });
  }

  private updateFinancialFilters(filters: FinancialFilters) {
    const view = this.view();
    if (!isFinancialFilterView(view)) return;
    this.financialFilters.update(previous => ({ ...previous, [view]: filters }));
    if (view !== "history") void this.refresh();
  }

  private signInDestination() {
    const target = this.router.routerState.snapshot.root.queryParamMap.get("returnTo");
    return target?.startsWith("/") && !target.startsWith("//") && !target.includes("\\")
      && !/^\/login(?:[/?#;]|$)/.test(target) ? target : DEFAULT_WORKSPACE_URL;
  }

  private origin(): WorkspaceOrigin | undefined {
    const origin = this.location.getState() as { workspaceOrigin?: WorkspaceOrigin };
    return origin.workspaceOrigin;
  }

  private openRoute(commands: string[], queryParams?: Record<string, string>) {
    const workspaceOrigin: WorkspaceOrigin = { url: this.router.url, view: this.view() };
    void this.router.navigate(["/", ...commands], { queryParams, state: { workspaceOrigin } });
  }

  closeRoute() {
    const origin = this.origin();
    if (origin && origin.url !== this.router.url) this.location.back();
    else {
      const route = this.router.routerState.snapshot.root.firstChild;
      void this.router.navigateByUrl(this.routeParent(route), { replaceUrl: true });
    }
  }

  private routeParent(snapshot: ActivatedRouteSnapshot | null) {
    const route = snapshot?.data as WorkspaceRoute | undefined;
    if (route?.kind === "new" && snapshot?.queryParamMap.has("historyRowId")) return "/history";
    if (route?.kind === "job" && this.classificationInvoiceId()) {
      return this.router.serializeUrl(this.router.createUrlTree(["/processing"], {
        queryParams: this.classificationJobQuery(),
      }));
    }
    return route ? "/" + WORKSPACE_PATHS[route.view] : DEFAULT_WORKSPACE_URL;
  }

  private async applyRoute() {
    const snapshot = this.router.routerState.snapshot.root.firstChild;
    if (!snapshot) return;
    const route = snapshot.data as WorkspaceRoute;
    const request = this.resetRoute(route.kind);
    if (route.kind !== "categories" && route.view !== "processing" && this.suspendedEditor?.url !== this.router.url) {
      this.suspendedEditor = undefined;
    }
    if (!this.user()) {
      if (route.kind !== "login") {
        await this.router.navigate(["/login"], { queryParams: { returnTo: this.router.url }, replaceUrl: true });
      }
      return;
    }
    if (route.kind === "login") {
      await this.router.navigateByUrl(this.signInDestination(), { replaceUrl: true });
      return;
    }
    this.view.set(this.routeView(route, snapshot));
    if (route.view === "processing") this.applyProcessingScope(snapshot);
    const id = snapshot.paramMap.get("id");
    if (route.kind === "job" && id && UUID.test(id)) this.selectedJobId.set(id);
    void this.refresh(false);
    await this.loadRoute(route, snapshot, id, request);
  }

  private routeView(route: WorkspaceRoute, snapshot: ActivatedRouteSnapshot): WorkspaceView {
    if (["new", "edit", "categories", "document"].includes(route.kind)) {
      const origin = this.origin();
      if (origin && Object.hasOwn(WORKSPACE_PATHS, origin.view)) return origin.view;
    }
    if (route.kind === "new" && snapshot.queryParamMap.has("historyRowId")) return "history";
    return route.view;
  }

  private applyProcessingScope(snapshot: ActivatedRouteSnapshot) {
    const requestedId = snapshot.queryParamMap.get("classificationInvoiceId");
    const invoiceId = requestedId && UUID.test(requestedId) ? requestedId : null;
    if (invoiceId === this.classificationInvoiceId()) return;
    this.classificationInvoiceId.set(invoiceId);
    this.processingPage = 0;
    this.processing.set(null);
    const requestedState = snapshot.queryParamMap.get("state");
    const filter = this.processingFilters.find(item => item.value === requestedState);
    this.processingState.set(filter?.value ?? (invoiceId ? "all" : "active"));
  }

  private resetRoute(kind: WorkspaceRouteKind) {
    ++this.financialRequest;
    ++this.processingRequest;
    ++this.editorRequest;
    ++this.documentRequest;
    this.routeKind.set(kind);
    this.loading.set(false);
    this.jobsLoading.set(false);
    this.routeMessage.set("");
    this.processingAlert.set(null);
    this.routeLoading.set(false);
    this.error.set("");
    this.editor.set(null);
    this.editorClassification.set(null);
    this.reviewDocument.set(null);
    this.activeAlert.set(null);
    this.invoiceEditRepairAlert.set(null);
    this.invoiceEditRepairError.set("");
    this.dismissedAlertDetail.set(null);
    this.alertRemoval.set(null);
    this.alertRemovalError.set("");
    this.alertSource.set(null);
    this.stagedRecordRepair = undefined;
    this.reviewEdits.set(0);
    this.assignment.set(null);
    this.categoryTarget.set(null);
    this.deletionTarget.set(null);
    this.routedGroup.set(null);
    this.exclusionGroup.set(null);
    this.selectedJobId.set(null);
    this.jobDetail.set(null);
    this.timeline.set([]);
    this.documentAttempts.set([]);
    return ++this.routeRequest;
  }

  private async loadRoute(route: WorkspaceRoute, snapshot: ActivatedRouteSnapshot, id: string | null, request: number) {
    if (route.kind === "missing" || (id && !UUID.test(id))) {
      this.routeMessage.set("Página ou registo indisponível.");
      return;
    }
    const kind = route.view === "movements" ? "MOVEMENT" : "INVOICE";
    if (route.kind === "list" || route.kind === "job") return;
    this.routeLoading.set(true);
    try {
      if (route.kind === "new") await this.loadNewRecord(kind, snapshot.queryParamMap.get("historyRowId"), request);
      else if (id) await this.loadRouteTarget(route.kind, kind, id, snapshot.queryParamMap.get("movementId"), request);
    } catch (error) {
      if (request === this.routeRequest) this.routeMessage.set(errorMessage(error));
    } finally {
      if (request === this.routeRequest) this.routeLoading.set(false);
    }
  }

  private async loadRouteTarget(action: WorkspaceRouteKind, kind: RecordKind, id: string, candidateId: string | null, request: number) {
    switch (action) {
      case "edit": return this.loadRecord(kind, id, request);
      case "categories": return this.loadCategories(kind, id, request);
      case "document": return this.loadDocument(id, request);
      case "alert": return this.loadAlert(id, request);
      case "group":
      case "exclude": return this.loadGroup(id, action, request);
      case "assign": return this.loadAssignment(id, candidateId, request);
      case "reverse": return this.loadReversal(id, request);
    }
  }
  /** Reloads server-derived amounts; the browser never calculates financial balances. */
  async refresh(refreshGroup = true) {
    if (refreshGroup && this.routeKind() === "group") void this.refreshGroup();
    void this.refreshRecordClassification();
    const view = this.view();
    if (view === "processing") return this.loadProcessing();
    if (view === "assistant") return this.loadAssistant();

    const request = ++this.financialRequest;
    this.loading.set(true);
    try {
      const [shared, records, summary] = await Promise.all([
        this.loadWorkspaceData(view),
        this.loadActiveRecords(view),
        this.loadFinancialSummary(view),
      ]);
      if (request !== this.financialRequest || this.view() !== view) return;
      const previous = this.state();
      const resultFilters = records ? { ...previous.resultFilters, [view]: records.filters } : previous.resultFilters;
      this.state.set({ ...previous, ...shared, ...records?.data, ...summary, resultFilters });
      this.updateAlertCount(shared.alerts);
      if (!this.uploadAccount && shared.accounts.length) this.uploadAccount = shared.accounts[0].id;
    } catch (error) {
      if (request === this.financialRequest) this.error.set(errorMessage(error));
    } finally {
      if (request === this.financialRequest) this.loading.set(false);
    }
  }

  private async loadWorkspaceData(view: WorkspaceView) {
    const includeArchived = view === "invoices" || view === "movements" || view === "reconciliation";
    const [
      allocations, candidates, years, accounts, categories,
      documents, alerts, jobs, history, imports, aiSuggestions,
    ] = await Promise.all([
      this.api.get("/allocations"),
      this.api.get("/reconciliation/candidates"),
      this.api.get("/balances"),
      this.api.get<{ id: string }[]>("/accounts"),
      this.api.get("/categories", includeArchived ? { archived: true } : {}),
      this.api.get("/documents"),
      this.api.get<AlertView[]>("/alerts"),
      this.api.get<WorkspaceJob[]>("/jobs"),
      this.api.get("/history"),
      this.api.get("/imports"),
      this.api.get("/reconciliation/ai-suggestions"),
    ]);
    return { allocations, candidates, years, accounts, categories, documents, alerts, jobs, history, imports, aiSuggestions };
  }

  private async loadActiveRecords(view: WorkspaceView) {
    if (view === "reconciliation") {
      const filters = this.financialFilters().reconciliation;
      const reconciliation = await this.api.get<ReconciliationPage>("/reconciliation/entries", financialQuery(view, filters));
      return { filters, data: { reconciliation } };
    }
    if (view !== "invoices" && view !== "movements") return null;
    const filters = this.financialFilters()[view];
    const page = await this.api.get<{ items: ReviewableRecord[]; total: number }>("/" + view, financialQuery(view, filters));
    const totalKey = view === "invoices" ? "invoiceTotal" : "movementTotal";
    return { filters, data: { [view]: page.items, [totalKey]: page.total } };
  }

  private async loadFinancialSummary(view: WorkspaceView) {
    if (view !== "reconciliation" && view !== "balances") return { summary: null, summaryPeriod: null };
    const { year, month } = this.financialFilters()[view];
    const summaryPeriod = { year, month };
    const summary = await this.api.get("/reconciliation/summary", summaryPeriod);
    return { summary, summaryPeriod };
  }

  /** Loads the processing page using its own task status and pagination. */
  async loadProcessing() {
    const request = ++this.processingRequest;
    const selectedId = this.selectedJobId();
    this.jobsLoading.set(true);
    try {
      const [page, detail] = await Promise.allSettled([
        this.api.get<JobPage>("/jobs/page", {
          state: this.processingState(), page: this.processingPage, size: 50, ...this.classificationJobQuery(),
        }),
        selectedId ? this.api.get<JobItem>("/jobs/" + selectedId) : Promise.resolve(null),
      ]);
      if (request !== this.processingRequest || this.view() !== "processing") return;
      if (page.status === "rejected") {
        this.error.set(errorMessage(page.reason));
        return;
      }
      if (this.processingPage > 0 && this.processingPage * 50 >= page.value.total) {
        this.processingPage = Math.max(0, Math.ceil(page.value.total / 50) - 1);
        await this.loadProcessing();
        return;
      }
      this.processing.set(page.value);
      this.setJobDetail(detail, selectedId);
      if (!this.classificationInvoiceId()) {
        const counts = page.value.counts;
        this.counts.update((previous) => ({ ...previous, jobs: counts.active, jobStates: counts }));
      }
    } catch (error) {
      if (request === this.processingRequest && this.view() === "processing") this.error.set(errorMessage(error));
    } finally {
      if (request === this.processingRequest) this.jobsLoading.set(false);
    }
  }

  private setJobDetail(result: PromiseSettledResult<JobItem | null>, id: string | null) {
    if (!id) return;
    if (result.status === "rejected") {
      this.jobDetail.set(null);
      this.routeMessage.set(errorMessage(result.reason));
      return;
    }
    const opened = !this.jobDetail();
    this.routeMessage.set("");
    this.jobDetail.set(result.value);
    if (opened) setTimeout(() => {
      if (this.selectedJobId() === id) document.getElementById("job-detail-heading")?.focus();
    });
  }
  filterJobs(state: string) {
    if (state !== this.processingState()) this.processing.set(null);
    this.processingState.set(state);
    this.processingPage = 0;
    void this.loadProcessing();
  }

  clearJobFilters(key?: string) {
    if (this.classificationInvoiceId() && key !== "state") {
      const state = key === "invoice" ? this.processingState() : "all";
      void this.router.navigate(["/processing"], { queryParams: { state } });
    } else this.filterJobs("all");
  }

  jobCount(state: string): number {
    const counts = this.classificationInvoiceId() ? this.processing()?.counts
      : this.counts().jobStates ?? this.processing()?.counts;
    return counts?.[state === "all" ? "total" : state as keyof JobCounts] ?? 0;
  }

  private classificationJobQuery(invoiceId = this.classificationInvoiceId()): Record<string, string> {
    return invoiceId ? { classificationInvoiceId: invoiceId } : {};
  }

  openJob(id: string) {
    this.openRoute(["processing", id], this.view() === "processing" ? this.classificationJobQuery() : undefined);
  }

  openInvoiceClassificationJobs(invoiceId: string, jobId?: string) {
    this.suspendRecordEditor(invoiceId);
    this.openRoute(jobId ? ["processing", jobId] : ["processing"], this.classificationJobQuery(invoiceId));
  }

  openJobTarget(job: JobItem) {
    const target = job.target;
    if (!target.available) return;
    if (target.type === "document") this.openDocument(target.id);
    else if (target.type === "invoice" || target.type === "movement") {
      this.openJobRecord(target.type, target.id);
    } else if (target.type === "alert") {
      this.openAlert(target.id);
    }
  }
  openJobRecord(type: "invoice" | "movement", id: string) {
    this.editRecord(type === "invoice" ? "INVOICE" : "MOVEMENT", { id });
  }
  async loadAssistant() {
    const request = this.routeRequest;
    await this.run(async () => {
      const [assistant] = await Promise.all([
        this.api.get("/assistant"), this.loadExecutionHistory(),
      ]);
      if (request === this.routeRequest) this.assistant.set(assistant);
    });
  }

  private async loadExecutionHistory() {
    const route = this.routeRequest;
    const request = ++this.executionHistoryRequest;
    const executions = await this.api.get<AiExecutionItem[]>("/assistant/executions");
    if (route === this.routeRequest && request === this.executionHistoryRequest) this.executions.set(executions);
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
  async upload(input: HTMLInputElement, kind = this.uploadKind, accountId: string | null = this.uploadAccount) {
    const files = input.files;
    if (this.uploading() || !files?.length) return;
    this.uploading.set(true);
    this.notice.set("");
    try {
      await this.run(async () => {
        const form = new FormData();
        for (const file of Array.from(files)) form.append("files", file);
        form.append("kind", kind);
        if (accountId) form.append("accountId", accountId);
        await this.api.post("/documents", form);
        this.notice.set("Documentos arquivados. O processamento continua em segundo plano.");
        await this.refresh();
      });
    } finally {
      input.value = "";
      this.uploading.set(false);
    }
  }
  newRecord(kind: RecordKind) {
    this.openRoute([WORKSPACE_PATHS[recordView(kind)], "new"]);
  }

  private async loadNewRecord(kind: RecordKind, historyId: string | null, request: number) {
    const history = historyId && kind === "INVOICE" ? await this.historyDraft(historyId) : {};
    if (kind === "MOVEMENT") await this.loadEditorAccounts(request);
    if (request !== this.routeRequest) return;
    this.recordKind = kind;
    this.editor.set(
      kind === "INVOICE"
        ? { currency: "EUR", type: "INVOICE", lines: [], categories: [], ...history }
        : {
            accountId: this.uploadAccount || null,
            currency: "EUR",
            direction: "DEBIT",
            kind: "UNCLASSIFIED",
          },
    );
  }

  private async historyDraft(id: string) {
    const history = await this.api.get<{ id: string; date: string; description: string; received: string; documentId: string }[]>("/history");
    const row = history.find(item => item.id === id);
    if (!row) throw new Error("Linha do histórico indisponível.");
    return { issuedOn: row.date, description: row.description, total: row.received, documentId: row.documentId };
  }
  recordTitle(kind:string,row:any) {
    return (kind==='INVOICE'?row.supplier:row.description) || (row.documentId?this.sourceName(row.documentId):kind==='INVOICE'?'Fornecedor por identificar':'Descrição por identificar');
  }
  editRecord(kind: RecordKind, row: { id: string }) {
    this.openRoute([WORKSPACE_PATHS[recordView(kind)], row.id, "edit"]);
  }

  private fetchRecord(kind: RecordKind, id: string) {
    return this.api.get<ReviewableRecord>(`/${recordView(kind)}/${id}`);
  }

  private async loadRecord(kind: RecordKind, id: string, request: number) {
    const record = await this.fetchRecord(kind, id);
    if (kind === "MOVEMENT") await this.loadEditorAccounts(request);
    if (request !== this.routeRequest) return;
    this.recordKind = kind;
    const suspended = this.suspendedEditor?.url === this.router.url ? this.suspendedEditor : undefined;
    this.suspendedEditor = undefined;
    this.stagedRecordRepair = suspended?.repair;
    this.editorClassification.set(record.classification ?? null);
    this.editor.set(suspended ? {
      ...suspended.draft, categories: record.categories, categoryDetails: record.categoryDetails,
      classification: record.classification,
    } : structuredClone(record));
    const timeline = this.loadRecordTimeline(kind, id);
    if (kind === "INVOICE" && this.routeKind() === "edit") {
      await Promise.all([timeline, this.loadInvoiceEditRepair(record, request)]);
    } else await timeline;
  }

  private async loadInvoiceEditRepair(record: ReviewableRecord, request: number) {
    const documentId = record.documentId;
    if (!documentId || record.manuallyConfirmed || !this.isCurrentInvoiceEditor(record, request)) return;
    try {
      const alerts = await this.api.get<AlertView[]>("/alerts");
      const alert = alerts.find(candidate => candidate.entityType === "invoice" && candidate.entityId === record.id
        && candidate.documentId === documentId && isVisibleOpenAlert(candidate));
      if (!alert || !this.isCurrentInvoiceEditor(record, request)) return;

      const source = await this.api.get<RecordAlertSource>("/documents/" + documentId);
      if (source.kind !== "INVOICE" || !this.isCurrentInvoiceEditor(record, request)) return;
      this.alertSource.set(source);
      this.invoiceEditRepairAlert.set(alert);
    } catch {
      if (this.isCurrentInvoiceEditor(record, request)) {
        this.invoiceEditRepairError.set("Não foi possível carregar a correção por IA.");
      }
    }
  }

  private isCurrentInvoiceEditor(record: ReviewableRecord, request: number) {
    const draft = this.editor();
    return request === this.routeRequest && draft?.id === record.id && draft.documentId === record.documentId;
  }

  private async refreshRecordClassification() {
    const id = this.editor()?.id;
    if (!id || !this.showsRecordCategories()) return;
    if (this.recordKind === "INVOICE" && this.routeKind() === "edit") {
      void this.loadRecordTimeline("INVOICE", id).catch(() => undefined);
    }
    const editorRequest = this.editorRequest;
    const request = ++this.editorClassificationRequest;
    const record = await this.fetchRecord(this.recordKind, id).catch(() => null);
    if (!record || editorRequest !== this.editorRequest || request !== this.editorClassificationRequest) return;
    this.editorClassification.set(record.classification ?? null);
  }

  private async loadRecordTimeline(kind: RecordKind, id: string) {
    const editorRequest = this.editorRequest;
    const request = ++this.timelineRequest;
    const history = await this.api.get<AuditEvent[]>(`/audit/${WORKSPACE_PATHS[recordView(kind)]}/${id}`);
    if (editorRequest === this.editorRequest && request === this.timelineRequest) this.timeline.set(history);
  }

  private async loadEditorAccounts(request: number) {
    if (request !== this.routeRequest || this.state().accounts.length) return;
    const accounts = await this.api.get<{ id: string }[]>("/accounts");
    if (request !== this.routeRequest) return;
    this.state.update(state => ({ ...state, accounts }));
    if (!this.uploadAccount && accounts.length) this.uploadAccount = accounts[0].id;
  }

  openCategories(type: "invoice" | "movement", id: string) {
    this.suspendRecordEditor(id);
    this.openRoute([type, id, "categories"]);
  }

  private suspendRecordEditor(id?: string) {
    const draft: ReviewableRecord | null = this.editor();
    if (draft?.id && (!id || draft.id === id)) {
      this.suspendedEditor = { url: this.router.url, draft, repair: this.stagedRecordRepair };
    }
  }

  private async loadCategories(kind: RecordKind, id: string, request: number) {
    const record = await this.fetchRecord(kind, id);
    if (request === this.routeRequest) this.categoryTarget.set({ type: WORKSPACE_PATHS[recordView(kind)], record });
  }
  private inCurrentPeriod(date: string | null) {
    if (!isFinancialFilterView(this.view())) return true;
    if (!date) return false;
    const filters = this.filters();
    const [year, month, day] = date.split("-").map(Number);
    return (filters.year === null || year === filters.year)
      && (!filters.month || month === Number(filters.month))
      && (filters.day === null || day === filters.day)
      && (!filters.from || date >= filters.from)
      && (!filters.to || date <= filters.to);
  }
  async saveRecord(validate: boolean) {
    if (this.working() || !this.editor()) return;
    const alert = this.activeAlert();
    if (alert?.status === "OPEN") return this.saveRecordAlert(alert, validate);
    const stagedAlert = this.stagedRecordRepair?.alert;
    if (stagedAlert) return this.saveRecordAlert(stagedAlert, validate);
    const request = this.editorRequest;
    const confirmation = validate ? this.categoryConfirmation() : undefined;
    await this.run(async () => {
      const data = { ...this.editor(), validate };
      if (data.id) { delete data.categories; delete data.categoryDetails; delete data.classification; }
      const resource = `/${recordView(this.recordKind)}`;
      if (data.id) await this.putRecordEdit(`${resource}/${data.id}`, data, confirmation);
      else await this.api.post(resource, data);
      if (request !== this.editorRequest) { await this.refresh(); return; }
      this.closeRoute();
      this.showSaveSuccess(
        validate ? "Validar e guardar" : "Guardar rascunho",
        this.recordSaveContext(validate, data.issuedOn || data.bookedOn),
      );
    });
  }

  private async saveRecordAlert(alert: AlertView, resolve: boolean) {
    const request = this.editorRequest;
    const alertEditor = alert.id === this.activeAlert()?.id;
    const selectedAlert = this.recordRepairAlert();
    const revision = selectedAlert?.id === alert.id ? selectedAlert.version : alert.version;
    const confirmation = resolve ? this.categoryConfirmation() : undefined;
    await this.run(async () => {
      const record: ReviewableRecord = this.editor();
      await this.putRecordEdit(`/alerts/${alert.id}/draft`, {
        alertVersion: revision,
        recordVersion: record.version,
        documentVersion: record.documentVersion,
        draft: record,
        resolve,
        repairs: this.stagedRecordRepair?.selections ?? [],
      }, confirmation);
      if (request !== this.editorRequest) { await this.refresh(); return; }
      this.closeRoute();
      if (alertEditor) this.showSaveSuccess(resolve ? "Validar e resolver" : "Guardar sem resolver");
      else this.showSaveSuccess(resolve ? "Validar e guardar" : "Guardar rascunho",
        this.recordSaveContext(resolve, record.issuedOn));
    });
  }

  private async putRecordEdit(path: string, payload: object, categoryConfirmation: CategoryConfirmation | undefined) {
    const request = this.editorRequest;
    try {
      await this.api.put(path, { ...payload, categoryConfirmation });
    } catch (error) {
      if (categoryConfirmation && error instanceof HttpErrorResponse && error.status === 409
          && request === this.editorRequest) {
        await this.refreshRecordClassification();
      }
      throw error;
    }
  }
  private recordSaveContext(validate: boolean, date: string | null): string {
    if (!validate) return "Rascunho guardado; fica fora dos saldos.";
    return this.inCurrentPeriod(date)
      ? "Registo validado e saldos atualizados."
      : "Registo validado. Pertence a outro período e fica fora dos filtros atuais.";
  }
  private showSaveSuccess(action: string, context = "") {
    const message = `${action}: operação concluída com sucesso.${context ? " " + context : ""}`;
    this.snackBar.open(message, "Fechar", {
      duration: 7000,
      horizontalPosition: "center",
      verticalPosition: "bottom",
      politeness: "polite",
    });
  }
  async attach(event: Event) {
    const input = event.target as HTMLInputElement;
    if (this.working() || !input.files?.length) return;
    const request=this.editorRequest;
    await this.run(async () => {
      const form = new FormData();
      form.append("files", input.files![0]);
      form.append("kind", "SUPPORT");
      const docs = await this.api.post("/documents", form);
      if (request !== this.editorRequest) return;
      this.invoiceEditRepairAlert.set(null);
      this.invoiceEditRepairError.set("");
      this.alertSource.set(null);
      this.stagedRecordRepair = undefined;
      this.editor.set({ ...this.editor(), documentId: docs[0].id, documentVersion: docs[0].version });
    });
  }
  requestRemoval(type: DeletionTarget["type"], id: string) {
    this.deletionTarget.set({ type, id });
  }

  async deletionCompleted(impact: DeletionImpact) {
    this.deletionTarget.set(null);
    const action = { INVOICE: "Fatura eliminada", MOVEMENT: "Movimento eliminado", DOCUMENT: "Extração eliminada" };
    const context = impact.upload?.action === "ERASE" ? "A eliminação do ficheiro original foi agendada." : "";
    this.showSaveSuccess(action[impact.record.type], context);
    if (this.editor()?.id === impact.record.id || this.reviewDocument()?.id === impact.record.id) this.closeRoute();
    else await this.refresh();
  }
  openDocument(id: string) { this.openRoute(["document", id, "review"]); }

  private fetchDocumentAttempts(id: string) {
    return this.api.get<DocumentAttempt[]>(`/documents/${id}/attempts`);
  }

  private async loadDocument(id: string, request: number, alert: AlertView | null = null) {
    const [source, attempts] = await Promise.all([
      this.api.get("/documents/" + id), this.fetchDocumentAttempts(id),
    ]);
    if (source.kind === "STATEMENT" || source.kind === "MOVEMENT") await this.loadEditorAccounts(request);
    if (request !== this.routeRequest) return;
    this.activeAlert.set(alert);
    this.reviewDocument.set(source);
    this.documentAttempts.set(attempts);
  }
  async saveDraft(resolve: boolean) {
    const doc = this.reviewDocument();
    if (this.working() || !doc) return;
    const request = this.documentRequest;
    await this.run(async () => {
      await this.api.put("/documents/" + doc.id + "/draft", {
        version: doc.version,
        draft: doc.draft,
        resolve,
      });
      if (request === this.documentRequest) {
        this.closeRoute();
        this.showSaveSuccess(resolve ? "Validar e resolver" : "Guardar sem resolver");
      } else await this.refresh();
    });
  }
  async reprocess() {
    const request = this.documentRequest;
    await this.run(async () => {
      const doc = this.reviewDocument();
      await this.api.post("/documents/" + doc.id + "/reprocess", {
        version: doc.version,
      });
      if (request !== this.documentRequest) return;
      this.notice.set("Nova extração agendada.");
      this.closeRoute();
    });
  }
  openAlert(id: string) { this.openRoute(["alert", id, "review"]); }

  dismissAlert(alert: AlertView) { return this.changeAlert(alert, "dismiss"); }

  requestAlertDeletion(alert: AlertView) {
    this.alertRemovalError.set("");
    this.alertRemoval.set(alert);
  }

  cancelAlertDeletion() {
    if (!this.alertActionPending()) this.alertRemoval.set(null);
  }

  confirmAlertDeletion() {
    const alert = this.alertRemoval();
    if (alert) return this.changeAlert(alert, "delete");
  }

  private async changeAlert(alert: AlertView, action: AlertAction) {
    if (this.alertActionPending()) return;
    const request = this.routeRequest;
    this.alertActionPending.set(true);
    this.alertRemovalError.set("");
    this.error.set("");
    try {
      const updated = await this.mutateAlert(alert, action);
      if (request !== this.routeRequest) return;
      this.replaceAlert(alert.id, updated);
      this.alertRemoval.set(null);
      this.notice.set(action === "dismiss" ? "Alerta ocultado." : "Alerta eliminado permanentemente.");
      await this.refresh();
    } catch (error) {
      if (request !== this.routeRequest) return;
      const message = errorMessage(error);
      if (action === "delete") this.alertRemovalError.set(message);
      else this.error.set(message);
    } finally {
      this.alertActionPending.set(false);
    }
  }

  private async mutateAlert(alert: AlertView, action: AlertAction): Promise<AlertView | null> {
    const path = `/alerts/${alert.id}`;
    const revision = { version: alert.version };
    if (action === "dismiss") return this.api.post<AlertView>(`${path}/dismiss`, revision);
    await this.api.delete(path, revision);
    return null;
  }

  private replaceAlert(id: string, updated: AlertView | null) {
    const alerts: AlertView[] = this.state().alerts;
    const next = updated
      ? alerts.map(alert => alert.id === id ? updated : alert)
      : alerts.filter(alert => alert.id !== id);
    this.state.update(state => ({ ...state, alerts: next }));
    this.updateAlertCount(next);
  }

  private updateAlertCount(alerts: AlertView[]) {
    this.counts.update(counts => ({ ...counts, alerts: alerts.filter(isVisibleOpenAlert).length }));
  }

  private async loadAlert(id: string, request: number) {
    const alert = await this.api.get<AlertView>("/alerts/" + id);
    if (request !== this.routeRequest) return;
    if (alert.dismissedAt) {
      this.dismissedAlertDetail.set(alert);
      return;
    }
    if (alert.entityType === "invoice" || alert.entityType === "movement")
      return this.loadRecordAlert(alert, request);
    if (alert.documentId) return this.loadDocument(alert.documentId, request, alert);
    if (alert.entityType === "job") return this.loadProcessingAlert(alert, request);
    this.routeMessage.set(alert.message);
  }

  private async loadProcessingAlert(alert: AlertView, request: number) {
    this.processingAlert.set({ message: alert.message, jobId: null });
    try {
      const job = await this.api.get<JobItem>(`/jobs/${alert.entityId}`);
      if (request !== this.routeRequest) return;
      this.processingAlert.set({ message: alert.message, jobId: job.id });
      const target = job.target;
      if (!target.available || (target.type !== "invoice" && target.type !== "movement")) return;
      await this.loadRecord(target.type === "invoice" ? "INVOICE" : "MOVEMENT", target.id, request);
    } catch (error) {
      if (!(error instanceof HttpErrorResponse && error.status === 404)) throw error;
    }
  }

  private async loadRecordAlert(alert: AlertView, request: number) {
    const kind = alert.entityType === "invoice" ? "INVOICE" : "MOVEMENT";
    this.activeAlert.set(alert);
    await Promise.all([this.loadRecord(kind, alert.entityId, request), this.loadAlertSource(alert, request)]);
  }

  private async loadAlertSource(alert: AlertView, request: number) {
    if (!alert.documentId) return;
    const source = await this.api.get<RecordAlertSource>("/documents/" + alert.documentId);
    if (request === this.routeRequest) this.alertSource.set(source);
  }

  updateRecordAlert(alert: AlertView) {
    if (alert.id === this.activeAlert()?.id) this.activeAlert.set(alert);
    else if (alert.id === this.invoiceEditRepairAlert()?.id) this.invoiceEditRepairAlert.set(alert);
  }

  startNewRecordAnalysis() {
    // Values already applied to the form stay editable after their proposal is superseded.
    this.stagedRecordRepair = undefined;
  }

  async stageRecordRepair(selection: RepairApplyRequest) {
    const alert = this.recordRepairAlert();
    const request = this.editorRequest;
    const edits = this.reviewEdits();
    if (!alert || this.working()) return;
    await this.run(async () => {
      const preview = await this.api.post<RepairPreview>("/alerts/" + alert.id + "/preview", selection);
      if (request !== this.editorRequest || !preview.canApplyDraft || !preview.accepted.length) return;
      const current = this.recordRepairAlert();
      if (current?.id !== alert.id || selection.proposalId !== (current.proposal?.jobId || current.id)) return;
      if (edits !== this.reviewEdits()) throw new Error("O rascunho foi alterado. Revê e aplica a proposta novamente.");
      const draft = { ...this.editor() };
      for (const change of preview.accepted) draft[change.field] = structuredClone(change.proposed);
      this.stagedRecordRepair = { alert, selections: [...(this.stagedRecordRepair?.selections ?? []), selection] };
      this.editor.set(draft);
      this.draftEdited();
    });
  }
  reviewEdits=signal(0);
  draftEdited(){this.reviewEdits.update(n=>n+1);}
  async repairUpdated(alert: AlertView) {
    const previous = this.activeAlert()?.repair;
    this.activeAlert.set(alert);
    const repair = alert.repair;
    if (!alert.documentId || !repair?.outcome) return;
    if (repair.jobId === previous?.jobId && repair.outcome === previous.outcome) return;

    const request = this.documentRequest;
    const documentId = alert.documentId;
    await this.run(async () => {
      const attempts = await this.fetchDocumentAttempts(documentId);
      if (request === this.documentRequest && this.activeAlert()?.repair?.jobId === repair.jobId) {
        this.documentAttempts.set(attempts);
      }
    });
  }
  async applyProposal(choices:any) {
    await this.run(async () => {
      const request=this.documentRequest;
      const alertId = this.activeAlert()?.id;
      if (!alertId) return;
      const result=await this.api.post("/alerts/" + alertId + "/apply",choices);
      if(request===this.documentRequest){
        const key=(row:any,root:boolean)=>root?'root':String(row.id||row._itemId||'');
        const collection=result.kind==='INVOICE'?'invoices':'movements';
        const before=choices.draft?.[collection]||[choices.draft];
        const after=result.draft?.[collection]||[result.draft];
        for(let n=0;n<after.length;n++) {
          const id=key(after[n],!result.draft?.[collection]);
          const local=before.find((row:any)=>key(row,!choices.draft?.[collection])===id);
          if(local&&!choices.selectedItems.includes(id))Object.assign(after[n],local);
        }
        this.reviewDocument.set(result);
        if(choices.validate)this.activeAlert.set(null);
        else {
          const alert = await this.api.get<AlertView>('/alerts/'+alertId);
          if (request === this.documentRequest) this.activeAlert.set(alert);
        }
      }
      if (request === this.documentRequest) this.notice.set(choices.validate?"Escolhas aplicadas e itens selecionados validados.":"Complementos e escolhas guardados no rascunho. Falta validar os itens alterados.");
      await this.refresh();
    });
  }
  inspectRepair(id: string) {
    this.suspendRecordEditor();
    this.openJob(id);
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
  openGroup(id: string) { this.openRoute(["reconciliation", id]); }
  openExclusion(id: string) { this.openRoute(["reconciliation", id, "exclude"]); }

  private fetchGroup(id: string) {
    return this.api.get<ReconciliationEntry>("/reconciliation/entries/by-record/" + id);
  }

  private async loadGroup(id: string, kind: "group" | "exclude", request: number) {
    const group = await this.fetchGroup(id);
    if (request !== this.routeRequest) return;
    if (kind === "group") this.routedGroup.set(group);
    else if (group.excluded || group.invoices.length + group.movements.length < 2) {
      this.routeMessage.set("A seleção de registos para excluir já não está disponível.");
    } else this.exclusionGroup.set(group);
  }

  private async refreshGroup() {
    const id = this.router.routerState.snapshot.root.firstChild?.paramMap.get("id");
    if (!id || !UUID.test(id) || this.routeLoading()) return;
    const routeRequest = this.routeRequest;
    const request = ++this.groupRefreshRequest;
    try {
      const group = await this.fetchGroup(id);
      if (routeRequest !== this.routeRequest || request !== this.groupRefreshRequest) return;
      this.routedGroup.set(group);
      this.routeMessage.set("");
    } catch (error) {
      if (routeRequest !== this.routeRequest || request !== this.groupRefreshRequest) return;
      this.routedGroup.set(null);
      this.routeMessage.set(errorMessage(error));
    }
  }

  assign(id: string, candidateId?: string) {
    this.openRoute(["reconciliation", id, "assign"], candidateId ? { movementId: candidateId } : undefined);
  }

  private async loadAssignment(id: string, candidateId: string | null, request: number) {
    const group = await this.fetchGroup(id);
    if (request !== this.routeRequest) return;
    const invoice = group.invoices.find(record => record.id === id);
    const movement = group.movements.find(record => record.id === id);
    const record = invoice ?? movement;
    if (!record || group.excluded || record.reviewStatus === "OPEN" || !record.remaining || record.remaining === "0.00") {
      this.routeMessage.set("Este registo já não está disponível para associação.");
      return;
    }
    if (invoice) await this.loadInvoiceAssignment(invoice, candidateId, null, request);
    else if (movement) await this.loadMovementAssignment(movement, request);
  }

  private async loadInvoiceAssignment(invoice: ReviewableRecord, candidateId: string | null, reversesId: string | null, request: number) {
    const [options, candidates] = await Promise.all([
      this.assignmentOptions("movements"),
      candidateId ? this.api.get<{ invoiceId: string; movementId: string; amount: string }[]>("/reconciliation/candidates") : [],
    ]);
    const candidate = candidates.find(item => item.invoiceId === invoice.id && item.movementId === candidateId);
    if (candidate && !options.some(item => item.id === candidate.movementId)) {
      const movement = await this.fetchRecord("MOVEMENT", candidate.movementId);
      if (this.assignmentEligible(movement)) options.unshift(movement);
    }
    if (request !== this.routeRequest) return;
    const suggested = candidate && options.some(item => item.id === candidate.movementId) ? candidate : undefined;
    this.assignmentMovements.set(options);
    this.assignment.set({ invoiceId: invoice.id, movementId: suggested?.movementId ?? "",
      amount: suggested?.amount ?? invoice.remaining, reversesId, evidence: "Confirmado pelo gestor", invoice });
  }

  private async loadMovementAssignment(movement: ReviewableRecord, request: number) {
    const invoices = await this.assignmentOptions("invoices");
    if (request !== this.routeRequest) return;
    this.allInvoices.set(invoices);
    this.assignmentMovements.set([movement]);
    this.assignment.set({ invoiceId: "", movementId: movement.id, amount: movement.remaining,
      reversesId: null, evidence: "Confirmado pelo gestor", invoice: null, fromMovement: true });
  }

  private async assignmentOptions(resource: "invoices" | "movements", q = "") {
    const { items } = await this.api.get<{ items: ReviewableRecord[] }>(`/${resource}`, { q, size: 500 });
    return items.filter(item => this.assignmentEligible(item) && (resource === "movements" || item.remaining !== "0.00"));
  }

  private assignmentEligible(item: ReviewableRecord) { return !item.excluded && item.reviewStatus !== "OPEN"; }

  async searchAssignment(text: string, resource: "invoices" | "movements" = "movements") {
    const request = this.routeRequest;
    await this.run(async () => {
      const records = await this.assignmentOptions(resource, text);
      if (request !== this.routeRequest) return;
      (resource === "movements" ? this.assignmentMovements : this.allInvoices).set(records);
    });
  }
  async allocate() {
    const request = this.routeRequest;
    await this.run(async () => {
      await this.api.post("/allocations", this.assignment());
      if (request === this.routeRequest) this.closeRoute();
      else await this.refresh();
    });
  }
  async undo(allocation: any) {
    await this.run(async () => {
      await this.api.delete("/allocations/" + allocation.id);
      await this.refresh();
    });
  }
  reverse(allocation: { id: string }) {
    this.openRoute(["reconciliation", allocation.id, "reverse"]);
  }

  private async loadReversal(id: string, request: number) {
    const allocations = await this.api.get<{ id: string; reversesId: string | null; invoiceId: string }[]>("/allocations");
    const allocation = allocations.find(item => item.id === id && !item.reversesId);
    if (request !== this.routeRequest) return;
    if (!allocation) {
      this.routeMessage.set("Associação indisponível para estorno.");
      return;
    }
    const invoice = await this.fetchRecord("INVOICE", allocation.invoiceId);
    if (request === this.routeRequest) await this.loadInvoiceAssignment(invoice, null, id, request);
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
  fromLegacy(row: { id: string }) {
    this.openRoute(["invoice", "new"], { historyRowId: row.id });
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
    const { summary, summaryPeriod } = this.state();
    const { year, month } = this.filters();
    return summaryPeriod?.year === year && summaryPeriod?.month === month ? summary : null;
  }
  title() {
    return this.navigation.find((v) => v[0] === this.view())?.[2] ?? "Conta";
  }
  filteredHistory() {
    const { year, month, q } = this.financialFilters().history;
    const query = q.toLowerCase();
    return this.state().history.filter(
      (r: any) =>
        (year === null || r.year === year) &&
        (!month || r.month === Number(month)) &&
        (!query || String(r.description).toLowerCase().includes(query)),
    );
  }
  filteredDocuments() {
    const query = this.documentQuery().trim().toLowerCase();
    return this.state().documents.filter(
      (d: any) => !query || d.filename.toLowerCase().includes(query),
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
        this.repairProgress.set(next.revision);
        this.counts.set(next);
        clearTimeout(this.refreshTimer);
        this.refreshTimer = setTimeout(() => {
          if (this.view() === "assistant") void this.run(() => this.loadExecutionHistory());
          else void this.refresh();
        }, 800);
      }
    });
  }
  private async run(operation: () => Promise<unknown>) {
    const request = this.routeRequest;
    this.activeOperations.update((n) => n + 1);
    this.error.set("");
    try {
      await operation();
    } catch (error) {
      if (request === this.routeRequest) this.error.set(errorMessage(error));
    } finally {
      this.activeOperations.update((n) => n - 1);
    }
  }
  ngOnDestroy() {
    ++this.routeRequest;
    ++this.documentRequest;
    ++this.editorRequest;
    this.events?.close();
    clearTimeout(this.refreshTimer);
  }
}
