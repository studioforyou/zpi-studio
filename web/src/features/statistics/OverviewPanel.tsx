import type { ReactNode } from 'react';
import { Alert, Box, Button, SimpleGrid, Stack, Text } from '@mantine/core';
import type { UseQueryResult } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import {
  AttendanceRateTile,
  AttendanceTrendChart,
  OccupancyRanking,
  OccupancyTile,
} from './AttendanceWidgets';
import { ChartCard } from './ChartCard';
import { ActiveCustomersTile, NewCustomersTile } from './CustomerActivityPanel';
import { OutcomesTrendChart } from './OutcomeWidgets';
import { PassesSoldTile, RevenueTile, SalesRanking } from './SalesWidgets';
import { periodLength } from './reportPeriod';
import { StatTile } from './StatTile';
import { bucketFor, formatPercent, ratio } from './statisticsMath';
import type { StatisticsTab } from './statisticsTabs';
import { useBookingOutcomes, useClassAttendance } from './useAttendanceReports';
import { useCustomerActivity } from './useCustomerActivity';
import { usePassSales } from './usePassSales';
import type { StatisticsFilters } from './useStatisticsFilters';

const texts = pl.statistics.overview;

type OverviewPanelProps = {
  filters: StatisticsFilters;
  /** Opens the tab with the details of a section. */
  open: (tab: StatisticsTab) => void;
};

