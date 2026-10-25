import { useState } from 'react';
import {
  Alert,
  Group,
  Paper,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { getStudio, scheduleKeys } from '@/features/schedule/scheduleApi';
import { DEFAULT_TIME_ZONE, formatDate, todayIn } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import {
  MAX_PERIOD_DAYS,
  periodError,
  periodLength,
  periodPresets,
  presetPeriod,
  type Period,
  type PeriodError,
  type PeriodPreset,
} from './activityPeriod';
import { customerActivityKeys, getCustomerActivity } from './customerActivityApi';

type PeriodChoice = PeriodPreset | 'custom';

const texts = pl.accounts.activity;

/** Active and new customers in a chosen period, compared with the period right before it. */
export function CustomerActivitySection() {
  const studio = useQuery({
    queryKey: scheduleKeys.studio,
    queryFn: getStudio,
    staleTime: Infinity,
  });
  const today = todayIn(studio.data?.timeZone ?? DEFAULT_TIME_ZONE);
  const [choice, setChoice] = useState<PeriodChoice>('last30');
  const [custom, setCustom] = useState<Period>(() => presetPeriod('last30', today));

  const period = choice === 'custom' ? custom : presetPeriod(choice, today);
  const error = choice === 'custom' ? periodError(period) : null;
  const report = useQuery({
    queryKey: customerActivityKeys.period(period.from, period.to),
    queryFn: () => getCustomerActivity(period.from, period.to),
    enabled: error === null,
    // Keeps the previous numbers on screen, dimmed, while another period loads.
    placeholderData: keepPreviousData,
  });

  function choose(value: PeriodChoice) {
    if (value === 'custom' && choice !== 'custom') {
      // The custom range starts from the period shown so far.
      setCustom(period);
    }
    setChoice(value);
  }

  const data = error === null ? report.data : undefined;

  return (
    <Stack gap="sm">
      <Title order={3}>{texts.title}</Title>
      <Group align="flex-end" gap="sm">
        <SegmentedControl
          aria-label={texts.periodLabel}
          value={choice}
          onChange={(value) => choose(value as PeriodChoice)}
          data={[...periodPresets, 'custom' as const].map((value) => ({
            value,
            label: texts.presets[value],
          }))}
        />
        {choice === 'custom' && (
          <>
            <TextInput
              type="date"
              size="sm"
              label={texts.from}
              value={custom.from}
              onChange={(event) => setCustom({ ...custom, from: event.currentTarget.value })}
            />
            <TextInput
              type="date"
              size="sm"
              label={texts.to}
              value={custom.to}
              onChange={(event) => setCustom({ ...custom, to: event.currentTarget.value })}
            />
          </>
        )}
      </Group>
      {error ? (
        <Alert color="orange">{errorMessage(error)}</Alert>
      ) : (
        <Text size="sm" c="dimmed">
          {texts.period(formatDate(period.from), formatDate(period.to), periodLength(period))}
        </Text>
      )}
      {report.isError && error === null ? (
        <Alert color="red">{texts.loadError}</Alert>
      ) : (
        <SimpleGrid
          cols={{ base: 1, sm: 2 }}
          style={{
            opacity: report.isPlaceholderData || error ? 0.5 : 1,
            transition: 'opacity 150ms',
          }}
        >
          <StatTile
            label={texts.active}
            value={data?.current.activeCustomers}
            previous={data?.previous.activeCustomers}
            previousPeriod={data?.previous}
          />
          <StatTile
            label={texts.new}
            value={data?.current.newCustomers}
            previous={data?.previous.newCustomers}
            previousPeriod={data?.previous}
          />
        </SimpleGrid>
      )}
    </Stack>
  );
}

type StatTileProps = {
  label: string;
  value: number | undefined;
  previous: number | undefined;
  previousPeriod: Period | undefined;
};

function StatTile({ label, value, previous, previousPeriod }: StatTileProps) {
  return (
    <Paper withBorder radius="md" p="md" role="group" aria-label={label}>
      <Stack gap={4}>
        <Text size="sm" fw={500} c="dimmed">
          {label}
        </Text>
        <Text fz={36} fw={600} lh={1.1}>
          {value === undefined ? '—' : formatNumber(value)}
        </Text>
        {value !== undefined && previous !== undefined && previousPeriod && (
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
        )}
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

function errorMessage(error: PeriodError): string {
  return error === 'tooLong' ? texts.errors.tooLong(MAX_PERIOD_DAYS) : texts.errors[error];
}
