import type { PassProductKind } from '@/features/passes/passesApi';
import { apiFetch } from '@/shared/api/apiClient';

/** What was sold: amounts in minor units of the report's currency. */
export type SaleCounts = {
  passes: number;
  /** Prices actually paid. */
  revenueMinor: number;
  /** Passes given for free, counted in `passes` too. */
  free: number;
  /** Passes clients bought themselves in the app. */
  online: number;
  /** Passes the studio staff assigned. */
  studio: number;
};

export type PassSalesVariant = {
  variantId: string;
  /** `null` = unlimited entries. */
  entriesCount: number | null;
  validityDays: number;
  /** The variant's list price. */
  priceMinor: number;
  counts: SaleCounts;
};

export type PassSalesProduct = {
  productId: string;
  name: string;
  kind: PassProductKind;
  counts: SaleCounts;
  /** The most passes first. */
  variants: PassSalesVariant[];
};

/** Response of `GET /api/v1/reports/pass-sales`. */
export type PassSalesReport = {
  from: string;
  to: string;
  currency: string;
  totals: SaleCounts;
  /** The period of the same length right before. */
  previous: { from: string; to: string; totals: SaleCounts };
  /** Every kind, in a fixed order, also those not sold. */
  kinds: { kind: PassProductKind; counts: SaleCounts }[];
  /** Products sold, the most passes first. */
  products: PassSalesProduct[];
  /** Studio dates with sales, per kind. */
  days: { date: string; kind: PassProductKind; counts: SaleCounts }[];
};

export const passSalesKeys = {
  all: ['pass-sales'] as const,
  period: (from: string, to: string) => [...passSalesKeys.all, from, to] as const,
};

export function getPassSales(from: string, to: string): Promise<PassSalesReport> {
  const query = new URLSearchParams({ from, to });
  return apiFetch<PassSalesReport>(`/api/v1/reports/pass-sales?${query}`);
}