/** The studio at a glance: the headline numbers and charts of every report, for the filters. */
export function OverviewPanel({ filters, open }: OverviewPanelProps) {
  const attendance = useClassAttendance(filters);
  const outcomes = useBookingOutcomes(filters);
  const customers = useCustomerActivity(filters);
  const sales = usePassSales(filters);
  const attendanceData = attendance.applicable ? attendance.report.data : undefined;
  const outcomesData = outcomes.report.data;
  const customersData = customers.report.data;
  const salesData = sales.report.data;
  const wholeStudio = filters.eventsFiltered ? texts.wholeStudio : undefined;
  const details = (tab: StatisticsTab) => (
    <Button variant="subtle" size="compact-sm" onClick={() => open(tab)}>
      {`${texts.details} →`}
    </Button>
  );

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {pl.statistics.heldOnly}
      </Text>
      {!attendance.applicable && <Alert color="gray">{pl.statistics.filters.groupKindsOnly}</Alert>}
      <SimpleGrid cols={{ base: 1, xs: 2, lg: 3 }}>
        <Tile
          failed={failed(attendance)}
          stale={attendance.stale}
          label={pl.statistics.attendance.occupancy}
        >
          {attendanceData && <OccupancyTile totals={attendanceData.totals} />}
        </Tile>
        <Tile
          failed={failed(attendance)}
          stale={attendance.stale}
          label={pl.statistics.attendance.attendanceRate}
        >
          {attendanceData && <AttendanceRateTile totals={attendanceData.totals} />}
        </Tile>
        <Tile
          failed={failed(customers)}
          stale={customers.stale}
          label={pl.statistics.customers.active}
        >
          {customersData && <ActiveCustomersTile report={customersData} note={wholeStudio} />}
        </Tile>
        <Tile failed={failed(outcomes)} stale={outcomes.stale} label={texts.noShowRate}>
          {outcomesData && (
            <StatTile
              label={texts.noShowRate}
              value={formatPercent(ratio(outcomesData.totals.absent, outcomesData.totals.bookings))}
              details={[texts.ofBookings(outcomesData.totals.absent, outcomesData.totals.bookings)]}
            />
          )}
        </Tile>
        <Tile failed={failed(outcomes)} stale={outcomes.stale} label={texts.lateRate}>
          {outcomesData && (
            <StatTile
              label={texts.lateRate}
              value={formatPercent(
                ratio(outcomesData.totals.cancelledLate, outcomesData.totals.bookings),
              )}
              details={[
                texts.ofBookings(outcomesData.totals.cancelledLate, outcomesData.totals.bookings),
                outcomesData.totals.lateEntriesForfeited > 0
                  ? pl.statistics.outcomes.forfeited(outcomesData.totals.lateEntriesForfeited)
                  : null,
              ]}
            />
          )}
        </Tile>
        <Tile
          failed={failed(customers)}
          stale={customers.stale}
          label={pl.statistics.customers.new}
        >
          {customersData && <NewCustomersTile report={customersData} note={wholeStudio} />}
        </Tile>
      </SimpleGrid>
      <SimpleGrid cols={{ base: 1, xs: 2, lg: 3 }}>
        <Tile failed={failed(sales)} stale={sales.stale} label={pl.statistics.sales.revenue}>
          {salesData && <RevenueTile report={salesData} note={wholeStudio} />}
        </Tile>
        <Tile failed={failed(sales)} stale={sales.stale} label={pl.statistics.sales.passes}>
          {salesData && <PassesSoldTile report={salesData} note={wholeStudio} />}
        </Tile>
        {salesData && salesData.totals.passes > 0 && (
          <Dimmed stale={sales.stale}>
            <ChartCard
              title={pl.statistics.sales.topProducts}
              subtitle={wholeStudio}
              actions={details('sales')}
            >
              <SalesRanking report={salesData} grouping="product" measure="passes" limit={5} />
            </ChartCard>
          </Dimmed>
        )}
      </SimpleGrid>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        {attendanceData && attendanceData.totals.events > 0 && (
          <Dimmed stale={attendance.stale}>
            <ChartCard
              title={pl.statistics.attendance.trendTitle}
              subtitle={
                pl.statistics.attendance.trendSubtitle[bucketFor(periodLength(attendanceData))]
              }
              actions={details('attendance')}
            >
              <AttendanceTrendChart report={attendanceData} />
            </ChartCard>
          </Dimmed>
        )}
        {outcomesData && outcomesData.totals.bookings > 0 && (
          <Dimmed stale={outcomes.stale}>
            <ChartCard
              title={pl.statistics.outcomes.trendTitle}
              subtitle={pl.statistics.outcomes.trendSubtitle[bucketFor(periodLength(outcomesData))]}
              actions={details('outcomes')}
            >
              <OutcomesTrendChart report={outcomesData} />
            </ChartCard>
          </Dimmed>
        )}
      </SimpleGrid>
      {attendanceData && attendanceData.totals.events > 0 && (
        <Dimmed stale={attendance.stale}>
          <SimpleGrid cols={{ base: 1, md: 2 }}>
            <ChartCard
              title={texts.fullest}
              subtitle={texts.rankingSubtitle}
              actions={details('attendance')}
            >
              <OccupancyRanking classes={attendanceData.classes} end="fullest" />
            </ChartCard>
            <ChartCard title={texts.emptiest} subtitle={texts.rankingSubtitle}>
              <OccupancyRanking classes={attendanceData.classes} end="emptiest" />
            </ChartCard>
          </SimpleGrid>
        </Dimmed>
      )}
    </Stack>
  );
}

type TileProps = {
  failed: boolean;
  stale: boolean;
  /** Names the tile while its report loads, has failed or does not apply. */
  label: string;
  /** The tile, or nothing while its report is not there. */
  children: ReactNode;
};

/** A headline number that waits for its own report, so one slow report does not hide the rest. */
function Tile({ failed, stale, label, children }: TileProps) {
  if (failed || !children) {
    return <StatTile label={label} value="—" />;
  }
  return <Dimmed stale={stale}>{children}</Dimmed>;
}

/** Whether the report failed to load for the current filters. */
function failed(state: { report: UseQueryResult<unknown>; stale: boolean }): boolean {
  return state.report.isError && !state.stale;
}

function Dimmed({ stale, children }: { stale: boolean; children: ReactNode }) {
  return <Box style={{ opacity: stale ? 0.5 : 1, transition: 'opacity 150ms' }}>{children}</Box>;
}
