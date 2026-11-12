import { Alert, Group, Paper, SimpleGrid, Stack, Text } from '@mantine/core';
import { formatDate } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import type { CustomerActivityReport } from './customerActivityApi';
import type { Period } from './reportPeriod';
import { ReportFrame } from './ReportFrame';
import { useCustomerActivity } from './useCustomerActivity';
import type { StatisticsFilters } from './useStatisticsFilters';

const texts = pl.statistics.customers;

/**
 * Active and new customers in the chosen period, compared with the period right before it (#163).
 * It covers the whole studio: the event filters do not apply to it.
 */
export function CustomerActivityPanel({ filters }: { filters: StatisticsFilters }) {
  const { report, stale } = useCustomerActivity(filters);
  return (
    <Stack gap="md">
      {filters.eventsFiltered && (
        <Alert color="gray">{pl.statistics.filters.customersNotFiltered}</Alert>
      )}
      <ReportFrame report={report} stale={stale} loadError={texts.loadError}>
        {(data) => (
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            <ActiveCustomersTile report={data} />
            <NewCustomersTile report={data} />
          </SimpleGrid>
        )}
      </ReportFrame>
    </Stack>
  );
}

/** Participants with a valid pass or a booked place in the period. */
export function ActiveCustomersTile({
  report,
  note,
}: {
  report: CustomerActivityReport;
  note?: string;
}) {
  return (
    <ActivityTile
      label={texts.active}
      note={note}
      value={report.current.activeCustomers}
      previous={report.previous.activeCustomers}
      previousPeriod={report.previous}
    />
  );
}

/** Client accounts activated and profiles without an account added in the period. */
export function NewCustomersTile({
  report,
  note,
}: {
  report: CustomerActivityReport;
  note?: string;
}) {
  return (
    <ActivityTile
      label={texts.new}
      note={note}
      value={report.current.newCustomers}
      previous={report.previous.newCustomers}
      previousPeriod={report.previous}
    />
  );
}

type ActivityTileProps = {
  label: string;
  /** Said next to the label, e.g. that the number covers the whole studio. */
  note?: string;
  value: number;
  previous: number;
  previousPeriod: Period;
};

function ActivityTile({ label, note, value, previous, previousPeriod }: ActivityTileProps) {
  return (
    <Paper withBorder radius="md" p="md" role="group" aria-label={label}>
      <Stack gap={6}>
        <Text size="sm" fw={500} c="dimmed">
          {label}
          {note && ` · ${note}`}
        </Text>
        <Text fz={32} fw={600} lh={1.1}>
          {formatNumber(value)}
        </Text>
        <Group gap={6} wrap="wrap">
          <Delta value={value} previous={previous} />
          <Text size="sm" c="dimmed">
            {texts.previous(
              formatNumber(previous),
              formatDate(previousPeriod.from),
              formatDate(previousPeriod.to),
            )}
          </Text>
        </Group>
      </Stack>
    </Paper>
  );
}

/** The change against the previous period; more customers is good, so up is green. */
function Delta({ value, previous }: { value: number; previous: number }) {
  const change = value - previous;
  if (change === 0) {
    return (
      <Text size="sm" fw={600} c="dimmed">
        {texts.noChange}
      </Text>
    );
  }
  const percent = previous > 0 ? ` (${signed(Math.round((change / previous) * 100))}%)` : '';
  return (
    <Text size="sm" fw={600} c={change > 0 ? 'teal.7' : 'red.7'}>
      {change > 0 ? '▲' : '▼'} {signed(change)}
      {percent}
    </Text>
  );
}

function signed(value: number): string {
  if (value === 0) {
    return '0';
  }
  return value > 0 ? `+${formatNumber(value)}` : `−${formatNumber(-value)}`;
}

function formatNumber(value: number): string {
  return value.toLocaleString('pl-PL');
}
