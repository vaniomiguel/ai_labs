import { Injectable, inject } from "@angular/core";
import { HttpClient, HttpErrorResponse } from "@angular/common/http";
import { firstValueFrom } from "rxjs";

export interface RepairTask {
  jobId: string; state: "QUEUED" | "RUNNING" | "WAITING" | "DONE" | "FAILED";
  outcome: "READY" | "REVIEW_REQUIRED" | "NO_CHANGES" | "STALE" | null;
  sourceVersion: number; error: string | null; attempts: number;
  canApply: boolean; canApplyDraft: boolean; canValidate: boolean; blockingIssues: string[];
  context: { model: string; vision: boolean; textComplete: boolean; textCharacters: number; pages: number[] } | null;
}
export interface FieldDecision {
  origin: "AUTOMATIC" | "HUMAN_EDIT" | "HUMAN_CONFIRMED" | "HUMAN_ACCEPTED_AI" | "HUMAN_LEGACY";
  protected: boolean; actor: string; at: string; proposalId: string | null;
}
export interface FieldSuggestion {
  key: string; itemId: string; itemIndex: number; proposalIndex?: number; field: string;
  current: any; proposed: any; protected: boolean; kind: "COMPLEMENT" | "CONFLICT";
}
export interface RepairApplyRequest {
  documentVersion: number; proposalId: string; draft: any;
  acceptedFields: string[]; selectedItems: string[]; validate: boolean;
}
export interface RepairPreview {
  changes: FieldSuggestion[]; blockingIssues: string[]; canApplyDraft: boolean; canValidate: boolean;
  accepted: FieldSuggestion[];
}

export interface AlertConcern { code: string; message: string }

export interface AlertView {
  id: string; version: number; documentId: string | null; status: string;
  message: string; code: string; entityType: string; entityId: string;
  concerns?: AlertConcern[]; proposal: any; repair: RepairTask | null;
  resolvedBy: string | null; resolvedAt: string | null;
  dismissedAt: string | null;
}

export interface DocumentAttempt {
  id: string;
  createdAt: string;
  engine: string;
  evidence: unknown;
  prompts?: { system: string; user: string }[];
}

export interface DeletionTarget { type: "INVOICE" | "MOVEMENT" | "DOCUMENT"; id: string }
export interface DeletionRecord extends DeletionTarget { version: number; label: string }
export interface DeletionLink {
  record: DeletionRecord;
  relationship: "PAYMENT" | "REVERSAL" | "PAYMENT_REFERENCE" | "ORIGINAL_INVOICE" | "RELATED_INVOICE" | "CREDIT_NOTE";
  allocationId: string | null;
  amount: string | null;
}
export interface DeletionImpact {
  record: DeletionRecord;
  links: DeletionLink[];
  balances: { record: DeletionRecord; date: string; change: string }[];
  alerts: { id: string; version: number; message: string }[];
  historicalLinks: number;
  upload: { id: string; filename: string; action: "KEEP" | "REMOVE_ROW" | "ERASE" } | null;
  impactToken: string;
}

const deletionResources = { INVOICE: "invoices", MOVEMENT: "movements", DOCUMENT: "documents" } as const;

export interface ExpenseCategory {
  id: string; version: number; name: string; path: string; aliases: string[];
  parentId: string | null; mergedIntoId: string | null; description: string | null;
  origin: string; protected: boolean; archived: boolean; usage: number;
  referenceCode: string | null; referenceName: string | null;
  referenceVersion: string | null; referenceSource: string | null;
}
export interface CategoryDetail {
  id: string; name: string; path: string; archived: boolean; origins: string[];
  evidence: { origin: string; reason: string | null; invoiceId?: string; documentId?: string | null; executionId?: string | null }[];
}
export interface InvoiceCategoryReview {
  summary: string;
  assessments: {
    categoryId: string;
    fit: "GOOD_FIT" | "POOR_FIT" | "INSUFFICIENT_EVIDENCE";
    reason: string;
  }[];
  suggestions: { categoryId: string; path: string; reason: string; evidence: string; replacesCategoryIds: string[] }[];
}
export interface Classification {
  version: number; state: string; reason: string | null;
  jobId?: string | null;
  jobState?: "QUEUED" | "RUNNING" | "WAITING" | "FAILED" | "DONE" | null;
  jobKind?: "CLASSIFY_INVOICE" | "CLASSIFY_MOVEMENT" | "REVIEW_INVOICE_CATEGORIES" | null;
  jobError?: string | null;
  review?: InvoiceCategoryReview | null;
  categories: CategoryDetail[]; rejectedCategoryIds: string[]; catalogRevision: number;
}

