import { signal } from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { Api } from "./api";

export interface InvoiceValidationIssue {
  code: string;
  message: string;
  fields: string[];
}

type EditableValue = string | number | null;

interface InvoiceValidationDraft {
  id?: string;
  issuedOn?: string | null;
  supplier?: string | null;
  supplierTaxId?: string | null;
  number?: string | null;
  currency?: string | null;
  type?: string | null;
  relatedInvoiceId?: string | null;
  net?: EditableValue;
  tax?: EditableValue;
  discount?: EditableValue;
  total?: EditableValue;
  lines?: Partial<Record<"quantity" | "unitPrice" | "net" | "tax" | "discount" | "total", EditableValue>>[];
}

interface InvoiceValidationResult {
  issues: unknown;
}

const RECHECK_DELAY_MS = 250;
const LINE_FIELD_PATH = /^lines\.(\d+)\.(.+)$/;

function isIssue(value: unknown): value is InvoiceValidationIssue {
  return typeof value === "object" && value !== null
    && "code" in value && typeof value.code === "string"
    && "message" in value && typeof value.message === "string"
    && "fields" in value && Array.isArray(value.fields) && value.fields.length > 0
    && value.fields.every(field => typeof field === "string");
}

function readIssues(issues: unknown): InvoiceValidationIssue[] | null {
  return Array.isArray(issues) && issues.every(isIssue) ? issues : null;
}

export function invoiceSaveIssues(error: unknown): InvoiceValidationIssue[] {
  return error instanceof HttpErrorResponse ? readIssues(error.error?.issues) ?? [] : [];
}

export function isInvoiceValidationConcern(code: string): boolean {
  return code === "INVALID_INVOICE" || code === "INVALID_REINCLUDED_RECORD";
}

/** Owns the current unsaved validation state for a single invoice editor. */
export class InvoiceEditorValidation {
  private readonly currentIssues = signal<InvoiceValidationIssue[]>([]);
  readonly issues = this.currentIssues.asReadonly();
  private active = false;
  private sequence = 0;
  private timer?: ReturnType<typeof setTimeout>;

  constructor(private readonly api: Api) {}

  start(draft: InvoiceValidationDraft) {
    this.active = true;
    void this.check(draft, this.invalidate());
  }

  edited(draft: InvoiceValidationDraft, removedLine?: number) {
    if (!this.active) return;
    if (removedLine !== undefined) this.removeLineIssues(removedLine);
    const sequence = this.invalidate();
    this.timer = setTimeout(() => void this.check(draft, sequence), RECHECK_DELAY_MS);
  }

  report(issues: InvoiceValidationIssue[]) {
    this.invalidate();
    this.active = true;
    this.currentIssues.set(issues);
  }

  reset() {
    this.invalidate();
    this.active = false;
    this.currentIssues.set([]);
  }

  private invalidate(): number {
    clearTimeout(this.timer);
    return ++this.sequence;
  }

  private async check(draft: InvoiceValidationDraft, sequence: number) {
    const { id, issuedOn, supplier, supplierTaxId, number, currency, type, relatedInvoiceId,
      net, tax, discount, total, lines } = draft;
    const path = id ? `/invoices/${id}/validation` : "/invoices/validation";
    try {
      const result = await this.api.post<InvoiceValidationResult>(path, {
        issuedOn, supplier, supplierTaxId, number, currency, type, relatedInvoiceId,
        net, tax, discount, total, lines,
      });
      const issues = readIssues(result?.issues);
      if (sequence === this.sequence && issues) this.currentIssues.set(issues);
    } catch {
      // A background failure leaves the last confirmed issues visible. Save reports request errors.
    }
  }

  private removeLineIssues(removedLine: number) {
    this.currentIssues.update(issues => issues.map(issue => ({
      ...issue, fields: issue.fields.flatMap(field => this.shiftLineField(field, removedLine)),
    })).filter(issue => issue.fields.length));
  }

  private shiftLineField(field: string, removedLine: number): string[] {
    const match = LINE_FIELD_PATH.exec(field);
    if (!match) return [field];
    const index = Number(match[1]);
    if (index === removedLine) return [];
    return [index > removedLine ? `lines.${index - 1}.${match[2]}` : field];
  }
}
