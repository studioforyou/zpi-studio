import { pl } from '@/shared/i18n/pl';
import type { BookingOutcomeCounts } from './attendanceReportsApi';
import { chartColors } from './chartColors';
import type { SplitPart } from './SplitBar';
import { formatNumber, formatPercent, ratio } from './statisticsMath';

const texts = pl.statistics.outcomes;

/** What became of the places, in bar order: kept places first, then the cancelled ones. */
export function splitParts(counts: BookingOutcomeCounts): SplitPart[] {
  return [
    {
      key: 'attended',
      label: texts.attended,
      value: counts.attended,
      color: chartColors.attended,
      textColor: '#0b0b0b',
    },
    {
      key: 'absent',
      label: texts.absent,
      value: counts.absent,
      color: chartColors.absent,
      textColor: '#0b0b0b',
    },
    {
      key: 'cancelledLate',
      label: texts.cancelledLate,
      value: counts.cancelledLate,
      color: chartColors.cancelledLate,
      textColor: '#ffffff',
    },
    {
      key: 'cancelledInTime',
      label: texts.cancelledInTime,
      value: counts.cancelledInTime,
      color: chartColors.cancelledInTime,
      textColor: '#0b0b0b',
    },
  ];
}

/** Accessible name of a split bar: every part with its number and share. */
export function splitLabel(parts: SplitPart[]): string {
  const total = parts.reduce((sum, part) => sum + part.value, 0);
  return texts.splitLabel(
    parts
      .map(
        (part) =>
          `${part.label} ${formatNumber(part.value)} (${formatPercent(ratio(part.value, total))})`,
      )
      .join(', '),
  );
}
