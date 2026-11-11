import { Box, Group, Progress, Stack, Text } from '@mantine/core';
import { LineChart } from '@mantine/charts';
import { pl } from '@/shared/i18n/pl';
import type {
  ClassAttendanceClass,
  ClassAttendanceCounts,
  ClassAttendanceReport,
} from './attendanceReportsApi';
import { TooltipCard } from './ChartCard';
import { chartColors } from './chartColors';
import { periodLength } from './reportPeriod';
import { StatTile } from './StatTile';
import {
  attendanceRate,
  bucketDays,
  bucketFor,
  bucketLabel,
  bucketTitle,
  formatNumber,
  formatPercent,
  noClassCounts,
  occupancy,
  rankByOccupancy,
  sortSummaries,
  summarizeClasses,
} from './statisticsMath';

const texts = pl.statistics.attendance;

/** Booked places against the places offered, with a meter. */
export function OccupancyTile({ totals }: { totals: ClassAttendanceCounts }) {
  const fill = occupancy(totals);
  return (
    <StatTile
      label={texts.occupancy}
      value={formatPercent(fill)}
      details={[
        fill === null
          ? texts.occupancyUnknown
          : texts.occupancyDetail(totals.placesBooked, totals.places),
      ]}
    >
      {fill !== null && <OccupancyMeter value={fill} size="md" />}
    </StatTile>
  );
}

/** Attended places against the booked ones. */
export function AttendanceRateTile({ totals }: { totals: ClassAttendanceCounts }) {
  return (
    <StatTile
      label={texts.attendanceRate}
      value={formatPercent(attendanceRate(totals))}
      details={[
        texts.attendanceDetail(totals.attended, totals.booked),
        totals.unmarked > 0 ? texts.unmarked(totals.unmarked) : null,
      ]}
    />
  );
}

/** A share of places on the occupancy blue, over a lighter step of the same blue. */
export function OccupancyMeter({ value, size }: { value: number; size: 'sm' | 'md' }) {
  return (
    <Progress
      aria-hidden
      value={Math.min(100, value * 100)}
      color={chartColors.occupancy}
      size={size}
      radius="xl"
      styles={{ root: { backgroundColor: chartColors.track } }}
    />
  );
}

type TrendPoint = {
  label: string;
  title: string;
  occupancy: number | null;
  attendance: number | null;
};

const trendSeries = [
  { name: 'occupancy', label: texts.series.occupancy, color: chartColors.occupancy },
  { name: 'attendance', label: texts.series.attendance, color: chartColors.attendance },
] as const;

/** Occupancy and attendance day by day, week by week or month by month, by the period's length. */
export function AttendanceTrendChart({ report }: { report: ClassAttendanceReport }) {
  const bucket = bucketFor(periodLength(report));
  const data: TrendPoint[] = bucketDays(report.days, bucket, noClassCounts).map(
    ({ start, counts }) => ({
      label: bucketLabel(start, bucket),
      title: bucketTitle(start, bucket),
      occupancy: percent(occupancy(counts)),
      attendance: percent(attendanceRate(counts)),
    }),
  );
  return (
    <LineChart
      h={260}
      data={data}
      dataKey="label"
      series={[...trendSeries]}
      curveType="linear"
      strokeWidth={2}
      connectNulls
      dotProps={{ r: 4, strokeWidth: 2, stroke: '#ffffff' }}
      activeDotProps={{ r: 5, strokeWidth: 2, stroke: '#ffffff' }}
      withLegend
      legendProps={{ verticalAlign: 'top', height: 36 }}
      gridAxis="x"
      strokeDasharray="0"
      tickLine="none"
      yAxisProps={{ domain: [0, 100], ticks: [0, 25, 50, 75, 100] }}
      valueFormatter={(value) => `${value}%`}
      tooltipAnimationDuration={0}
      tooltipProps={{
        content: ({ active, payload }) => {
          const point = active ? (payload?.[0]?.payload as TrendPoint | undefined) : undefined;
          if (!point) {
            return null;
          }
          return (
            <TooltipCard
              title={point.title}
              rows={trendSeries.map((line) => ({
                key: line.name,
                label: line.label,
                value: point[line.name] === null ? '—' : `${point[line.name]}%`,
                color: line.color,
              }))}
            />
          );
        },
      }}
    />
  );
}

/** Classes with a limit of places from the fullest down, or from the emptiest up. */
export function OccupancyRanking({
  classes,
  end,
}: {
  classes: ClassAttendanceClass[];
  end: 'fullest' | 'emptiest';
}) {
  const ranked = rankByOccupancy(classes)[end];
  if (ranked.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {pl.statistics.overview.noRanking}
      </Text>
    );
  }
  const rows = sortSummaries(summarizeClasses(ranked, 'classes'), 'occupancy', end === 'fullest');
  return (
    <Stack gap="sm">
      {rows.map((row) => {
        const fill = occupancy(row.counts) ?? 0;
        return (
          <Stack key={row.key} gap={4}>
            <Group justify="space-between" gap="sm" wrap="nowrap">
              <Group gap={8} wrap="nowrap" style={{ minWidth: 0 }}>
                <Box
                  w={8}
                  h={8}
                  style={{
                    flexShrink: 0,
                    borderRadius: '50%',
                    background: row.color ?? 'var(--mantine-color-gray-4)',
                  }}
                />
                <Text size="sm" fw={500} truncate>
                  {row.name}
                </Text>
                {row.detail && (
                  <Text size="xs" c="dimmed" truncate>
                    {row.detail}
                  </Text>
                )}
              </Group>
              <Text size="sm" style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                {formatPercent(fill)}{' '}
                <Text span size="xs" c="dimmed">
                  {`${formatNumber(row.counts.placesBooked)}/${formatNumber(row.counts.places)}`}
                </Text>
              </Text>
            </Group>
            <OccupancyMeter value={fill} size="sm" />
          </Stack>
        );
      })}
    </Stack>
  );
}

/** A ratio as a rounded percentage for the chart, `null` without a ratio. */
function percent(value: number | null): number | null {
  return value === null ? null : Math.round(value * 100);
}
