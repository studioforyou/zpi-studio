import { Box, Group, Stack, Text, UnstyledButton } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import type { ScheduleEvent } from './scheduleApi';
import { eventColor, eventTitle, type ColorBy } from './scheduleFormat';
import { dateRange, formatTime, toZoned } from './studioTime';
import type { ScheduleLookups } from './useScheduleLookups';

const SHOWN_PER_DAY = 3;

type MonthViewProps = {
  start: string;
  end: string;
  /** The month the view is about; days of other months are dimmed. */
  month: string;
  events: ScheduleEvent[];
  lookups: ScheduleLookups;
  colorBy: ColorBy;
  today: string;
  onSelect: (event: ScheduleEvent) => void;
  onShowDay: (day: string) => void;
};

/** Whole weeks of a month; each day lists its first events and how many more it has. */
export function MonthView({
  start,
  end,
  month,
  events,
  lookups,
  colorBy,
  today,
  onSelect,
  onShowDay,
}: MonthViewProps) {
  const byDay = new Map<string, ScheduleEvent[]>();
  for (const event of events) {
    const day = toZoned(event.startsAt, lookups.timeZone).date;
    byDay.set(day, [...(byDay.get(day) ?? []), event]);
  }
  return (
    <Box
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(7, minmax(0, 1fr))',
        border: '1px solid var(--mantine-color-gray-3)',
        borderRadius: 8,
        overflow: 'hidden',
      }}
    >
      {pl.schedule.weekdaysShort.map((weekday) => (
        <Text
          key={weekday}
          size="xs"
          fw={600}
          ta="center"
          py={4}
          bg="gray.0"
          style={{ borderBottom: '1px solid var(--mantine-color-gray-3)' }}
        >
          {weekday}
        </Text>
      ))}
      {dateRange(start, end).map((day) => {
        const dayEvents = byDay.get(day) ?? [];
        const inMonth = day.slice(0, 7) === month.slice(0, 7);
        return (
          <Stack
            key={day}
            gap={2}
            p={4}
            mih={110}
            style={{
              borderTop: '1px solid var(--mantine-color-gray-2)',
              borderLeft: '1px solid var(--mantine-color-gray-2)',
              background: day === today ? 'var(--mantine-color-blue-0)' : undefined,
            }}
          >
            <UnstyledButton onClick={() => onShowDay(day)} aria-label={day}>
              <Text size="xs" fw={day === today ? 700 : 500} c={inMonth ? undefined : 'dimmed'}>
                {Number(day.slice(8))}
              </Text>
            </UnstyledButton>
            {dayEvents.slice(0, SHOWN_PER_DAY).map((event) => {
              const color = eventColor(event, colorBy, lookups.serviceTypeById, lookups.staffById);
              return (
                <UnstyledButton
                  key={event.id}
                  onClick={() => onSelect(event)}
                  aria-label={`${eventTitle(event)}, ${formatTime(event.startsAt, lookups.timeZone)}`}
                  style={{
                    borderRadius: 4,
                    opacity: event.status === 'CANCELLED' ? 0.55 : 1,
                    textDecoration: event.status === 'CANCELLED' ? 'line-through' : undefined,
                  }}
                >
                  <Group gap={4} wrap="nowrap">
                    <Box
                      w={8}
                      h={8}
                      style={{ borderRadius: 4, background: color, flexShrink: 0 }}
                    />
                    <Text size="xs" truncate>
                      {formatTime(event.startsAt, lookups.timeZone)} {eventTitle(event)}
                    </Text>
                  </Group>
                </UnstyledButton>
              );
            })}
            {dayEvents.length > SHOWN_PER_DAY && (
              <UnstyledButton onClick={() => onShowDay(day)}>
                <Text size="xs" c="blue">
                  {pl.schedule.more(dayEvents.length - SHOWN_PER_DAY)}
                </Text>
              </UnstyledButton>
            )}
          </Stack>
        );
      })}
    </Box>
  );
}
