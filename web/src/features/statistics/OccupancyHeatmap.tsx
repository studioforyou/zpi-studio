import { Box, Group, Stack, Text, Tooltip } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { chartColors, occupancyScale, occupancyScaleText } from './chartColors';
import type { ClassAttendanceClass } from './attendanceReportsApi';
import {
  formatNumber,
  formatPercent,
  heatKey,
  occupancy,
  occupancyGrid,
  occupancyStep,
  type HeatCell,
} from './statisticsMath';

const texts = pl.statistics.attendance;

const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7];

/**
 * Average occupancy of the classes per weekday and start hour, darker where fuller: a week as in
 * the schedule, days in columns and start hours in rows.
 */
export function OccupancyHeatmap({ classes }: { classes: ClassAttendanceClass[] }) {
  const { hours, cells } = occupancyGrid(classes);
  return (
    <Stack gap="sm">
      <Box style={{ overflowX: 'auto' }}>
        <Box
          role="table"
          aria-label={texts.heatmapTitle}
          style={{
            display: 'grid',
            gridTemplateColumns: `56px repeat(${WEEKDAYS.length}, minmax(64px, 1fr))`,
            gap: 2,
            minWidth: 56 + WEEKDAYS.length * 66,
          }}
        >
          <Box role="row" style={{ display: 'contents' }}>
            <Box role="columnheader" />
            {WEEKDAYS.map((weekday) => (
              <Text
                key={weekday}
                role="columnheader"
                aria-label={texts.weekdays[weekday - 1]}
                size="sm"
                c="dimmed"
                ta="center"
                pb={4}
              >
                {texts.weekdaysShort[weekday - 1]}
              </Text>
            ))}
          </Box>
          {hours.map((hour) => (
            <Box key={hour} role="row" style={{ display: 'contents' }}>
              <Text
                role="rowheader"
                size="xs"
                c="dimmed"
                style={{ alignSelf: 'center', fontVariantNumeric: 'tabular-nums' }}
              >
                {hourLabel(hour)}
              </Text>
              {WEEKDAYS.map((weekday) => (
                <HeatmapCell
                  key={weekday}
                  weekday={weekday}
                  hour={hour}
                  cell={cells.get(heatKey(weekday, hour))}
                />
              ))}
            </Box>
          ))}
        </Box>
      </Box>
      <HeatmapLegend />
    </Stack>
  );
}

function HeatmapCell({
  weekday,
  hour,
  cell,
}: {
  weekday: number;
  hour: number;
  cell: HeatCell | undefined;
}) {
  if (!cell) {
    return <Box role="cell" h={36} bg="var(--mantine-color-gray-0)" style={{ borderRadius: 4 }} />;
  }
  const value = occupancy(cell.counts);
  const step = value === null ? null : occupancyStep(value);
  const shown = value === null ? texts.heatmapNoLimit : formatPercent(value);
  const details =
    value === null
      ? cell.titles.join(', ')
      : `${cell.titles.join(', ')} (${formatNumber(cell.counts.placesBooked)} / ${formatNumber(cell.counts.places)})`;
  const label = texts.heatmapCell(texts.weekdays[weekday - 1], hourLabel(hour), shown, details);
  return (
    <Tooltip
      label={label}
      multiline
      maw={320}
      withArrow
      events={{ hover: true, focus: true, touch: true }}
    >
      <Box
        role="cell"
        tabIndex={0}
        aria-label={label}
        h={36}
        style={{
          borderRadius: 4,
          background: step === null ? chartColors.noLimit : occupancyScale[step],
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'default',
        }}
      >
        <Text size="xs" fw={600} c={step === null ? 'dimmed' : occupancyScaleText[step]}>
          {value === null ? '—' : formatPercent(value)}
        </Text>
      </Box>
    </Tooltip>
  );
}

const SCALE_LABELS = ['0–20%', '20–40%', '40–60%', '60–80%', '80%+'];

function HeatmapLegend() {
  return (
    <Group gap="md" wrap="wrap" aria-label={texts.heatmapLegend}>
      <Text size="sm" c="dimmed">
        {texts.heatmapLegend}
      </Text>
      {occupancyScale.map((color, step) => (
        <Group key={color} gap={6} wrap="nowrap">
          <Box w={14} h={14} style={{ borderRadius: 3, background: color }} />
          <Text size="sm" c="dimmed">
            {SCALE_LABELS[step]}
          </Text>
        </Group>
      ))}
      <Group gap={6} wrap="nowrap">
        <Box w={14} h={14} style={{ borderRadius: 3, background: chartColors.noLimit }} />
        <Text size="sm" c="dimmed">
          {texts.heatmapNoLimit}
        </Text>
      </Group>
    </Group>
  );
}

function hourLabel(hour: number): string {
  return `${String(hour).padStart(2, '0')}:00`;
}
