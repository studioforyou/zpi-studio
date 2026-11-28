import type { Content, TableCell, TDocumentDefinitions } from 'pdfmake/interfaces';
import { formatMoney } from '@/features/passes/passFormat';
import { formatDate, formatMonth, formatTime, toZoned } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import { formatWorkedTime } from './statisticsMath';
import { csvDocument, downloadCsv } from './trainerHoursExport';
import { exportPdfDocument } from './trainerHoursPdf';
import type { TrainerSettlement } from './trainerSettlementsApi';

const texts = pl.statistics.settlements;
const hours = pl.statistics.hours;

export function settlementsCsv(rows: TrainerSettlement[], generatedAt: string) {
  return csvDocument([
    [
      hours.export.studio,
      texts.month,
      texts.employee,
      texts.shortHours + ' [h]',
      texts.hours + ' [s]',
      texts.unconfirmedIncluded,
      texts.amount,
      texts.paid,
      texts.remaining,
      'Waluta',
      texts.status,
      hours.export.generated,
    ],
    ...rows.map(({ summary: s, revisions }) => [
      revisions[0].snapshot.studioName,
      s.month,
      s.displayName,
      (s.countedSeconds / 3600).toFixed(6).replace('.', ','),
      String(s.countedSeconds),
      s.includeUnconfirmed ? 'Tak' : 'Nie',
      ...[s.amount, s.paid, s.remaining].map((m) =>
        (m.amountMinor / 100).toFixed(2).replace('.', ','),
      ),
      s.amount.currency,
      s.amount.amountMinor === 0 ? texts.zeroAmount : texts.statuses[s.status],
      generatedAt,
    ]),
  ]);
}

function table(
  labels: string[],
  rows: TableCell[][],
  widths: ('*' | 'auto' | number)[],
  title?: string,
): Content {
  const heading: TableCell[][] = title
    ? [
        [
          { text: title, bold: true, colSpan: labels.length, margin: [0, 12, 0, 4] },
          ...labels.slice(1).map(() => ({ text: '' })),
        ],
      ]
    : [];
  return {
    table: {
      headerRows: title ? 2 : 1,
      keepWithHeaderRows: 1,
      dontBreakRows: true,
      widths,
      body: [...heading, labels.map((text) => ({ text, bold: true })), ...rows],
    },
    layout: {
      vLineWidth: () => 0,
      hLineWidth: (line) => (line < (title ? 2 : 1) ? 0 : line === (title ? 2 : 1) ? 0.5 : 0.25),
      hLineColor: (line) => (line === (title ? 2 : 1) ? '#888888' : '#d8d8d8'),
      paddingTop: () => 5,
      paddingBottom: () => 5,
    },
  };
}

