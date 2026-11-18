import { Box, Group, Paper, Stack, Text } from '@mantine/core';
import { BarChart } from '@mantine/charts';
import { pl } from '@/shared/i18n/pl';
import { TooltipCard } from './ChartCard';
import { passKindColors } from './chartColors';
import type { PassSalesReport } from './passSalesApi';
import { periodLength } from './reportPeriod';
import {
  averageMinor,
  formatAmount,
  measured,
  salesBuckets,
  salesRanking,
  soldKinds,
  type SalesGrouping,
  type SalesMeasure,
} from './salesMath';
import {
  bucketFor,
  bucketLabel,
  bucketTitle,
  formatNumber,
  formatPercent,
  ratio,
} from './statisticsMath';

const texts = pl.statistics.sales;

type SalesTileProps = {
  label: string;
  /** Said next to the label, e.g. that the number covers the whole studio. */
  note?: string;
  value: number;
  previous: number;
  format: (value: number) => string;
  details?: (string | null)[];
};

/** A headline number of the sales, compared with the period right before. */
function SalesTile({ label, note, value, previous, format, details = [] }: SalesTileProps) {
  const change = value - previous;
  const percent =
    previous > 0 ? ` (${signed(Math.round((change / previous) * 100), String)}%)` : '';
  return (
    <Paper withBorder radius="md" p="md" role="group" aria-label={label}>
      <Stack gap={6}>
        <Text size="sm" fw={500} c="dimmed">
          {label}
          {note && ` · ${note}`}
        </Text>
        <Text fz={32} fw={600} lh={1.1}>
          {format(value)}
        </Text>
        <Group gap={6} wrap="wrap">
          {change === 0 ? (
            <Text size="sm" fw={600} c="dimmed">
              {texts.noChange}
            </Text>
          ) : (
            <Text size="sm" fw={600} c={change > 0 ? 'teal.7' : 'red.7'}>
              {change > 0 ? '▲' : '▼'} {signed(change, format)}
              {percent}
            </Text>
          )}
          <Text size="sm" c="dimmed">
            {texts.previous(format(previous))}
          </Text>
        </Group>
        {details
          .filter((detail): detail is string => Boolean(detail))
          .map((detail) => (
            <Text key={detail} size="sm" c="dimmed">
              {detail}
            </Text>
          ))}
      </Stack>
    </Paper>
  );
}

function signed(value: number, format: (value: number) => string): string {
  if (value === 0) {
    return format(0);
  }
  return value > 0 ? `+${format(value)}` : `−${format(-value)}`;
}

type TileProps = { report: PassSalesReport; note?: string };

export function RevenueTile({ report, note }: TileProps) {
  return (
    <SalesTile
      label={texts.revenue}
      note={note}
      value={report.totals.revenueMinor}
      previous={report.previous.totals.revenueMinor}
      format={(value) => formatAmount(value, report.currency)}
    />
  );
}

export function PassesSoldTile({ report, note }: TileProps) {
  return (
    <SalesTile
      label={texts.passes}
      note={note}
      value={report.totals.passes}
      previous={report.previous.totals.passes}
      format={(value) => formatNumber(value)}
      details={[report.totals.free > 0 ? texts.free(report.totals.free) : null]}
    />
  );
}

export function AverageValueTile({ report }: TileProps) {
  const current = averageMinor(report.totals);
  if (current === null) {
    return <EmptyTile label={texts.average} />;
  }
  return (
    <SalesTile
      label={texts.average}
      value={current}
      previous={averageMinor(report.previous.totals) ?? 0}
      format={(value) => formatAmount(value, report.currency)}
    />
  );
}

export function TopProductTile({ report }: TileProps) {
  const top = salesRanking(report, 'product', 'passes')[0];
  if (!top) {
    return <EmptyTile label={texts.topProduct} />;
  }
  return (
    <Paper withBorder radius="md" p="md" role="group" aria-label={texts.topProduct}>
      <Stack gap={6}>
        <Text size="sm" fw={500} c="dimmed">
          {texts.topProduct}
        </Text>
        <Text fz={22} fw={600} lh={1.2} mt={4}>
          {top.name}
        </Text>
        <Text size="sm" c="dimmed">
          {texts.topDetail(
            top.counts.passes,
            formatPercent(ratio(top.counts.passes, report.totals.passes)),
            formatAmount(top.counts.revenueMinor, report.currency),
          )}
        </Text>
      </Stack>
    </Paper>
  );
}

