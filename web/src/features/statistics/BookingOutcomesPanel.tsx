import { Alert, Box, Group, SimpleGrid, Stack, Table, Text } from '@mantine/core';
import { BarChart } from '@mantine/charts';
import { pl } from '@/shared/i18n/pl';
import type { BookingOutcomeService, BookingOutcomesReport } from './attendanceReportsApi';
import { ChartCard, TooltipCard } from './ChartCard';
import { chartColors } from './chartColors';
import { OutcomesTrendChart } from './OutcomeWidgets';
import { splitLabel, splitParts } from './outcomeParts';
import { periodLength } from './reportPeriod';
import { ReportFrame } from './ReportFrame';
import { SplitBar, SplitLegend } from './SplitBar';
import { StatTile } from './StatTile';
import {
  bucketFor,
  describePolicy,
  formatNumber,
  formatPercent,
  noOutcomeCounts,
  ratio,
} from './statisticsMath';
import { useBookingOutcomes } from './useAttendanceReports';
import type { StatisticsFilters } from './useStatisticsFilters';

const texts = pl.statistics.outcomes;

/**
 * No-shows and cancellations in time and late of the events the filters choose, to judge the
 * cancellation rules (#166). By default group classes and workshops; personal training only when
 * the filters choose it, so it does not skew their shares.
 */
export function BookingOutcomesPanel({ filters }: { filters: StatisticsFilters }) {
  const { report, stale } = useBookingOutcomes(filters);
  return (
    <ReportFrame report={report} stale={stale} loadError={texts.loadError}>
      {(data) => (
        <>
          <Text size="sm" c="dimmed">
            {pl.statistics.heldOnly}
          </Text>
          {data.totals.bookings === 0 ? (
            <Alert color="gray">{texts.empty}</Alert>
          ) : (
            <OutcomesReport report={data} />
          )}
        </>
      )}
    </ReportFrame>
  );
}

function OutcomesReport({ report }: { report: BookingOutcomesReport }) {
  const { totals } = report;
  const share = (value: number) =>
    texts.shareOfBookings(formatPercent(ratio(value, totals.bookings)));
  const parts = splitParts(totals);
  const bucket = bucketFor(periodLength(report));
  return (
    <>
      <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
        <StatTile
          label={texts.absent}
          value={formatNumber(totals.absent)}
          details={[share(totals.absent)]}
        />
        <StatTile
          label={texts.cancelledLate}
          value={formatNumber(totals.cancelledLate)}
          details={[
            share(totals.cancelledLate),
            totals.lateEntriesForfeited > 0 ? texts.forfeited(totals.lateEntriesForfeited) : null,
          ]}
        />
        <StatTile
          label={texts.cancelledInTime}
          value={formatNumber(totals.cancelledInTime)}
          details={[share(totals.cancelledInTime)]}
        />
        <StatTile
          label={texts.bookings}
          value={formatNumber(totals.bookings)}
          details={[
            texts.bookingsDetail(totals.events),
            totals.unmarked > 0 ? pl.statistics.attendance.unmarked(totals.unmarked) : null,
          ]}
        />
      </SimpleGrid>
      <ChartCard title={texts.splitTitle}>
        <SplitBar parts={parts} variant="tall" label={splitLabel(parts)} />
        <SplitLegend parts={parts} />
      </ChartCard>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <ChartCard title={texts.trendTitle} subtitle={texts.trendSubtitle[bucket]}>
          <OutcomesTrendChart report={report} />
        </ChartCard>
        <ChartCard title={texts.leadTimeTitle} subtitle={texts.leadTimeSubtitle}>
          <LeadTimes report={report} />
        </ChartCard>
      </SimpleGrid>
      <ServicesTable services={report.services} />
    </>
  );
}

type LeadTimePoint = { label: string; inTime: number; late: number };

const leadTimeSeries = [
  { name: 'inTime', label: texts.cancelledInTime, color: chartColors.cancelledInTime },
  { name: 'late', label: texts.cancelledLate, color: chartColors.cancelledLate },
] as const;

