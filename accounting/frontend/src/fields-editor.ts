import {
  Component,
  Input,
  Output,
  EventEmitter,
  inject,
  signal,
} from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { MatButtonModule } from "@angular/material/button";
import { MatInputModule } from "@angular/material/input";
import { Api, labels, euros } from "./api";

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
  @Output() changed = new EventEmitter<void>();
  readonly labels = labels;
  readonly money = euros;
  private api = inject(Api);
  invoiceOptions = signal<any[]>([]);
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
  }
  addMovement() {
    this.movements.push({
      bookedOn: null,
      valueOn: null,
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