function EmptyTile({ label }: { label: string }) {
  return (
    <Paper withBorder radius="md" p="md" role="group" aria-label={label}>
      <Stack gap={6}>
        <Text size="sm" fw={500} c="dimmed">
          {label}
        </Text>
        <Text fz={32} fw={600} lh={1.1}>
          —
        </Text>
      </Stack>
    </Paper>
  );
}

type SalesRankingProps = {
  report: PassSalesReport;
  grouping: SalesGrouping;
  measure: SalesMeasure;
  /** Shows only this many rows from the top. */
  limit?: number;
};

/** Kinds or products as horizontal bars, the largest first, each with its share. */
export function SalesRanking({ report, grouping, measure, limit }: SalesRankingProps) {
  const rows = salesRanking(report, grouping, measure).slice(0, limit);
  const whole = measured(report.totals, measure);
  const largest = Math.max(1, ...rows.map((row) => measured(row.counts, measure)));
  return (
    <Stack gap={10} role="list">
      {rows.map((row) => {
        const value = measured(row.counts, measure);
        const label =
          measure === 'passes'
            ? texts.pieces(row.counts.passes)
            : formatAmount(row.counts.revenueMinor, report.currency);
        return (
          <Group
            key={row.key}
            gap="sm"
            wrap="nowrap"
            role="listitem"
            aria-label={`${row.name}: ${label}`}
          >
            <Text size="sm" truncate w={190} style={{ flexShrink: 0 }}>
              {row.name}
            </Text>
            <Box style={{ flex: 1, minWidth: 0 }}>
              <Box
                h={14}
                style={{
                  width: value > 0 ? `${Math.max((value / largest) * 100, 1)}%` : 2,
                  background: value > 0 ? passKindColors[row.kind] : 'var(--mantine-color-gray-3)',
                  borderRadius: 3,
                }}
              />
            </Box>
            <Text
              size="sm"
              fw={600}
              ta="right"
              w={120}
              style={{ flexShrink: 0, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}
            >
              {label}{' '}
              <Text span size="xs" c="dimmed" fw={400}>
                {formatPercent(ratio(value, whole))}
              </Text>
            </Text>
          </Group>
        );
      })}
    </Stack>
  );
}

type TrendPoint = { label: string; title: string } & Record<string, number | string>;

/** Passes or revenue period by period, stacked by kind of pass. */
export function SalesTrendChart({
  report,
  measure,
}: {
  report: PassSalesReport;
  measure: SalesMeasure;
}) {
  const bucket = bucketFor(periodLength(report));
  const kinds = soldKinds(report);
  const format = (value: number) =>
    measure === 'passes' ? texts.pieces(value) : formatAmount(value, report.currency);
  const data: TrendPoint[] = salesBuckets(report, bucket, measure).map(({ start, values }) => ({
    label: bucketLabel(start, bucket),
    title: bucketTitle(start, bucket),
    ...values,
  }));
  return (
    <BarChart
      h={260}
      data={data}
      dataKey="label"
      type="stacked"
      series={kinds.map((kind) => ({
        name: kind,
        label: texts.kinds[kind],
        color: passKindColors[kind],
      }))}
      maxBarWidth={32}
      barProps={{ stroke: '#ffffff', strokeWidth: 1 }}
      withLegend
      legendProps={{ verticalAlign: 'bottom', height: 48 }}
      gridAxis="x"
      strokeDasharray="0"
      tickLine="none"
      valueFormatter={(value) =>
        measure === 'passes' ? formatNumber(value) : formatAmount(value, report.currency)
      }
      tooltipAnimationDuration={0}
      tooltipProps={{
        content: ({ active, payload }) => {
          const point = active ? (payload?.[0]?.payload as TrendPoint | undefined) : undefined;
          if (!point) {
            return null;
          }
          const total = kinds.reduce((sum, kind) => sum + Number(point[kind]), 0);
          return (
            <TooltipCard
              title={`${point.title} · ${format(total)}`}
              rows={kinds
                .filter((kind) => Number(point[kind]) > 0)
                .map((kind) => ({
                  key: kind,
                  label: texts.kinds[kind],
                  value: format(Number(point[kind])),
                  color: passKindColors[kind],
                }))}
            />
          );
        },
      }}
    />
  );
}
