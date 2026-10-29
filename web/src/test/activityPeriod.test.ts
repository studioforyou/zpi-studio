import { expect, test } from 'vitest';
import { periodError, periodLength, presetPeriod } from '@/features/accounts/activityPeriod';

test('presets end today and include it', () => {
  expect(presetPeriod('last7', '2026-10-25')).toEqual({ from: '2026-10-19', to: '2026-10-25' });
  expect(presetPeriod('last30', '2026-10-25')).toEqual({ from: '2026-09-26', to: '2026-10-25' });
  expect(presetPeriod('last90', '2026-03-01')).toEqual({ from: '2025-12-02', to: '2026-03-01' });
  expect(presetPeriod('thisMonth', '2026-10-25')).toEqual({ from: '2026-10-01', to: '2026-10-25' });
  expect(presetPeriod('thisYear', '2026-10-25')).toEqual({ from: '2026-01-01', to: '2026-10-25' });
  expect(periodLength(presetPeriod('last30', '2026-10-25'))).toBe(30);
});

test('rejects incomplete, reversed and too long custom periods', () => {
  expect(periodError({ from: '', to: '2026-10-25' })).toBe('incomplete');
  expect(periodError({ from: '2026-10-25', to: '2026-10-24' })).toBe('reversed');
  expect(periodError({ from: '2026-10-25', to: '2026-10-25' })).toBeNull();
  // 2023 and the leap year 2024 together are exactly the 731 allowed days.
  expect(periodError({ from: '2023-01-01', to: '2024-12-31' })).toBeNull();
  expect(periodError({ from: '2023-01-01', to: '2025-01-01' })).toBe('tooLong');
});
