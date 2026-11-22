import {
  Component,
  Input,
  Output,
  EventEmitter,
  HostListener,
  ElementRef,
  computed,
  inject,
  input,
  signal,
} from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { MatButtonModule } from "@angular/material/button";
import { MatInputModule } from "@angular/material/input";
import { Api, labels, euros } from "./api";
import type { InvoiceValidationIssue } from "./invoice-validation";

export interface RecordFieldEdit { removedLine: number }

/** Shared field editor used for manual records and raw extraction drafts. No absent value becomes zero. */
@Component({
  selector: "record-fields",
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatInputModule],
  templateUrl: "./fields-editor.html",
})
export class FieldsEditor {
  @Input({ required: true }) kind = "INVOICE";
  @Input({ required: true }) data: any = {};
  @Input() accounts: any[] = [];
  readonly issues = input<readonly InvoiceValidationIssue[]>([]);
  @Output() changed = new EventEmitter<RecordFieldEdit | void>();
  @HostListener("input") @HostListener("change") edited(){this.changed.emit();}
  readonly labels = labels;
  readonly money = euros;
  readonly invoiceAmounts = [
    { key: "total", label: "Total com IVA", placeholder: "0.00" },
    { key: "net", label: "Base sem IVA", placeholder: "Desconhecida" },
    { key: "tax", label: "IVA", placeholder: "Desconhecido" },
    { key: "discount", label: "Desconto", placeholder: "" },
  ] as const;
  readonly articleAmounts = [
    { key: "quantity", label: "Quantidade" },
    { key: "unitPrice", label: "Preço unitário" },
    { key: "net", label: "Base" },
    { key: "tax", label: "IVA" },
    { key: "discount", label: "Desconto" },
    { key: "total", label: "Total" },
  ] as const;
  private api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly hintsByField = computed(() => {
    const hints = new Map<string, Set<string>>();
    for (const issue of this.issues()) {
      for (const field of issue.fields) {
        const messages = hints.get(field) ?? new Set<string>();
        messages.add(issue.message);
        hints.set(field, messages);
      }
    }
    return hints;
  });
  invoiceOptions = signal<any[]>([]);

  fieldHints(path: string): ReadonlySet<string> | undefined {
    return this.hintsByField().get(path);
  }

  hintId(path: string): string | null {
    return this.hintsByField().has(path) ? `invoice-error-${path.replaceAll(".", "-")}` : null;
  }

  focusFirstInvalid() {
    this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
  }
  get categories() {
    return (this.data.categories ?? []).join("; ");
  }
  set categories(text: string) {
    this.data.categories = text
      .split(/[;,]/)
      .map((s) => s.trim())
      .filter(Boolean);
  }
  get lines(): any[] {
    return this.data.lines ?? (this.data.lines = []);
  }
  get movements(): any[] {
    return this.data.movements ?? (this.data.movements = []);
  }
  get rows(): any[] {
    return this.data.rows ?? (this.data.rows = []);
  }
  addLine() {
    this.lines.push({
      description: null,
      quantity: null,
      unitPrice: null,
      net: null,
      tax: null,
      total: null,
    });
    this.changed.emit();
  }
  removeLine(index: number) {
    this.lines.splice(index, 1);
    this.changed.emit({ removedLine: index });
  }
  addMovement() {
    this.changed.emit();
    this.movements.push({
      bookedOn: null,
      description: null,
      amount: null,
      direction: null,
      currency: "EUR",
      kind: "UNCLASSIFIED",
    });
  }
  async searchInvoices(text: string) {
    this.invoiceOptions.set(
      (await this.api.get("/invoices", { q: text, size: 100 })).items,
    );
  }
}
