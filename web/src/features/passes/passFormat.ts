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

/** Today's date in the browser's time zone, as `yyyy-MM-dd`. */
export function todayIsoDate(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/** Adds days to a `yyyy-MM-dd` date; calendar arithmetic, independent of time zones. */
export function addDays(isoDate: string, days: number): string {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** `2026-11-01` → `01.11.2026`. */
export function formatDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-');
  return `${day}.${month}.${year}`;
}

export function customerPassErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code !== null) {
    const known = pl.customerPasses.errors[error.code as keyof typeof pl.customerPasses.errors];
    if (known) {
      return known;
    }
  }
  return pl.customerPasses.errors.unknown;
}
