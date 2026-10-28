import type { MouseEvent } from 'react';
import { Box, Group, ScrollArea, Text, Tooltip, UnstyledButton } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { layoutDay, type TimedItem } from './eventLayout';
import type { ScheduleEvent } from './scheduleApi';
import { staffingGapsOf } from './scheduleFilters';
import { eventColor, eventTitle, formatTimeRange, type ColorBy } from './scheduleFormat';
import { formatDayShort, formatTime, minutesToTime, toZoned } from './studioTime';
import type { ScheduleLookups } from './useScheduleLookups';

const HOUR_HEIGHT = 48;
const DAY_MINUTES = 24 * 60;

type TimeGridViewProps = {
  days: string[];
  events: ScheduleEvent[];
  lookups: ScheduleLookups;
  colorBy: ColorBy;
  today: string;
  onSelect: (event: ScheduleEvent) => void;
  onShowDay: (day: string) => void;
  onCreateAt: (day: string, time: string) => void;
};

/** Pieces of each event per shown day; an event past midnight continues on the next day. */
function itemsByDay(events: ScheduleEvent[], days: string[], timeZone: string) {
  const byDay = new Map<string, TimedItem<ScheduleEvent>[]>(days.map((day) => [day, []]));
  for (const event of events) {
    const start = toZoned(event.startsAt, timeZone);
    const end = toZoned(event.endsAt, timeZone);
    if (byDay.has(start.date)) {
      const endMinutes = end.date === start.date ? end.minutes : DAY_MINUTES;
      byDay
        .get(start.date)!
        .push({ item: event, start: start.minutes, end: Math.max(endMinutes, start.minutes + 15) });
    }
    if (end.date !== start.date && end.minutes > 0 && byDay.has(end.date)) {
      byDay.get(end.date)!.push({ item: event, start: 0, end: end.minutes });
    }
  }
  return byDay;
}

/** Days side by side with an hour scale; overlapping events share the width of their day. */
export function TimeGridView({
  days,
  events,
  lookups,
  colorBy,
  today,
  onSelect,
  onShowDay,
  onCreateAt,
}: TimeGridViewProps) {
  const byDay = itemsByDay(events, days, lookups.timeZone);
  const allItems = [...byDay.values()].flat();
  const firstHour = Math.min(6, ...allItems.map((item) => Math.floor(item.start / 60)));
  const lastHour = Math.max(22, ...allItems.map((item) => Math.ceil(item.end / 60)));
  const hours = Array.from({ length: lastHour - firstHour }, (_, index) => firstHour + index);
  const height = hours.length * HOUR_HEIGHT;
  const columns = `56px repeat(${days.length}, minmax(${days.length > 1 ? 120 : 240}px, 1fr))`;

  function handleColumnClick(day: string, event: MouseEvent<HTMLDivElement>) {
    if (event.target !== event.currentTarget) {
      return;
    }
    const halfHours = Math.floor((event.nativeEvent.offsetY / HOUR_HEIGHT) * 2);
    onCreateAt(day, minutesToTime(firstHour * 60 + halfHours * 30));
  }

  return (
    <ScrollArea type="auto">
      <Box style={{ display: 'grid', gridTemplateColumns: columns, minWidth: 'fit-content' }}>
        <Box />
        {days.map((day) => (
          <UnstyledButton
            key={day}
            onClick={() => onShowDay(day)}
            py={6}
            style={{
              textAlign: 'center',
              borderBottom: '1px solid var(--mantine-color-gray-3)',
              background: day === today ? 'var(--mantine-color-blue-0)' : undefined,
            }}
          >
            <Text size="sm" fw={day === today ? 700 : 500}>
              {formatDayShort(day)}
            </Text>
          </UnstyledButton>
        ))}
        <Box style={{ position: 'relative', height }}>
          {hours.map((hour, index) => (
            <Text
              key={hour}
              size="xs"
              c="dimmed"
              style={{ position: 'absolute', top: index * HOUR_HEIGHT - 7, right: 6 }}
            >
              {index === 0 ? '' : `${String(hour).padStart(2, '0')}:00`}
            </Text>
          ))}
        </Box>
        {days.map((day) => (
          <Box
            key={day}
            onClick={(event) => handleColumnClick(day, event)}
            title={pl.schedule.newHere}
            style={{
              position: 'relative',
              height,
              cursor: 'copy',
              borderLeft: '1px solid var(--mantine-color-gray-3)',
              backgroundImage: `repeating-linear-gradient(to bottom, var(--mantine-color-gray-2) 0, var(--mantine-color-gray-2) 1px, transparent 1px, transparent ${HOUR_HEIGHT}px)`,
              backgroundColor: day === today ? 'var(--mantine-color-blue-0)' : undefined,
            }}
          >
            {layoutDay(byDay.get(day) ?? []).map((placed) => (
              <EventBlock
                key={`${placed.item.id}-${placed.start}`}
                event={placed.item}
                lookups={lookups}
                colorBy={colorBy}
                onSelect={onSelect}
                narrow={placed.columns >= 3}
                style={{
                  top: ((placed.start - firstHour * 60) / 60) * HOUR_HEIGHT + 1,
                  height: Math.max(20, ((placed.end - placed.start) / 60) * HOUR_HEIGHT - 2),
                  left: `calc(${(placed.column / placed.columns) * 100}% + 2px)`,
                  width: `calc(${100 / placed.columns}% - 4px)`,
                }}
              />
            ))}
          </Box>
        ))}
      </Box>
    </ScrollArea>
  );
}

