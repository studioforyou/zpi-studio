import { useState, type ReactNode } from 'react';
import { Alert, Badge, Button, Divider, Drawer, Group, Modal, Stack, Text } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { EventAttendanceSection } from './EventAttendanceSection';
import { EventReservationsSection } from './EventReservationsSection';
import {
  getRecurrenceSeries,
  plannableKinds,
  restoreEventSeries,
  scheduleKeys,
  type ScheduleEvent,
} from './scheduleApi';
import {
  eventTitle,
  formatDateTime,
  formatTimeRange,
  scheduleErrorMessage,
} from './scheduleFormat';
import { formatDate, formatDayLong, toZoned } from './studioTime';
import type { ScheduleLookups } from './useScheduleLookups';

type EventDetailsDrawerProps = {
  /** The shown event; `null` keeps the drawer closed. */
  event: ScheduleEvent | null;
  lookups: ScheduleLookups;
  onClose: () => void;
  onEditAssignments: (event: ScheduleEvent) => void;
  /** Opens the change of this one event, e.g. a single occurrence of a series. */
  onEditOccurrence: (event: ScheduleEvent) => void;
};

function hasStarted(event: ScheduleEvent): boolean {
  return new Date(event.startsAt).getTime() <= Date.now();
}

/** Whether the time, name and capacity of the event can be changed in the panel. */
function isChangeable(event: ScheduleEvent): boolean {
  return (
    event.status === 'SCHEDULED' &&
    (plannableKinds as readonly string[]).includes(event.kind) &&
    !hasStarted(event)
  );
}

const statusColors = { SCHEDULED: 'blue', COMPLETED: 'green', CANCELLED: 'gray' } as const;

/**
 * Places of a scheduled group class are booked and cancelled until it starts; from then on its
 * participants' attendance is shown instead.
 */
function managesBookings(event: ScheduleEvent): boolean {
  return (
    event.kind === 'CLASS' &&
    event.serviceTypeId !== null &&
    event.status === 'SCHEDULED' &&
    new Date(event.startsAt).getTime() > Date.now()
  );
}

export function EventDetailsDrawer({
  event,
  lookups,
  onClose,
  onEditAssignments,
  onEditOccurrence,
}: EventDetailsDrawerProps) {
  const texts = pl.schedule;
  const queryClient = useQueryClient();
  const [confirmRestore, setConfirmRestore] = useState(false);
  const restore = useMutation({
    mutationFn: (target: ScheduleEvent) => restoreEventSeries(target.id, target.version),
    onSuccess: async () => {
      setConfirmRestore(false);
      await queryClient.invalidateQueries({ queryKey: scheduleKeys.all });
    },
  });
  const moved = event?.seriesChange?.usualStartsAt ?? null;
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
          {moved && (
            <Detail label={texts.details.originalTime}>
              <Text c="grape.7" td="line-through">
                {formatDateTime(moved, lookups.timeZone)}
              </Text>
            </Detail>
          )}
          {event.serviceTypeName && (
            <Detail label={texts.details.service}>
              <Text>{event.serviceTypeName}</Text>
            </Detail>
          )}
          {event.kind !== 'BLOCK' && (
            <Detail label={texts.details.capacity}>
              <Text>{texts.capacity(event.capacity)}</Text>
            </Detail>
          )}
          {event.kind !== 'BLOCK' && (
            <Detail label={texts.details.staff}>
              {event.staff.length === 0 ? (
                <Text c="red.7">{texts.details.none}</Text>
              ) : (
                event.staff.map((member) => (
                  <Text key={member.staffId}>
                    {member.displayName ?? texts.unknownStaff} — {texts.roles[member.role]}
                  </Text>
                ))
              )}
            </Detail>
          )}
          {event.seriesChange?.usualInstructors && (
            <Detail label={texts.details.usualStaff}>
              <Text c="grape.7" td="line-through">
                {event.seriesChange.usualInstructors.join(', ')}
              </Text>
            </Detail>
          )}
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
          {event.seriesChange?.usualRooms && (
            <Detail label={texts.details.usualResources}>
              <Text c="grape.7" td="line-through">
                {event.seriesChange.usualRooms.join(', ')}
              </Text>
            </Detail>
          )}
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
          {event.kind !== 'BLOCK' && (
            <>
              <Divider />
              {managesBookings(event) ? (
                <EventReservationsSection event={event} />
              ) : (
                <EventAttendanceSection event={event} timeZone={lookups.timeZone} />
              )}
            </>
          )}
          <Divider />
          {event.status === 'SCHEDULED' ? (
            <Stack gap="xs">
              {isChangeable(event) && (
                <Button onClick={() => onEditOccurrence(event)}>
                  {event.seriesId ? texts.details.editOccurrence : texts.details.editEvent}
                </Button>
              )}
              <Button
                variant={isChangeable(event) ? 'light' : 'filled'}
                onClick={() => onEditAssignments(event)}
              >
                {texts.details.editAssignments}
              </Button>
              {isChangeable(event) && event.isSeriesException && (
                <Button variant="subtle" color="grape" onClick={() => setConfirmRestore(true)}>
                  {texts.details.restoreSeries}
                </Button>
              )}
              {hasStarted(event) && (
                <Text size="xs" c="dimmed">
                  {texts.details.started}
                </Text>
              )}
              {restore.isError && !confirmRestore && (
                <Alert color="red">{scheduleErrorMessage(restore.error)}</Alert>
              )}
            </Stack>
          ) : (
            <Alert color="gray">{texts.details.notEditable}</Alert>
          )}
        </Stack>
      )}
      {event && confirmRestore && (
        <Modal
          opened
          onClose={() => setConfirmRestore(false)}
          title={texts.restore.title}
          size="sm"
          zIndex={400}
        >
          <Stack>
            <Text size="sm">
              {texts.restore.body(formatDateTime(moved ?? event.startsAt, lookups.timeZone))}
            </Text>
            {restore.isError && <Alert color="red">{scheduleErrorMessage(restore.error)}</Alert>}
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setConfirmRestore(false)}>
                {texts.restore.cancel}
              </Button>
              <Button
                color="grape"
                loading={restore.isPending}
                onClick={() => restore.mutate(event)}
              >
                {texts.restore.confirm}
              </Button>
            </Group>
          </Stack>
        </Modal>
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
