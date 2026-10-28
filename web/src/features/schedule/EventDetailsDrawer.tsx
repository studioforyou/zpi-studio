import type { ReactNode } from 'react';
import { Alert, Badge, Button, Divider, Drawer, Group, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { getRecurrenceSeries, scheduleKeys, type ScheduleEvent } from './scheduleApi';
import { eventTitle, formatTimeRange } from './scheduleFormat';
import { formatDate, formatDayLong, toZoned } from './studioTime';
import type { ScheduleLookups } from './useScheduleLookups';

type EventDetailsDrawerProps = {
  /** The shown event; `null` keeps the drawer closed. */
  event: ScheduleEvent | null;
  lookups: ScheduleLookups;
  onClose: () => void;
  onEditAssignments: (event: ScheduleEvent) => void;
};

const statusColors = { SCHEDULED: 'blue', COMPLETED: 'green', CANCELLED: 'gray' } as const;

export function EventDetailsDrawer({
  event,
  lookups,
  onClose,
  onEditAssignments,
}: EventDetailsDrawerProps) {
  const texts = pl.schedule;
  const seriesId = event?.seriesId ?? null;
  const series = useQuery({
    queryKey: scheduleKeys.series(seriesId ?? ''),
    queryFn: () => getRecurrenceSeries(seriesId!),
    enabled: seriesId !== null,
  });

  return (
    <Drawer
      opened={event !== null}
      onClose={onClose}
      position="right"
      size="md"
      title={event ? eventTitle(event) : ''}
    >
      {event && (
        <Stack gap="md">
          <Group gap="xs">
            <Badge variant="light">{texts.kinds[event.kind]}</Badge>
            <Badge color={statusColors[event.status]} variant="light">
              {texts.statuses[event.status]}
            </Badge>
            <Badge color="gray" variant="outline">
              {texts.visibilities[event.visibility]}
            </Badge>
            {event.seriesId && (
              <Badge color="grape" variant="light">
                {event.isSeriesException ? texts.exception : texts.recurring}
              </Badge>
            )}
          </Group>
          <Detail label={texts.details.when}>
            <Text>{formatDayLong(toZoned(event.startsAt, lookups.timeZone).date)}</Text>
            <Text>{formatTimeRange(event.startsAt, event.endsAt, lookups.timeZone)}</Text>
          </Detail>
          {event.serviceTypeName && (
            <Detail label={texts.details.service}>
              <Text>{event.serviceTypeName}</Text>
            </Detail>
          )}
          <Detail label={texts.details.capacity}>
            <Text>{texts.capacity(event.capacity)}</Text>
          </Detail>
          <Detail label={texts.details.staff}>
            {event.staff.length === 0 ? (
              <Text c={event.kind === 'BLOCK' ? 'dimmed' : 'red.7'}>{texts.details.none}</Text>
            ) : (
              event.staff.map((member) => (
                <Text key={member.staffId}>
                  {member.displayName ?? texts.unknownStaff} — {texts.roles[member.role]}
                </Text>
              ))
            )}
          </Detail>
          <Detail label={texts.details.resources}>
            {event.resources.length === 0 ? (
              <Text c={event.kind === 'BLOCK' ? 'dimmed' : 'red.7'}>{texts.details.none}</Text>
            ) : (
              event.resources.map((resource) => (
                <Text key={resource.resourceId}>
                  {resource.name} ({resource.blocksWholeRoom ? texts.wholeRoom : texts.sharedRoom})
                </Text>
              ))
            )}
          </Detail>
          {event.seriesId && series.data && (
            <Detail label={texts.details.series}>
              <Text>
                {texts.details.seriesRule(
                  texts.weekdaysLong[series.data.weekday - 1],
                  series.data.localStartTime.slice(0, 5),
                  series.data.intervalWeeks,
                )}
              </Text>
              <Text size="sm" c="dimmed">
                {texts.details.seriesRange(
                  formatDate(series.data.startsOn),
                  series.data.endsOn ? formatDate(series.data.endsOn) : null,
                )}
                , {texts.details.seriesCount(series.data.occurrenceCount)}
              </Text>
              {event.isSeriesException && (
                <Text size="sm" c="grape.7">
                  {texts.details.exceptionNote}
                </Text>
              )}
            </Detail>
          )}
          {event.guestName && (
            <Detail label={texts.details.guest}>
              <Text>{event.guestName}</Text>
            </Detail>
          )}
          {event.cancelReason && (
            <Detail label={texts.details.cancelReason}>
              <Text>{event.cancelReason}</Text>
            </Detail>
          )}
          {event.blockReason && (
            <Detail label={texts.details.blockReason}>
              <Text>{event.blockReason}</Text>
            </Detail>
          )}
          <Divider />
          {event.status === 'SCHEDULED' ? (
            <Button onClick={() => onEditAssignments(event)}>
              {texts.details.editAssignments}
            </Button>
          ) : (
            <Alert color="gray">{texts.details.notEditable}</Alert>
          )}
        </Stack>
      )}
    </Drawer>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={2}>
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      {children}
    </Stack>
  );
}
