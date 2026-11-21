import { Component, computed, inject, input, linkedSignal, output, signal } from "@angular/core";
import { Api, Classification, errorMessage } from "./api";

@Component({
  selector: "invoice-category-action",
  standalone: true,
  template: `
    <small aria-live="polite">
      {{ status() }}@if (current()?.reason; as reason) { · {{ reason }} }
    </small>
    @if (!active()) {
      <button type="button" class="text-button" [disabled]="submitting() || disabled()" (click)="classify()">
        Classificar com IA
      </button>
    }
    @if (error()) { <small class="error" role="alert">{{ error() }}</small> }
  `,
})
export class InvoiceCategoryAction {
  private readonly api = inject(Api);
  readonly invoiceId = input.required<string>();
  readonly classification = input<Classification | null>(null);
  readonly disabled = input(false);
  readonly queued = output<void>();
  protected readonly current = linkedSignal(() => this.classification());
  protected readonly submitting = signal(false);
  protected readonly error = signal("");
  protected readonly active = computed(() => {
    const job = this.current()?.jobState;
    return job === "WAITING" || job === "QUEUED" || job === "RUNNING";
  });
  protected readonly status = computed(() => {
    const state = this.current();
    switch (state?.jobState) {
      case "QUEUED": return "Classificação agendada";
      case "RUNNING": return "A classificar";
      case "WAITING": return "A aguardar IA";
      case "FAILED": return "Classificação falhou";
    }
    return state?.state === "REVIEW" ? "Categorias por rever" : "Sem categorias";
  });

  protected async classify() {
    if (this.active() || this.submitting() || this.disabled()) return;
    this.submitting.set(true);
    this.error.set("");
    try {
      const classification = await this.api.post<Classification>(
        `/invoices/${this.invoiceId()}/categories/classify`,
        { version: this.current()?.version ?? 0 },
      );
      this.current.set(classification);
      this.queued.emit();
    } catch (error) {
      this.error.set(errorMessage(error));
    } finally {
      this.submitting.set(false);
    }
  }
}
