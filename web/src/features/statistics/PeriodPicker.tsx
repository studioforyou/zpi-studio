import { Alert, Group, SegmentedControl, Stack, Text, TextInput } from '@mantine/core';
import { formatDate } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import { MAX_PERIOD_DAYS, periodLength, periodPresets, type PeriodError } from './reportPeriod';
import type { PeriodChoice, ReportPeriodState } from './useStatisticsFilters';

const texts = pl.statistics.period;

/** Presets and a custom range, with the chosen dates or why they cannot be reported. */
export function PeriodPicker({ state }: { state: ReportPeriodState }) {
  const { choice, choose, custom, setCustom, period, error } = state;
  return (
    <Stack gap={6}>
      <Group align="flex-end" gap="sm">
        <SegmentedControl
          aria-label={texts.label}
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
          {texts.summary(formatDate(period.from), formatDate(period.to), periodLength(period))}
        </Text>
      )}
    </Stack>
  );
}

function errorMessage(error: PeriodError): string {
  return error === 'tooLong' ? texts.errors.tooLong(MAX_PERIOD_DAYS) : texts.errors[error];
}