type EventBlockProps = {
  event: ScheduleEvent;
  lookups: ScheduleLookups;
  colorBy: ColorBy;
  onSelect: (event: ScheduleEvent) => void;
  /** Shares its day with two or more overlapping events, so only the start time fits. */
  narrow: boolean;
  style: { top: number; height: number; left: string; width: string };
};

function EventBlock({ event, lookups, colorBy, onSelect, narrow, style }: EventBlockProps) {
  const color = eventColor(event, colorBy, lookups.serviceTypeById, lookups.staffById);
  const title = eventTitle(event);
  const time = formatTimeRange(event.startsAt, event.endsAt, lookups.timeZone);
  const cancelled = event.status === 'CANCELLED';
  // A short event has room for one line only: its start time and title.
  const compact = style.height < 40;
  const gaps = staffingGapsOf(event);
  const staffNames = event.staff.map((member) => member.displayName).join(', ');
  const resourceNames = event.resources.map((resource) => resource.name).join(', ');
  return (
    <Tooltip
      label={[title, time, staffNames, resourceNames].filter(Boolean).join(' · ')}
      openDelay={400}
      withinPortal
    >
      <UnstyledButton
        onClick={() => onSelect(event)}
        aria-label={`${title}, ${time}`}
        px={6}
        py={2}
        style={{
          position: 'absolute',
          ...style,
          overflow: 'hidden',
          borderRadius: 6,
          borderLeft: `3px solid ${color}`,
          background: `color-mix(in srgb, ${color} 16%, white)`,
          opacity: cancelled ? 0.55 : 1,
          textDecoration: cancelled ? 'line-through' : undefined,
          boxShadow: '0 1px 2px rgba(0, 0, 0, 0.08)',
        }}
      >
        <Group gap={4} wrap="nowrap" style={{ whiteSpace: 'nowrap' }}>
          <Text size="xs" fw={600} lh={1.3}>
            {compact || narrow ? formatTime(event.startsAt, lookups.timeZone) : time}
          </Text>
          {compact && (
            <Text size="xs" fw={600} lh={1.3} truncate>
              {title}
            </Text>
          )}
          {event.seriesId && (
            <Text size="xs" c="dimmed" lh={1.3} title={pl.schedule.recurring}>
              ↻
            </Text>
          )}
          {gaps.length > 0 && (
            <Text
              size="xs"
              c="red.7"
              fw={700}
              lh={1.3}
              title={gaps.map((gap) => pl.schedule.filters.gapOptions[gap]).join(', ')}
            >
              !
            </Text>
          )}
        </Group>
        {!compact && (
          <Text size="xs" fw={600} lh={1.25} truncate>
            {title}
          </Text>
        )}
        {!compact && staffNames && (
          <Text size="xs" c="dimmed" lh={1.25} truncate>
            {staffNames}
          </Text>
        )}
        {!compact && resourceNames && (
          <Text size="xs" c="dimmed" lh={1.25} truncate>
            {resourceNames}
          </Text>
        )}
      </UnstyledButton>
    </Tooltip>
  );
}
