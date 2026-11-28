import { describe, expect, test } from 'vitest';
import {
  monthlyPeriod,
  trainerHoursCsv,
  type HoursExport,
} from '@/features/statistics/trainerHoursExport';
import { trainerHoursPdfDefinition } from '@/features/statistics/trainerHoursPdf';

const counts = {
  seconds: 4501,
  leadSeconds: 2701,
  assistantSeconds: 1800,
  events: 1,
  unconfirmedSeconds: 1800,
  unconfirmedEvents: 1,
};
const data: HoursExport = {
  studioName: 'Studio "Żółw"; Łódź',
  generatedAt: '2026-11-27T12:00:00Z',
  staffId: null,
  report: {
    from: '2026-11-01',
    to: '2026-11-30',
    timeZone: 'Europe/Warsaw',
    totals: counts,
    instructors: [{ id: 'anna', displayName: 'Anna Łącka', active: true, counts, sessions: [] }],
  },
};

describe('calendar month', () => {
  test('handles leap years, December and invalid input without browser-zone shifts', () => {
    expect(monthlyPeriod('2024-02')).toEqual({ from: '2024-02-01', to: '2024-02-29' });
    expect(monthlyPeriod('2026-02')?.to).toBe('2026-02-28');
    expect(monthlyPeriod('2026-12')?.to).toBe('2026-12-31');
    expect(monthlyPeriod('9999-12')?.to).toBe('9999-12-31');
    for (const invalid of ['', '2026-13', '2026-00', '26-01', '2026-1', '2026-11-01']) {
      expect(monthlyPeriod(invalid)).toBeNull();
    }
  });
});

test('CSV preserves Polish text, quoted separators, exact seconds and separate confirmation totals', () => {
  const csv = trainerHoursCsv(data);
  expect(csv.startsWith('\uFEFF')).toBe(true);
  expect(csv).toContain('"Studio ""Żółw""; Łódź"');
  expect(csv).toContain(
    '"Anna Łącka";"0,750278";"0,500000";"0,750278";"0,500000";"1,250278";"4501";"1"',
  );
  expect(csv).toContain('"2026-11-01";"2026-11-30"');
  // No extra total row: summing the exported employee rows must not double their hours.
  expect(csv.trim().split('\r\n')).toHaveLength(2);
});

test.each(['=HYPERLINK("bad")', ' +2+3', '-4', '@SUM(A1)', '\tmalicious', '\rmalicious'])(
  'CSV neutralizes spreadsheet formulas: %s',
  (name) => {
    const csv = trainerHoursCsv({
      ...data,
      report: {
        ...data.report,
        instructors: [{ ...data.report.instructors[0], displayName: name }],
      },
    });
    expect(csv).toContain(`"'${name.replaceAll('"', '""')}"`);
  },
);

test('individual PDF contains every occurrence chronologically, with studio dates across midnight and confirmation state', () => {
  const sessions = Array.from({ length: 21 }, (_, i) => ({
    eventId: String(i),
    kind: 'PERSONAL_TRAINING' as const,
    title: `Trening Łucji ${i}`,
    startsAt: `2026-11-${String(i + 1).padStart(2, '0')}T22:30:00Z`,
    endsAt: `2026-11-${String(i + 2).padStart(2, '0')}T00:00:00Z`,
    role: 'LEAD' as const,
    seconds: 5400,
    confirmed: i > 0,
    rooms: 'Sala Żółta',
  })).reverse();
  const definition = trainerHoursPdfDefinition({
    ...data,
    staffId: 'anna',
    report: { ...data.report, instructors: [{ ...data.report.instructors[0], sessions }] },
  });
  const content = JSON.stringify(definition.content);
  expect(definition.pageOrientation).toBe('portrait');
  for (let i = 0; i < 21; i++) expect(content).toContain(`Trening Łucji ${i}"`);
  expect(content.indexOf('Trening Łucji 0"')).toBeLessThan(content.indexOf('Trening Łucji 20"'));
  expect(content).toContain('23:30–01:00');
  expect(content).toContain('koniec 02.11.2026');
  expect(content).toContain('Bez potwierdzenia');
  expect(content).toContain('Sala Żółta');
});

test('PDF uses only black and gray without ink-heavy filled backgrounds', () => {
  const definition = trainerHoursPdfDefinition(data);
  expect(definition.defaultStyle?.color).toBe('#000000');
  expect(JSON.stringify(definition)).not.toContain('fillColor');
  const colors = JSON.stringify(definition).match(/#[0-9a-f]{6}/gi) ?? [];
  expect(colors.length).toBeGreaterThan(0);
  for (const color of colors) {
    expect(color.slice(1, 3)).toBe(color.slice(3, 5));
    expect(color.slice(3, 5)).toBe(color.slice(5, 7));
  }
});
