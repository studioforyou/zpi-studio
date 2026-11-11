import { BarChart } from '@mantine/charts';
import { pl } from '@/shared/i18n/pl';
import type { BookingOutcomesReport } from './attendanceReportsApi';
import { TooltipCard } from './ChartCard';
import { chartColors } from './chartColors';
import { periodLength } from './reportPeriod';
import {
  bucketDays,
  bucketFor,
  bucketLabel,
  bucketTitle,
  noOutcomeCounts,
  percentTicks,
  sharePercent,
} from './statisticsMath';

const texts = pl.statistics.outcomes;

type TrendPoint = {
  label: string;
  title: string;
  bookings: number;
  absent: number;
  cancelledLate: number;
  absentShare: number;
  lateShare: number;
};

const trendSeries = [
  { name: 'absentShare', count: 'absent', label: texts.absent, color: chartColors.absent },
  {
    name: 'lateShare',
    count: 'cancelledLate',
    label: texts.cancelledLate,
    color: chartColors.cancelledLate,
  },
] as const;

/** No-shows and late cancellations as shares of the bookings, stacked, period by period. */
export function OutcomesTrendChart({ report }: { report: BookingOutcomesReport }) {
  const bucket = bucketFor(periodLength(report));
  const data: TrendPoint[] = bucketDays(report.days, bucket, noOutcomeCounts).map(
    ({ start, counts }) => ({
      label: bucketLabel(start, bucket),
      title: bucketTitle(start, bucket),
      bookings: counts.bookings,
      absent: counts.absent,
      cancelledLate: counts.cancelledLate,
      absentShare: sharePercent(counts.absent, counts.bookings),
      lateShare: sharePercent(counts.cancelledLate, counts.bookings),
    }),
  );
  const ticks = percentTicks(
    Math.max(0, ...data.map((point) => point.absentShare + point.lateShare)),
  );
  return (
    <BarChart
      h={260}
      data={data}
      dataKey="label"
      type="stacked"
      series={trendSeries.map(({ name, label, color }) => ({ name, label, color }))}
      maxBarWidth={24}
      // A line of the background between stacked parts keeps them apart.
      barProps={{ stroke: '#ffffff', strokeWidth: 2 }}
      withLegend
      legendProps={{ verticalAlign: 'top', height: 36 }}
      gridAxis="x"
      strokeDasharray="0"
      tickLine="none"
      yAxisProps={{ domain: [0, ticks[ticks.length - 1]], ticks }}
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
              title={texts.bucketBookings(point.title, point.bookings)}
              rows={trendSeries.map((series) => ({
                key: series.name,
                label: series.label,
                value: texts.tooltipValue(point[series.count], `${point[series.name]}%`),
                color: series.color,
              }))}
            />
          );
        },
      }}
    />
  );
}
