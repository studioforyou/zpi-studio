import type { PassProductKind } from '@/features/passes/passesApi';
import { pl } from '@/shared/i18n/pl';
import type {
  PassSalesProduct,
  PassSalesReport,
  PassSalesVariant,
  SaleCounts,
} from './passSalesApi';
import { bucketDays, type Bucket } from './statisticsMath';

const texts = pl.statistics.sales;

export type SalesMeasure = 'passes' | 'revenue';
export type SalesGrouping = 'kind' | 'product';

export const noSaleCounts: SaleCounts = {
  passes: 0,
  revenueMinor: 0,
  free: 0,
  online: 0,
  studio: 0,
};

/** An amount in minor units, e.g. `19 412 zł`, with grosze only when there are any. */
export function formatAmount(amountMinor: number, currency: string): string {
  const whole = amountMinor % 100 === 0;
  const amount = (amountMinor / 100).toLocaleString('pl-PL', {
    minimumFractionDigits: whole ? 0 : 2,
    maximumFractionDigits: whole ? 0 : 2,
  });
  return `${amount} ${currency === 'PLN' ? 'zł' : currency}`;
}

/** The value of a measure. */
export function measured(counts: SaleCounts, measure: SalesMeasure): number {
  return measure === 'passes' ? counts.passes : counts.revenueMinor;
}

/** The average price paid for a pass, rounded to whole units (złoty), or `null` without sales. */
export function averageMinor(counts: SaleCounts): number | null {
  return counts.passes > 0 ? Math.round(counts.revenueMinor / counts.passes / 100) * 100 : null;
}

export type RankingRow = { key: string; name: string; kind: PassProductKind; counts: SaleCounts };

/**
 * Kinds or products, the largest first by the measure, then by the other one. Every kind is
 * listed, also those not sold; only sold products exist.
 */
export function salesRanking(
  report: PassSalesReport,
  grouping: SalesGrouping,
  measure: SalesMeasure,
): RankingRow[] {
  const rows: RankingRow[] =
    grouping === 'kind'
      ? report.kinds.map(({ kind, counts }) => ({
          key: kind,
          name: texts.kinds[kind],
          kind,
          counts,
        }))
      : report.products.map((product) => ({
          key: product.productId,
          name: product.name,
          kind: product.kind,
          counts: product.counts,
        }));
  const other: SalesMeasure = measure === 'passes' ? 'revenue' : 'passes';
  return [...rows].sort(
    (a, b) =>
      measured(b.counts, measure) - measured(a.counts, measure) ||
      measured(b.counts, other) - measured(a.counts, other) ||
      a.name.localeCompare(b.name, 'pl'),
  );
}

/** Kinds sold in the period, in the report's order: the series of the chart over time. */
export function soldKinds(report: PassSalesReport): PassProductKind[] {
  return report.kinds.filter(({ counts }) => counts.passes > 0).map(({ kind }) => kind);
}

type KindValues = Record<PassProductKind, number>;

const noKindValues: KindValues = {
  ENTRY_PASS: 0,
  SINGLE_ENTRY: 0,
  PT_PACKAGE: 0,
  TIME_PASS: 0,
  VOUCHER: 0,
};

/** The measure per kind, summed into buckets, earliest first. */
export function salesBuckets(
  report: PassSalesReport,
  bucket: Bucket,
  measure: SalesMeasure,
): { start: string; values: KindValues }[] {
  const days = report.days.map((day) => ({
    date: day.date,
    counts: { ...noKindValues, [day.kind]: measured(day.counts, measure) },
  }));
  return bucketDays(days, bucket, noKindValues).map(({ start, counts }) => ({
    start,
    values: counts,
  }));
}

/** How a variant is sold: `4 wejścia · 30 dni · 180 zł`. */
export function describeVariant(
  product: PassSalesProduct,
  variant: PassSalesVariant,
  currency: string,
): string {
  const entries =
    variant.entriesCount === null
      ? texts.unlimited
      : product.kind === 'PT_PACKAGE'
        ? texts.sessions(variant.entriesCount)
        : texts.entries(variant.entriesCount);
  return texts.variant(entries, variant.validityDays, formatAmount(variant.priceMinor, currency));
}
