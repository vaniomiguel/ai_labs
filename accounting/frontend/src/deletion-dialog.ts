import { A11yModule } from "@angular/cdk/a11y";
import { DatePipe } from "@angular/common";
import { HttpErrorResponse } from "@angular/common/http";
import { Component, computed, inject, input, OnDestroy, OnInit, output, signal } from "@angular/core";
import { MatButtonModule } from "@angular/material/button";
import { RouterLink } from "@angular/router";
import { Api, errorMessage, euros } from "./api";
import type { DeletionImpact, DeletionLink, DeletionRecord, DeletionTarget } from "./api";

@Component({
  selector: "deletion-dialog",
  imports: [A11yModule, DatePipe, MatButtonModule, RouterLink],
  templateUrl: "./deletion-dialog.html",
})
export class DeletionDialog implements OnInit, OnDestroy {
  private readonly api = inject(Api);
  readonly target = input.required<DeletionTarget>();
  readonly closed = output<void>();
  readonly deleted = output<DeletionImpact>();
  readonly impact = signal<DeletionImpact | null>(null);
  readonly loading = signal(true);
  readonly submitting = signal(false);
  readonly error = signal("");
  readonly title = computed(() => ({
    INVOICE: "Eliminar esta fatura?",
    MOVEMENT: "Eliminar este movimento?",
    DOCUMENT: "Eliminar esta extração?",
  })[this.target().type]);
  readonly links = computed(() => this.impact()?.links.filter(link => link.relationship !== "PAYMENT_REFERENCE") ?? []);
  readonly references = computed(() => this.impact()?.links.filter(link => link.relationship === "PAYMENT_REFERENCE") ?? []);
  readonly relationship: Record<DeletionLink["relationship"], string> = {
    PAYMENT: "Associação de pagamento a remover",
    REVERSAL: "Estorno a desvincular",
    PAYMENT_REFERENCE: "Referência sem associação confirmada",
    CREDIT_NOTE: "Nota de crédito a rever",
    ORIGINAL_INVOICE: "Fatura original",
    RELATED_INVOICE: "Referência a remover",
  };
  readonly money = euros;
  private destroyed = false;

  ngOnInit() { void this.loadImpact(); }
  ngOnDestroy() { this.destroyed = true; }

  detailRoute(record: DeletionRecord): string[] {
    return ["/", record.type === "INVOICE" ? "invoice" : "movement", record.id, "edit"];
  }

  cancel() {
    if (!this.submitting()) this.closed.emit();
  }

  async confirm() {
    const impact = this.impact();
    if (!impact || this.loading() || this.submitting()) return;
    this.submitting.set(true);
    this.error.set("");
    try {
      await this.api.confirmDeletion(impact);
      if (!this.destroyed) this.deleted.emit(impact);
    } catch (error) {
      if (!this.destroyed) await this.handleFailure(error);
    } finally {
      this.submitting.set(false);
    }
  }

  private async handleFailure(error: unknown) {
    if (error instanceof HttpErrorResponse && error.status === 409) {
      await this.loadImpact("As consequências da eliminação mudaram. Revê os dados atualizados e confirma novamente.");
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 404) this.impact.set(null);
    this.error.set(errorMessage(error));
  }

  private async loadImpact(message = "") {
    this.loading.set(true);
    this.impact.set(null);
    try {
      const impact = await this.api.deletionImpact(this.target());
      if (!this.destroyed) {
        this.impact.set(impact);
        this.error.set(message);
      }
    } catch (error) {
      if (!this.destroyed) this.error.set(errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }
}
