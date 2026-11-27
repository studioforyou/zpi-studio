import { pl } from '@/shared/i18n/pl';
import type { TrainerHoursReport } from './trainerHoursApi';

const texts = pl.statistics.hours;

export type HoursExport = {
  report: TrainerHoursReport;
  studioName: string;
  generatedAt: string;
  staffId: string | null;
};

/** Full calendar month in studio dates, independent of the browser's time zone. */
export function monthlyPeriod(month: string) {
  if (!/^[1-9]\d{3}-(0[1-9]|1[0-2])$/.test(month)) return null;
  const from = `${month}-01`;
  const [year, number] = month.split('-').map(Number);
  const lastDay = new Date(Date.UTC(year, number, 0)).getUTCDate();
  return { from, to: `${month}-${lastDay}` };
}

function csvCell(value: string) {
  // Spreadsheet applications may execute a formula even in a quoted CSV cell.
  const safe = /^\s*[=+\-@]/.test(value) || /^[\t\r\n]/.test(value) ? `'${value}` : value;
  return `"${safe.replaceAll('"', '""')}"`;
}

function decimalHours(seconds: number) {
  return (seconds / 3600).toFixed(6).replace('.', ',');
}

/** UTF-8 BOM and semicolons work with Polish Excel settings; exact seconds avoid rounding loss. */
export function trainerHoursCsv({ report, studioName, generatedAt }: HoursExport) {
  const exp = texts.export;
  const header = [
    exp.studio,
    exp.from,
    exp.to,
    texts.instructor,
    ...[texts.lead, texts.assistant, exp.confirmed, texts.unconfirmed, texts.totalShort].map(
      exp.hours,
    ),
    exp.exactSeconds,
    texts.events,
    exp.generated,
  ];
  const rows = [...report.instructors]
    .sort((a, b) => a.displayName.localeCompare(b.displayName, 'pl'))
    .map(({ displayName, counts }) => [
      studioName,
      report.from,
      report.to,
      displayName,
      ...[
        counts.leadSeconds,
        counts.assistantSeconds,
        counts.seconds - counts.unconfirmedSeconds,
        counts.unconfirmedSeconds,
        counts.seconds,
      ].map(decimalHours),
      String(counts.seconds),
      String(counts.events),
      generatedAt,
    ]);
  return (
    '\uFEFF' + [header, ...rows].map((row) => row.map(csvCell).join(';')).join('\r\n') + '\r\n'
  );
}

export function hoursExportFilename(data: HoursExport, extension: 'pdf' | 'csv') {
  // UUID scope keeps names safe on every OS and avoids embedding personal data in filenames.
  return `godziny-${data.report.from.slice(0, 7)}-${data.staffId ?? 'zespol'}.${extension}`;
}

export function downloadHoursCsv(data: HoursExport) {
  const url = URL.createObjectURL(
    new Blob([trainerHoursCsv(data)], { type: 'text/csv;charset=utf-8' }),
  );
  const link = document.createElement('a');
  link.href = url;
  link.download = hoursExportFilename(data, 'csv');
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
