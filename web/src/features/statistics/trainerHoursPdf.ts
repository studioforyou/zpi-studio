import type { Content, TableCell, TDocumentDefinitions } from 'pdfmake/interfaces';
import { formatDate, formatMonth, formatTime, toZoned } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import { formatWorkedTime } from './statisticsMath';
import { hoursExportFilename, type HoursExport } from './trainerHoursExport';

const texts = pl.statistics.hours;
const exp = texts.export;
const secondary = '#555555';

function header(labels: string[]): TableCell[] {
  return labels.map((text) => ({
    text,
    bold: true,
    margin: [0, 3],
  }));
}

/** Both downloads and printing use exactly the same complete, paginated document. */
export function trainerHoursPdfDefinition(data: HoursExport): TDocumentDefinitions {
  const { report, generatedAt, studioName, staffId } = data;
  const individual = staffId ? report.instructors.find((row) => row.id === staffId) : undefined;
  const zone = report.timeZone;
  const generated = new Intl.DateTimeFormat('pl-PL', {
    timeZone: zone,
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(generatedAt));
  const totals = report.totals;
  const summary: Content = {
    columns: [
      {
        stack: [
          { text: texts.totalShort, color: secondary },
          { text: formatWorkedTime(totals.seconds), fontSize: 15, bold: true },
        ],
      },
      {
        stack: [
          { text: exp.confirmed, color: secondary },
          {
            text: formatWorkedTime(totals.seconds - totals.unconfirmedSeconds),
            fontSize: 15,
            bold: true,
          },
        ],
      },
      {
        stack: [
          { text: texts.unconfirmed, color: secondary },
          {
            text: formatWorkedTime(totals.unconfirmedSeconds),
            fontSize: 15,
            bold: true,
          },
        ],
      },
    ],
    margin: [0, 14, 0, 16],
  };
  const body: TableCell[][] = individual
    ? [
        header([texts.date, texts.session, texts.role, texts.time, exp.status]),
        ...[...individual.sessions]
          .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
          .map((session) => {
            const start = toZoned(session.startsAt, zone).date;
            const end = toZoned(session.endsAt, zone).date;
            return [
              {
                text: [
                  formatDate(start),
                  `${formatTime(session.startsAt, zone)}–${formatTime(session.endsAt, zone)}`,
                  ...(start !== end ? [texts.endsOn(formatDate(end))] : []),
                ].join('\n'),
              },
              {
                stack: [
                  { text: session.title || texts.kinds[session.kind], bold: true },
                  {
                    text: [texts.kinds[session.kind], session.rooms].filter(Boolean).join(' · '),
                    color: secondary,
                    fontSize: 8,
                  },
                ],
              },
              { text: session.role === 'LEAD' ? texts.lead : texts.assistant },
              { text: formatWorkedTime(session.seconds), alignment: 'right' },
              {
                text: session.confirmed ? texts.confirmed : texts.unconfirmed,
                bold: !session.confirmed,
              },
            ] satisfies TableCell[];
          }),
      ]
    : [
        header([
          texts.instructor,
          texts.lead,
          texts.assistant,
          exp.confirmed,
          texts.unconfirmed,
          texts.totalShort,
          texts.events,
        ]),
        ...[...report.instructors]
          .sort((a, b) => a.displayName.localeCompare(b.displayName, 'pl'))
          .map((row) => [
            {
              text: row.displayName + (row.active ? '' : ` (${texts.inactive.toLowerCase()})`),
              bold: true,
            },
            ...[
              row.counts.leadSeconds,
              row.counts.assistantSeconds,
              row.counts.seconds - row.counts.unconfirmedSeconds,
              row.counts.unconfirmedSeconds,
              row.counts.seconds,
            ].map((seconds) => ({ text: formatWorkedTime(seconds), alignment: 'right' as const })),
            { text: String(row.counts.events), alignment: 'right' as const },
          ]),
      ];
  return {
    pageSize: 'A4',
    pageOrientation: individual ? 'portrait' : 'landscape',
    pageMargins: [36, 32, 36, 42],
    info: { title: `${exp.documentTitle} - ${report.from.slice(0, 7)}`, author: studioName },
    defaultStyle: { font: 'Roboto', fontSize: 9, color: '#000000', lineHeight: 1.15 },
    footer: (page, total) => ({
      columns: [
        { text: `${studioName} · ${report.from.slice(0, 7)}`, color: secondary },
        { text: exp.page(page, total), alignment: 'right', color: secondary },
      ],
      margin: [36, 14, 36, 0],
      fontSize: 8,
    }),
    content: [
      { text: studioName, fontSize: 10, margin: [0, 0, 0, 6] },
      { text: exp.documentTitle, fontSize: 16, bold: true },
      {
        text: `${formatMonth(report.from)} · ${individual?.displayName ?? texts.allInstructors}`,
        fontSize: 11,
        margin: [0, 6, 0, 10],
      },
      {
        text: `${exp.period}: ${formatDate(report.from)}–${formatDate(report.to)}   |   ${exp.generated}: ${generated}`,
        color: secondary,
        fontSize: 8,
      },
      { text: `${exp.timeZone}: ${zone}`, color: secondary, fontSize: 8, margin: [0, 3, 0, 0] },
      summary,
      {
        text: `${texts.lead}: ${formatWorkedTime(totals.leadSeconds)}   ·   ${texts.assistant}: ${formatWorkedTime(totals.assistantSeconds)}   ·   ${texts.events}: ${totals.events}`,
        margin: [0, 0, 0, 10],
      },
      {
        table: {
          headerRows: 1,
          dontBreakRows: true,
          widths: individual ? [82, '*', 57, 44, 68] : ['*', 79, 64, 81, 85, 78, 40],
          body,
        },
        layout: {
          hLineWidth: (line) => (line === 0 ? 0 : line === 1 ? 0.5 : 0.25),
          vLineWidth: () => 0,
          hLineColor: (line) => (line === 1 ? '#888888' : '#d8d8d8'),
          paddingTop: () => 6,
          paddingBottom: () => 6,
        },
      },
      ...(report.instructors.length === 0 || individual?.sessions.length === 0
        ? [{ text: texts.empty, margin: [0, 10, 0, 0] } satisfies Content]
        : []),
      { text: exp.basis, color: secondary, fontSize: 8, margin: [0, 15, 0, 5] },
      ...(!individual
        ? [
            {
              text: exp.counting,
              color: secondary,
              fontSize: 8,
              margin: [0, 0, 0, 5],
            } satisfies Content,
          ]
        : []),
      { text: exp.provisional, color: secondary, fontSize: 8 },
    ],
  };
}

export async function exportHoursPdf(data: HoursExport, printWindow?: Window) {
  return exportPdfDocument(
    trainerHoursPdfDefinition(data),
    hoursExportFilename(data, 'pdf'),
    printWindow,
  );
}

/** Shared renderer for reports and saved settlements; both use the same bundled fonts. */
export async function exportPdfDocument(
  definition: TDocumentDefinitions,
  filename: string,
  printWindow?: Window,
) {
  // Keep the PDF engine and its Polish-capable fonts out of the initial report bundle.
  const [{ default: pdfMake }, { default: fonts }] = await Promise.all([
    import('pdfmake/build/pdfmake'),
    import('pdfmake/build/vfs_fonts'),
  ]);
  pdfMake.addVirtualFileSystem(fonts);
  const pdf = pdfMake.createPdf(definition);
  if (printWindow) await pdf.print(printWindow);
  else await pdf.download(filename);
}
