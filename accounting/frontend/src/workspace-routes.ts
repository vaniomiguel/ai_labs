import { Component } from "@angular/core";
import { Routes } from "@angular/router";

export const WORKSPACE_PATHS = {
  reconciliation: "reconciliation",
  invoices: "invoice",
  movements: "movement",
  documents: "document",
  processing: "processing",
  alerts: "alert",
  balances: "balances",
  history: "history",
  settings: "settings",
  assistant: "assistant",
} as const;

export type WorkspaceView = keyof typeof WORKSPACE_PATHS;
export type RecordKind = "INVOICE" | "MOVEMENT";
export type WorkspaceRouteKind = "list" | "login" | "missing" | "new" | "edit" | "categories"
  | "document" | "alert" | "job" | "group" | "assign" | "exclude" | "reverse";

export interface WorkspaceRoute {
  view: WorkspaceView;
  kind: WorkspaceRouteKind;
}

export const DEFAULT_WORKSPACE_URL = "/reconciliation";

// The persistent workspace renders the active page and its dialogs using the route data.
@Component({ selector: "workspace-route", template: "" })
class WorkspaceRouteComponent {}

function page(path: string, view: WorkspaceView, kind: WorkspaceRouteKind = "list") {
  return { path, component: WorkspaceRouteComponent, data: { view, kind } satisfies WorkspaceRoute };
}

export function recordView(kind: RecordKind): "invoices" | "movements" {
  return kind === "INVOICE" ? "invoices" : "movements";
}

export const WORKSPACE_ROUTES: Routes = [
  { path: "", pathMatch: "full", redirectTo: DEFAULT_WORKSPACE_URL },
  page("login", "reconciliation", "login"),
  page("invoice/new", "invoices", "new"),
  page("invoice/:id/edit", "invoices", "edit"),
  page("invoice/:id/categories", "invoices", "categories"),
  page("movement/new", "movements", "new"),
  page("movement/:id/edit", "movements", "edit"),
  page("movement/:id/categories", "movements", "categories"),
  page("document/:id/review", "documents", "document"),
  page("alert/:id/review", "alerts", "alert"),
  page("processing/:id", "processing", "job"),
  page("reconciliation/:id/assign", "reconciliation", "assign"),
  page("reconciliation/:id/exclude", "reconciliation", "exclude"),
  page("reconciliation/:id/reverse", "reconciliation", "reverse"),
  page("reconciliation/:id", "reconciliation", "group"),
  ...Object.entries(WORKSPACE_PATHS).map(([view, path]) => page(path, view as WorkspaceView)),
  page("**", "reconciliation", "missing"),
];
