import { NgTemplateOutlet } from "@angular/common";
import { Component, computed, DestroyRef, inject, input, linkedSignal, output, signal, TemplateRef } from "@angular/core";
import { Api, Classification, errorMessage } from "./api";
import { InvoiceCategoryReviewPanel } from "./invoice-category-review";

@Component({
  selector: "invoice-category-action",
  standalone: true,
  imports: [NgTemplateOutlet, InvoiceCategoryReviewPanel],
  template: `
    <ng-template #classifyButton>
      @if (!active()) {
        <button type="button" class="text-button" [disabled]="submitting() || disabled()" (click)="request()">
          {{ requestsReview() ? "Rever categorias com IA" : "Classificar com IA" }}
        </button>
      }
    </ng-template>
    @if (headerTemplate(); as header) {
      <ng-container [ngTemplateOutlet]="header" [ngTemplateOutletContext]="{ $implicit: classifyButton }" />
    }
    <ng-content />
    <small aria-live="polite">
      {{ status() }}@if (statusReason(); as reason) { · {{ reason }} }
    </small>
    @if (review() && current()?.review; as report) {
      @if (current(); as state) {
        @if (state.jobId; as jobId) {
          <invoice-category-review [report]="report" [jobId]="jobId" [categories]="state.categories"
            [disabled]="submitting() || disabled()" (approved)="applyReview($event)" />
        }
      }
    }
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
  protected readonly requestsReview = computed(() => this.review() && !!this.current()?.categories.length);
  private readonly isReviewJob = computed(() => this.current()?.jobKind === "REVIEW_INVOICE_CATEGORIES");
  protected readonly active = computed(() => {
    const job = this.current()?.jobState;
    return job === "WAITING" || job === "QUEUED" || job === "RUNNING";
  });
  protected readonly status = computed(() => {
    const state = this.current();
    if (this.isReviewJob()) {
      switch (state?.jobState) {
        case "QUEUED": return "Revisão de categorias agendada";
        case "RUNNING": return "A rever categorias";
        case "WAITING": return "Revisão a aguardar IA";
        case "FAILED": return "Revisão de categorias falhou";
        case "DONE": return state.review ? "Revisão de categorias concluída" : "Pede uma nova revisão de categorias";
      }
    }
    switch (state?.jobState) {
      case "QUEUED": return "Classificação agendada";
      case "RUNNING": return "A classificar";
      case "WAITING": return "A aguardar IA";
      case "FAILED": return "Classificação falhou";
    }
    if (state?.state === "CLASSIFIED") return "Categorias classificadas";
    return state?.state === "REVIEW" ? "Categorias por rever" : "Sem categorias";
  });
  protected readonly statusReason = computed(() => {
    const state = this.current();
    return this.isReviewJob() ? state?.jobError : state?.reason;
  });

  protected request() {
    const action = this.requestsReview() ? "review" : "classify";
    return this.submit(action, { version: this.current()?.version ?? 0 });
  }

  protected applyReview(categoryIds: string[]) {
    const state = this.current();
    if (!state?.jobId || !state.review) return;
    return this.submit("review/apply", { version: state.version, reviewJobId: state.jobId, categoryIds });
  }

  private async submit(action: "review" | "classify" | "review/apply", payload: object) {
    if (this.active() || this.submitting() || this.disabled()) return;
    const previous = this.current();
    this.submitting.set(true);
    this.error.set("");
    try {
      const classification = await this.api.post<Classification>(
        `/invoices/${this.invoiceId()}/categories/${action}`,
        payload,
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
