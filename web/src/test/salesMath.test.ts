import { expect, test } from 'vitest';
import type { PassSalesReport, SaleCounts } from '@/features/statistics/passSalesApi';
import {
  averageMinor,
  describeVariant,
  formatAmount,
  salesBuckets,
  salesRanking,
} from '@/features/statistics/salesMath';

function counts(values: Partial<SaleCounts>): SaleCounts {
  return { passes: 0, revenueMinor: 0, free: 0, online: 0, studio: 0, ...values };
}

const report: PassSalesReport = {
  from: '2026-10-01',
  to: '2026-10-31',
  currency: 'PLN',
  totals: counts({ passes: 4, revenueMinor: 91000 }),
  previous: { from: '2026-08-31', to: '2026-09-30', totals: counts({}) },
  kinds: [
    { kind: 'ENTRY_PASS', counts: counts({ passes: 2, revenueMinor: 36000 }) },
    { kind: 'SINGLE_ENTRY', counts: counts({ passes: 1, revenueMinor: 5500 }) },
    { kind: 'PT_PACKAGE', counts: counts({ passes: 1, revenueMinor: 49500 }) },
    { kind: 'TIME_PASS', counts: counts({}) },
    { kind: 'VOUCHER', counts: counts({}) },
  ],
  products: [],
  days: [
    { date: '2026-10-05', kind: 'ENTRY_PASS', counts: counts({ passes: 1, revenueMinor: 18000 }) },
    { date: '2026-10-07', kind: 'ENTRY_PASS', counts: counts({ passes: 1, revenueMinor: 18000 }) },
    { date: '2026-10-07', kind: 'PT_PACKAGE', counts: counts({ passes: 1, revenueMinor: 49500 }) },
    { date: '2026-10-13', kind: 'SINGLE_ENTRY', counts: counts({ passes: 1, revenueMinor: 5500 }) },
  ],
};

test('formats amounts with grosze only when there are any', () => {
  expect(formatAmount(1941200, 'PLN')).toBe('19 412 zł');
  expect(formatAmount(5550, 'PLN')).toBe('55,50 zł');
  expect(formatAmount(1000, 'EUR')).toBe('10 EUR');
});

test('averages the price paid per pass in whole złoty', () => {
  expect(averageMinor(counts({ passes: 3, revenueMinor: 100000 }))).toBe(33300);
  expect(averageMinor(counts({}))).toBeNull();
});

test('ranks by the chosen measure, then by the other one', () => {
  expect(salesRanking(report, 'kind', 'passes').map((row) => row.kind)).toEqual([
    'ENTRY_PASS',
    'PT_PACKAGE',
    'SINGLE_ENTRY',
    'TIME_PASS',
    'VOUCHER',
  ]);
  expect(salesRanking(report, 'kind', 'revenue').map((row) => row.kind)).toEqual([
    'PT_PACKAGE',
    'ENTRY_PASS',
    'SINGLE_ENTRY',
    'TIME_PASS',
    'VOUCHER',
  ]);
});

test('sums each kind into weeks starting on Monday', () => {
  expect(salesBuckets(report, 'week', 'revenue')).toEqual([
    {
      start: '2026-10-05',
      values: { ENTRY_PASS: 36000, SINGLE_ENTRY: 0, PT_PACKAGE: 49500, TIME_PASS: 0, VOUCHER: 0 },
    },
    {
      start: '2026-10-12',
      values: { ENTRY_PASS: 0, SINGLE_ENTRY: 5500, PT_PACKAGE: 0, TIME_PASS: 0, VOUCHER: 0 },
    },
  ]);
});

test('describes a price variant with entries, sessions or no limit', () => {
  const variant = { variantId: 'v', validityDays: 30, priceMinor: 18000, counts: counts({}) };
  const product = (kind: 'ENTRY_PASS' | 'PT_PACKAGE' | 'TIME_PASS') => ({
    productId: 'p',
    name: 'Karnet',
    kind,
    counts: counts({}),
    variants: [],
  });
  expect(describeVariant(product('ENTRY_PASS'), { ...variant, entriesCount: 1 }, 'PLN')).toBe(
    '1 wejście · 30 dni · 180 zł',
  );
  expect(describeVariant(product('PT_PACKAGE'), { ...variant, entriesCount: 5 }, 'PLN')).toBe(
    '5 sesji · 30 dni · 180 zł',
  );
  expect(describeVariant(product('TIME_PASS'), { ...variant, entriesCount: null }, 'PLN')).toBe(
    'bez limitu · 30 dni · 180 zł',
  );
});
