import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import type { Money } from './passesApi';

export function formatMoney(money: Money): string {
  const amount = (money.amountMinor / 100).toLocaleString('pl-PL', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return `${amount} ${money.currency}`;
}

/** Converts an amount typed in main units (e.g. 120.5) to minor units (12050). */
export function toMinorUnits(amount: number): number {
  return Math.round(amount * 100);
}

export function formatEntries(entriesCount: number | null): string {
  return entriesCount === null ? pl.passes.unlimited : String(entriesCount);
}

export function passErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code !== null) {
    const known = pl.passes.errors[error.code as keyof typeof pl.passes.errors];
    if (known) {
      return known;
    }
  }
  return pl.passes.errors.unknown;
}
