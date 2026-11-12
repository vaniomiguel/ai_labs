import { Injectable, inject } from "@angular/core";
import { HttpClient, HttpErrorResponse } from "@angular/common/http";
import { firstValueFrom } from "rxjs";

export interface ReconciliationEntry {
  id: string;
  status: string;
  excluded: boolean;
  invoices: any[];
  movements: any[];
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
}
export interface JobPage {
  items: JobItem[];
  total: number;
  page: number;
  size: number;
  counts: JobCounts;
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
  UNCLASSIFIED: "Por classificar",
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