export interface AuditEvent {
  id: string;
  actor: string;
  action: string;
  createdAt: string;
  after: unknown;
}

export interface CategoryConfirmation {
  version: number;
  categoryIds: string[];
}

export interface ReviewableRecord {
  id: string; version: number; documentId: string | null;
  documentVersion: number | null; fieldProvenance: Record<string, FieldDecision>; reviewStatus: string; reviewIssues: string[];
  remaining: string | null;
  manuallyConfirmed: boolean;
  categoryDetails: CategoryDetail[];
  classification: Classification;
  [field: string]: any;
}

export interface ReconciliationEntry {
  id: string;
  status: string;
  excluded: boolean;
  pendingReview: boolean;
  invoices: ReviewableRecord[];
  movements: ReviewableRecord[];
  allocations: any[];
  differences: { dates: boolean; amounts: boolean };
  invoiceTotal: string;
  debitTotal: string;
  creditTotal: string;
  balanceImpact: string | null;
  periodImpact: string | null;
  spansPeriods: boolean;
  firstDate: string | null;
  lastDate: string | null;
}
export interface ReconciliationPage {
  items: ReconciliationEntry[];
  total: number;
  page: number;
  size: number;
}
export interface ExclusionItem {
  type: "invoice" | "movement";
  id: string;
  version: number;
}
export interface ExclusionRequest {
  excluded: boolean;
  items: ExclusionItem[];
  reason?: string;
}

export const contextWarningLabels = {
  NEAR_CONTEXT_LIMIT: "Contexto próximo do limite",
  CONTEXT_LIMIT_REACHED: "Limite de contexto atingido",
  CONTEXT_REJECTED: "Pedido recusado por exceder o limite de contexto",
  OUTPUT_LIMIT_REACHED: "Resposta interrompida no limite de geração",
  RESPONSE_INTERRUPTED: "Resposta interrompida pelo modelo",
} as const;

export interface AiContextUsage {
  promptTokens: number | null;
  responseTokens: number | null;
  contextTokens: number;
  capacitySource: "CONFIGURED" | "RUNNING_MODEL";
  doneReason: string | null;
  warning: keyof typeof contextWarningLabels | null;
}

export function contextUsagePercent(usage: AiContextUsage): number | null {
  if (usage.promptTokens == null || usage.responseTokens == null) return null;
  return Math.floor(100 * (usage.promptTokens + usage.responseTokens) / usage.contextTokens);
}

export interface AiExecutionItem {
  id: string;
  task: string;
  model: string;
  digest: string;
  promptVersion: string;
  durationMs: number;
  outcome: string;
  createdAt: string;
  response: string | null;
  error: string | null;
  usage: AiContextUsage | null;
}

interface JobContextWarning {
  model: string;
  createdAt: string;
  usage: AiContextUsage & { warning: keyof typeof contextWarningLabels };
}

export interface JobCounts {
  QUEUED: number;
  RUNNING: number;
  WAITING: number;
  DONE: number;
  FAILED: number;
  active: number;
  total: number;
}
export interface JobTarget {
  type: "invoice" | "movement" | "document" | "allocation" | "alert" | "unknown";
  id: string;
  label: string;
  available: boolean;
  documentId: string | null;
  invoiceId?: string;
  movementId?: string;
  invoiceAvailable?: boolean;
  movementAvailable?: boolean;
  amount?: string;
  active?: boolean;
  evidence?: string;
}
export interface JobItem {
  id: string;
  kind: string;
  targetId: string;
  targetVersion: number;
  state: string;
  attempts: number;
  error: string | null;
  createdAt: string;
  updatedAt: string;
  availableAt: string | null;
  target: JobTarget;
  contextWarning?: JobContextWarning | null;
}
export interface JobPage {
  items: JobItem[];
  total: number;
  page: number;
  size: number;
  counts: JobCounts;
  recentContextWarnings?: { jobId: string; kind: string; warning: JobContextWarning }[];
}

/** Same-origin API client. Angular supplies XSRF headers from the authenticated cookie. */
@Injectable({ providedIn: "root" })
export class Api {
  private readonly http = inject(HttpClient);
  get<T = any>(path: string, params: Record<string, any> = {}): Promise<T> {
    return firstValueFrom(
      this.http.get<T>("/api/v1" + path, {
        params: Object.fromEntries(
          Object.entries(params).filter(
            ([, v]) => v !== "" && v !== null && v !== undefined,
          ),
        ),
      }),
    );
  }
  post<T = any>(path: string, body: unknown = {}): Promise<T> {
    return firstValueFrom(this.http.post<T>("/api/v1" + path, body));
  }
  put<T = any>(path: string, body: unknown): Promise<T> {
    return firstValueFrom(this.http.put<T>("/api/v1" + path, body));
  }
  delete(path: string, params: Record<string, any> = {}): Promise<unknown> {
    return firstValueFrom(this.http.delete("/api/v1" + path, { params }));
  }

