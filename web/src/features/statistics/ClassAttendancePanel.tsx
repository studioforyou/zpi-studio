import { Alert, SimpleGrid, Text } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import type { ClassAttendanceReport } from './attendanceReportsApi';
import { AttendanceRateTile, AttendanceTrendChart, OccupancyTile } from './AttendanceWidgets';
import { ChartCard } from './ChartCard';
import { ClassesTable } from './ClassesTable';
import { OccupancyHeatmap } from './OccupancyHeatmap';
import { periodLength } from './reportPeriod';
import { ReportFrame } from './ReportFrame';
import { StatTile } from './StatTile';
import { averageAttendees, bucketFor, formatNumber } from './statisticsMath';
import { useClassAttendance } from './useAttendanceReports';
import type { StatisticsFilters } from './useStatisticsFilters';

const texts = pl.statistics.attendance;

/** Attendance and occupancy of the group classes and workshops the filters choose (#164). */
export function ClassAttendancePanel({ filters }: { filters: StatisticsFilters }) {
  const { report, stale, applicable } = useClassAttendance(filters);
  if (!applicable) {
    return <Alert color="gray">{pl.statistics.filters.groupKindsOnly}</Alert>;
  }
  return (
    <ReportFrame report={report} stale={stale} loadError={texts.loadError}>
      {(data) => (
        <>
          <Text size="sm" c="dimmed">
            {pl.statistics.heldOnly}
          </Text>
          {data.totals.events === 0 ? (
            <Alert color="gray">{texts.empty}</Alert>
          ) : (
            <AttendanceReport report={data} />
          )}
        </>
      )}
    </ReportFrame>
  );
}

function AttendanceReport({ report }: { report: ClassAttendanceReport }) {
  const { totals } = report;
  const average = averageAttendees(totals);
  return (
    <>
      <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
        <OccupancyTile totals={totals} />
        <AttendanceRateTile totals={totals} />
        <StatTile
          label={texts.averageAttendees}
          value={average === null ? '—' : formatNumber(average, 1)}
          details={[texts.averageDetail(totals.events)]}
        />
        <StatTile
          label={texts.waitlisted}
          value={formatNumber(totals.waitlisted)}
          details={[texts.waitlistedDetail]}
        />
      </SimpleGrid>
      <ChartCard
        title={texts.trendTitle}
        subtitle={texts.trendSubtitle[bucketFor(periodLength(report))]}
      >
        <AttendanceTrendChart report={report} />
      </ChartCard>
      <ChartCard title={texts.heatmapTitle} subtitle={texts.heatmapSubtitle}>
        <OccupancyHeatmap classes={report.classes} />
      </ChartCard>
      <ClassesTable classes={report.classes} />
    </>
  );
}
