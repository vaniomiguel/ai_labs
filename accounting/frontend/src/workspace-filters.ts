import type { WorkspaceView } from "./workspace-routes";

export const EMPTY_ADVANCED_FILTERS = {
  day: null as number | null,
  from: "",
  to: "",
  category: "",
  status: "",
  direction: "",
  min: "",
  max: "",
  time: "",
  participation: "",
};

export type AdvancedFilters = typeof EMPTY_ADVANCED_FILTERS;

export interface FinancialFilters extends AdvancedFilters {
  year: number | null;
  month: string;
  q: string;
  page: number;
}

export type FinancialFilterKey = Exclude<keyof FinancialFilters, "page">;

const PERIOD_FIELDS = ["year", "month"] as const;
const SEARCH_FIELDS = [...PERIOD_FIELDS, "q"] as const;
const INVOICE_FIELDS = [...SEARCH_FIELDS, "day", "from", "to", "category", "status", "min", "max"] as const;
const MOVEMENT_FIELDS = [...INVOICE_FIELDS, "direction", "time"] as const;

export const FINANCIAL_FILTER_FIELDS = {
  invoices: INVOICE_FIELDS,
  movements: MOVEMENT_FIELDS,
  reconciliation: [...MOVEMENT_FIELDS, "participation"],
  balances: PERIOD_FIELDS,
  history: SEARCH_FIELDS,
} satisfies Record<string, readonly FinancialFilterKey[]>;

export type FinancialFilterView = keyof typeof FINANCIAL_FILTER_FIELDS;

export function isFinancialFilterView(view: WorkspaceView): view is FinancialFilterView {
  return Object.hasOwn(FINANCIAL_FILTER_FIELDS, view);
}

export function defaultFinancialFilters(): FinancialFilters {
  return { ...EMPTY_ADVANCED_FILTERS, year: new Date().getFullYear(), month: "", q: "", page: 0 };
}

export function initialFinancialFilters(): Record<FinancialFilterView, FinancialFilters> {
  return {
    invoices: defaultFinancialFilters(),
    movements: defaultFinancialFilters(),
    reconciliation: defaultFinancialFilters(),
    balances: defaultFinancialFilters(),
    history: defaultFinancialFilters(),
  };
}

export function financialQuery(view: FinancialFilterView, filters: FinancialFilters) {
  const criteria = FINANCIAL_FILTER_FIELDS[view].map(key => [key, filters[key]] as const);
  return { ...Object.fromEntries(criteria), page: filters.page };
}

export interface CatalogFilters {
  query: string;
  showArchived: boolean;
}

export const DEFAULT_CATALOG_FILTERS: CatalogFilters = { query: "", showArchived: false };