  deletionImpact(target: DeletionTarget): Promise<DeletionImpact> {
    return this.get<DeletionImpact>(`/${deletionResources[target.type]}/${target.id}/deletion-impact`);
  }

  confirmDeletion({ record, impactToken }: DeletionImpact): Promise<unknown> {
    return this.delete(`/${deletionResources[record.type]}/${record.id}`, { version: record.version, impactToken });
  }
  /** Login uses the encoding expected by Spring's session authentication filter. */
  async signIn(username: string, password: string) {
    await this.get("/csrf");
    await firstValueFrom(
      this.http.post(
        "/api/v1/session",
        new URLSearchParams({ username, password }).toString(),
        { headers: { "Content-Type": "application/x-www-form-urlencoded" } },
      ),
    );
    await this.get("/csrf");
  }
  async original(id: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get("/api/v1/documents/" + id + "/original", {
        responseType: "blob",
      }),
    );
  }
}

/** Displays decimal strings without using them in floating-point financial calculations. */
export function euros(value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  const [whole, decimal = "00"] = String(value).split(".");
  return (
    whole.replace(/\B(?=(\d{3})+(?!\d))/g, " ") +
    "," +
    decimal.padEnd(2, "0") +
    " €"
  );
}
export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse)
    return (
      error.error?.detail ??
      error.error?.message ??
      (error.status === 401
        ? "Inicia sessão para continuar."
        : "Não foi possível concluir a operação.")
    );
  return error instanceof Error ? error.message : String(error);
}
export const labels: Record<string, string> = {
  PDFBOX: "Texto do PDF",
  PDFBOX_PARTIAL: "Leitura parcial",
  DOCLING_TESSERACT: "OCR",
  APACHE_POI: "Excel",
  EXTRACT: "Extração",
  IMPORT_ZIP: "Importação de arquivo",
  REVIEW_INVOICE: "Revisão de fatura",
  REVIEW_MOVEMENT: "Revisão de movimento",
  REVIEW_ALLOCATION: "Revisão de associação",
  REVIEW_DOCUMENT: "Revisão de documento",
  AI_EXTRACT_DOCUMENT: "Extração com IA",
  REPAIR_ALERT: "Proposta de correção",
  NORMALIZE_CATEGORIES: "Normalização de categorias",
  CLASSIFY_INVOICE: "Categorias da fatura",
  REVIEW_INVOICE_CATEGORIES: "Revisão das categorias da fatura",
  CLASSIFY_MOVEMENT: "Categorias do movimento",
  REFINE_CATEGORIES: "Evolução do catálogo de categorias",
  SUGGEST_RECONCILIATION: "Sugestões de reconciliação",
  OPEN: "Em aberto",
  AUTOMATIC: "Automático",
  CORRECTED: "Corrigido",
  EDITED: "Editado",
  MANUAL: "Manual",
  PROCESSING: "Em processamento",
  PARTIAL: "Parcial",
  RECONCILED: "Reconciliado",
  EXCLUDED: "Excluído do saldo",
  PENDING_REVIEW: "Por validar",
  UNMATCHED_INVOICE: "Sem movimento",
  UNMATCHED_MOVEMENT: "Sem fatura",
  MATCHED: "Correspondência",
  MATCHED_DIFFERENCES: "Com diferenças",
  RESOLVED: "Resolvido",
  QUEUED: "Em fila",
  RUNNING: "A processar",
  DONE: "Concluído",
  FAILED: "Falhou",
  WAITING: "A aguardar modelo",
  RULES: "Regras",
  AI: "IA",
  DEBIT: "Débito",
  CREDIT: "Crédito",
  UNCLASSIFIED: "Natureza não definida",
  INVOICE_PAYMENT: "Pagamento de fatura",
  REFUND: "Reembolso de fornecedor",
  REIMBURSEMENT: "Reembolso ao gestor",
  ADVANCE: "Adiantamento ao gestor",
  RETURN: "Devolução à empresa",
  OTHER: "Outro",
  INTERNAL_TRANSFER: "Transferência interna",
  INVOICE: "Fatura",
  CREDIT_NOTE: "Nota de crédito",
  STATEMENT: "Extrato",
  HISTORY: "Excel histórico",
  ARCHIVE: "Arquivo ZIP",
  SUPPORT: "Suporte",
};