function LeadTimes({ report }: { report: BookingOutcomesReport }) {
  const cancelled = report.totals.cancelledInTime + report.totals.cancelledLate;
  // Time runs towards the start from left to right.
  const data: LeadTimePoint[] = [...report.cancellationLeadTimes].reverse().map((range) => ({
    label: texts.leadTimeRange(range.fromHours, range.toHours),
    inTime: range.inTime,
    late: range.late,
  }));
  // The free cancellation times now in force for the services shown, shortest first.
  const freeHours = [
    ...new Set(
      report.services.flatMap(({ policy }) =>
        policy && policy.cancellationAllowed !== false && policy.freeCancelHours !== null
          ? [policy.freeCancelHours]
          : [],
      ),
    ),
  ].sort((a, b) => a - b);
  if (cancelled === 0) {
    return (
      <Text size="sm" c="dimmed">
        {texts.noCancellations}
      </Text>
    );
  }
  return (
    <Stack gap="xs">
      <BarChart
        h={260}
        data={data}
        dataKey="label"
        type="stacked"
        series={[...leadTimeSeries]}
        maxBarWidth={24}
        barProps={{ stroke: '#ffffff', strokeWidth: 2 }}
        withLegend
        legendProps={{ verticalAlign: 'top', height: 36 }}
        gridAxis="x"
        strokeDasharray="0"
        tickLine="none"
        yAxisProps={{ allowDecimals: false }}
        valueFormatter={(value) => formatNumber(value)}
        tooltipAnimationDuration={0}
        tooltipProps={{
          content: ({ active, payload }) => {
            const point = active ? (payload?.[0]?.payload as LeadTimePoint | undefined) : undefined;
            if (!point) {
              return null;
            }
            return (
              <TooltipCard
                title={point.label}
                rows={leadTimeSeries.map((series) => ({
                  key: series.name,
                  label: series.label,
                  value: formatNumber(point[series.name]),
                  color: series.color,
                }))}
              />
            );
          },
        }}
      />
      {freeHours.length > 0 && (
        <Text size="sm" c="dimmed">
          {texts.freeCancelRules(freeHours.map((hours) => texts.hours(hours)).join(' lub '))}
        </Text>
      )}
    </Stack>
  );
}

function ServicesTable({ services }: { services: BookingOutcomeService[] }) {
  const columns = texts.columns;
  return (
    <ChartCard title={texts.tableTitle}>
      <SplitLegend parts={splitParts(noOutcomeCounts)} />
      <Table.ScrollContainer minWidth={820}>
        <Table verticalSpacing="xs" highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{columns.service}</Table.Th>
              <Table.Th>{columns.rules}</Table.Th>
              <Table.Th style={numeric}>{columns.bookings}</Table.Th>
              <Table.Th style={{ width: '16%' }}>{columns.split}</Table.Th>
              <Table.Th style={numeric}>{columns.absent}</Table.Th>
              <Table.Th style={numeric}>{columns.cancelledLate}</Table.Th>
              <Table.Th style={numeric}>{columns.cancelledInTime}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {services.map((service) => {
              const counts = service.counts;
              const parts = splitParts(counts);
              const cell = (value: number) =>
                `${formatNumber(value)} (${formatPercent(ratio(value, counts.bookings))})`;
              return (
                <Table.Tr key={service.serviceTypeId ?? 'none'}>
                  <Table.Td>
                    <Group gap={8} wrap="nowrap">
                      <Box
                        w={8}
                        h={8}
                        style={{
                          flexShrink: 0,
                          borderRadius: '50%',
                          background: service.calendarColor ?? 'var(--mantine-color-gray-4)',
                        }}
                      />
                      <Text size="sm" fw={500}>
                        {service.name ?? texts.noService}
                      </Text>
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {describePolicy(service.policy)}
                    </Text>
                  </Table.Td>
                  <Table.Td style={numeric}>{formatNumber(counts.bookings)}</Table.Td>
                  <Table.Td>
                    <SplitBar parts={parts} variant="thin" label={splitLabel(parts)} />
                  </Table.Td>
                  <Table.Td style={numeric}>{cell(counts.absent)}</Table.Td>
                  <Table.Td style={numeric}>{cell(counts.cancelledLate)}</Table.Td>
                  <Table.Td style={numeric}>{cell(counts.cancelledInTime)}</Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
    </ChartCard>
  );
}

const numeric = {
  textAlign: 'right',
  fontVariantNumeric: 'tabular-nums',
  whiteSpace: 'nowrap',
} as const;
