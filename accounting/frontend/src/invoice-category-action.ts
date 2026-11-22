import { NgTemplateOutlet } from "@angular/common";
import { Component, computed, DestroyRef, inject, input, linkedSignal, output, signal, TemplateRef } from "@angular/core";
import { Api, Classification, errorMessage } from "./api";

@Component({
  selector: "invoice-category-action",
  standalone: true,
  imports: [NgTemplateOutlet],
  template: `
    <ng-template #classifyButton>
      @if (!active()) {
        <button type="button" class="text-button" [disabled]="submitting() || disabled()" (click)="classify()">
          {{ review() && current()?.categories?.length ? "Rever categorias com IA" : "Classificar com IA" }}
        </button>
      }
    </ng-template>
    @if (headerTemplate(); as header) {
      <ng-container [ngTemplateOutlet]="header" [ngTemplateOutletContext]="{ $implicit: classifyButton }" />
    }
    <ng-content />
    <small aria-live="polite">
      {{ status() }}@if (current()?.reason; as reason) { · {{ reason }} }
    </small>
    @if (!headerTemplate()) {
      <ng-container [ngTemplateOutlet]="classifyButton" />
    }
    @if (error()) { <small class="error" role="alert">{{ error() }}</small> }
  `,
})
export class InvoiceCategoryAction {
  private readonly api = inject(Api);
  private readonly destroyRef = inject(DestroyRef);
  readonly invoiceId = input.required<string>();
  readonly classification = input<Classification | null>(null);
  readonly review = input(false);
  readonly disabled = input(false);
  readonly headerTemplate = input<TemplateRef<{ $implicit: TemplateRef<unknown> }> | null>(null);
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
    if (state?.state === "CLASSIFIED") return "Categorias classificadas";
    return state?.state === "REVIEW" ? "Categorias por rever" : "Sem categorias";
  });

  protected async classify() {
    if (this.active() || this.submitting() || this.disabled()) return;
    const previous = this.current();
    this.submitting.set(true);
    this.error.set("");
    try {
      const classification = await this.api.post<Classification>(
        `/invoices/${this.invoiceId()}/categories/classify`,
        { version: previous?.version ?? 0 },
      );
      if (this.destroyRef.destroyed) return;
      const latest = this.current();
      if (latest === previous || classification.version > (latest?.version ?? 0)) {
        this.current.set(classification);
      }
      this.queued.emit();
    } catch (error) {
      if (!this.destroyRef.destroyed) this.error.set(errorMessage(error));
    } finally {
      this.submitting.set(false);
    }
  }
}