export function settlementsPdf(
  rows: TrainerSettlement[],
  generatedAt: string,
  individual: boolean,
): TDocumentDefinitions {
  const first = rows[0];
  const zone = first.revisions[0].snapshot.report.timeZone;
  const instant = (value: string) =>
    new Intl.DateTimeFormat('pl-PL', {
      timeZone: zone,
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(value));
  const content: Content[] = [
    { text: first.revisions[0].snapshot.studioName, fontSize: 10, margin: [0, 0, 0, 6] },
    { text: individual ? texts.individualTitle : texts.exportTitle, fontSize: 16, bold: true },
    {
      text: `${formatMonth(first.summary.month + '-01')}${individual ? ` · ${first.summary.displayName}` : ''}`,
      margin: [0, 6, 0, 8],
      fontSize: 11,
    },
    {
      text: `${hours.export.generated}: ${instant(generatedAt)}`,
      color: '#555555',
      fontSize: 8,
      margin: [0, 0, 0, 14],
    },
  ];
  if (individual) {
    const { summary: s, revisions, payments } = first;
    const saved = revisions[0];
    content.push(
      {
        text: s.amount.amountMinor === 0 ? texts.zeroAmount : texts.statuses[s.status],
        margin: [0, 0, 0, 7],
      },
      {
        text: `${texts.amount}: ${formatMoney(s.amount)}   |   ${texts.paid}: ${formatMoney(s.paid)}   |   ${texts.remaining}: ${formatMoney(s.remaining)}`,
        bold: true,
        margin: [0, 0, 0, 8],
      },
      {
        text: `${texts.hours}: ${formatWorkedTime(s.countedSeconds)}${s.unconfirmedSeconds > 0 ? `. ${texts.unconfirmed(formatWorkedTime(s.unconfirmedSeconds), s.includeUnconfirmed)}` : ''}`,
        margin: [0, 0, 0, 12],
      },
      {
        text: `${texts.savedOn(instant(saved.createdAt))} · ${saved.actor}`,
        fontSize: 8,
        color: '#555555',
        margin: [0, 0, 0, 10],
      },
      table(
        [hours.date, hours.session, hours.role, hours.time, texts.confirmation],
        [...saved.snapshot.report.instructors[0].sessions]
          .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
          .map((session) => [
            {
              text: `${formatDate(toZoned(session.startsAt, zone).date)}\n${formatTime(session.startsAt, zone)}–${formatTime(session.endsAt, zone)}${toZoned(session.startsAt, zone).date !== toZoned(session.endsAt, zone).date ? `\n${hours.endsOn(formatDate(toZoned(session.endsAt, zone).date))}` : ''}`,
            },
            {
              text: `${session.title || hours.kinds[session.kind]}\n${session.rooms}`,
              fontSize: 8,
            },
            session.role === 'LEAD' ? hours.lead : hours.assistant,
            formatWorkedTime(session.seconds),
            session.confirmed
              ? hours.confirmed
              : s.includeUnconfirmed
                ? `${hours.unconfirmed} (${texts.counted.toLowerCase()})`
                : `${texts.omitted} (${hours.unconfirmed.toLowerCase()})`,
          ]),
        [76, '*', 58, 48, 80],
      ),
      payments.length
        ? table(
            [texts.payoutDate, texts.payoutAmount, texts.method, texts.recorded],
            payments.map((p) => [
              formatDate(p.paidOn),
              `${p.reversesId ? '−' : ''}${formatMoney(p.amount)}${p.reversed ? `\n${texts.reversed}` : ''}`,
              p.reversesId ? texts.reversal : texts.methods[p.method],
              {
                text: `${instant(p.createdAt)}\n${p.actor}${p.note ? `\n${p.note}` : ''}`,
                fontSize: 8,
              },
            ]),
            [64, 90, 64, '*'],
            texts.payouts,
          )
        : {
            stack: [
              { text: texts.payouts, bold: true, margin: [0, 18, 0, 8] },
              { text: texts.noPayouts },
            ],
            unbreakable: true,
          },
      table(
        [texts.changeLabel, texts.amount, texts.hours, texts.reason],
        revisions.map((r) => [
          {
            text: `${texts.revision(r.revision)}\n${instant(r.createdAt)}\n${r.actor}`,
            fontSize: 8,
          },
          formatMoney(r.amount),
          formatWorkedTime(
            r.snapshot.report.totals.seconds -
              (r.includeUnconfirmed ? 0 : r.snapshot.report.totals.unconfirmedSeconds),
          ),
          r.reason || '—',
        ]),
        [130, 78, 75, '*'],
        texts.history,
      ),
    );
  } else {
    content.push(
      table(
        [texts.employee, texts.hours, texts.amount, texts.paid, texts.remaining, texts.status],
        rows.map(({ summary: s }) => [
          s.displayName,
          formatWorkedTime(s.countedSeconds),
          formatMoney(s.amount),
          formatMoney(s.paid),
          formatMoney(s.remaining),
          s.amount.amountMinor === 0 ? texts.zeroAmount : texts.statuses[s.status],
        ]),
        ['*', 75, 98, 98, 98, 103],
      ),
    );
  }
  return {
    pageSize: 'A4',
    pageOrientation: individual ? 'portrait' : 'landscape',
    pageMargins: [32, 30, 32, 40],
    defaultStyle: { font: 'Roboto', fontSize: 9, color: '#000000', lineHeight: 1.1 },
    info: { title: texts.exportTitle, author: first.revisions[0].snapshot.studioName },
    footer: (page, total) => ({
      text: hours.export.page(page, total),
      alignment: 'right',
      margin: [32, 12, 32, 0],
      fontSize: 8,
      color: '#555555',
    }),
    content,
  };
}

export async function exportSettlementFiles(
  rows: TrainerSettlement[],
  mode: 'pdf' | 'csv' | 'print',
  individual: boolean,
  printWindow?: Window,
) {
  if (!rows.length) throw Error(texts.exportEmpty);
  const generatedAt = new Date().toISOString();
  const name = `rozliczenia-${rows[0].summary.month}-${individual ? rows[0].summary.id : 'zespol'}`;
  if (mode === 'csv') downloadCsv(settlementsCsv(rows, generatedAt), name + '.csv');
  else
    await exportPdfDocument(
      settlementsPdf(rows, generatedAt, individual),
      name + '.pdf',
      printWindow,
    );
}
