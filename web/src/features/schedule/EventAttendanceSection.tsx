import { useState } from 'react';
import { Alert, Badge, Button, Group, Paper, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import {
  attendanceKeys,
  getEventAttendance,
  type Attendance,
  type AttendanceChange,
  type AttendanceRecord,
  type EventAttendance,
} from './attendanceApi';
import { CorrectAttendanceModal } from './CorrectAttendanceModal';
import { formatDateTime } from './scheduleFormat';
import type { ScheduleEvent } from './scheduleApi';

type Props = {
  event: ScheduleEvent;
  timeZone: string;
};

const attendanceColors: Record<Attendance, string> = {
  UNMARKED: 'gray',
  PRESENT: 'green',
  ABSENT: 'red',
};

/**
 * The event's participants with their attendance and its history. Administrators correct it
 * once the event has started, also after the trainers' time to record it.
 */
export function EventAttendanceSection({ event, timeZone }: Props) {
  const texts = pl.schedule.attendance;
  const attendance = useQuery({
    queryKey: attendanceKeys.event(event.id),
    queryFn: () => getEventAttendance(event.id),
  });
  const [correcting, setCorrecting] = useState<string | null>(null);
  const correctedRecord = attendance.data?.participants.find(
    (participant) => participant.reservationId === correcting,
  );

  return (
    <Stack gap="xs">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {texts.title}
      </Text>
      {attendance.isPending && (
        <Text size="sm" c="dimmed">
          {texts.loading}
        </Text>
      )}
      {attendance.isError && (
        <Alert color="red">
          <Stack gap="xs" align="flex-start">
            <Text size="sm">{texts.loadError}</Text>
            <Button size="xs" variant="light" onClick={() => attendance.refetch()}>
              {texts.retry}
            </Button>
          </Stack>
        </Alert>
      )}
      {attendance.data && (
        <Sheet
          sheet={attendance.data}
          timeZone={timeZone}
          onCorrect={(record) => setCorrecting(record.reservationId)}
        />
      )}
      {correctedRecord && (
        <CorrectAttendanceModal
          eventId={event.id}
          record={correctedRecord}
          onClose={() => setCorrecting(null)}
        />
      )}
    </Stack>
  );
}

function Sheet({
  sheet,
  timeZone,
  onCorrect,
}: {
  sheet: EventAttendance;
  timeZone: string;
  onCorrect: (record: AttendanceRecord) => void;
}) {
  const texts = pl.schedule.attendance;
  const count = (value: Attendance) =>
    sheet.participants.filter((participant) => participant.attendance === value).length;
  const closesAt = formatDateTime(sheet.trainerMarkingClosesAt, timeZone);
  const note =
    sheet.eventStatus === 'CANCELLED'
      ? texts.cancelled
      : !sheet.correctable
        ? texts.notStarted
        : sheet.trainerMarkingOpen
          ? texts.trainerUntil(closesAt)
          : texts.trainerClosed(closesAt);

  return (
    <Stack gap="xs">
      <Text size="sm" c="dimmed">
        {note}
      </Text>
      {sheet.participants.length === 0 ? (
        <Text size="sm">{texts.empty}</Text>
      ) : (
        <>
          {sheet.correctable && (
            <Text size="sm" fw={600}>
              {texts.summary(count('PRESENT'), count('ABSENT'), count('UNMARKED'))}
            </Text>
          )}
          {sheet.participants.map((participant) => (
            <Participant
              key={participant.reservationId}
              participant={participant}
              timeZone={timeZone}
              correctable={sheet.correctable}
              onCorrect={() => onCorrect(participant)}
            />
          ))}
        </>
      )}
    </Stack>
  );
}

function Participant({
  participant,
  timeZone,
  correctable,
  onCorrect,
}: {
  participant: AttendanceRecord;
  timeZone: string;
  correctable: boolean;
  onCorrect: () => void;
}) {
  const texts = pl.schedule.attendance;
  return (
    <Paper withBorder radius="sm" p="xs" aria-label={participant.displayName}>
      <Stack gap={4}>
        <Group justify="space-between" wrap="nowrap" gap="xs">
          <Group gap="xs" wrap="wrap">
            <Text size="sm" fw={600}>
              {participant.displayName}
            </Text>
            {participant.kind === 'CHILD' && (
              <Badge size="xs" variant="outline" color="gray">
                {texts.child}
              </Badge>
            )}
          </Group>
          <Group gap="xs" wrap="nowrap">
            <Badge color={attendanceColors[participant.attendance]} variant="light">
              {texts.statuses[participant.attendance]}
            </Badge>
            {correctable && (
              <Button
                size="compact-xs"
                variant="subtle"
                onClick={onCorrect}
                aria-label={texts.correctFor(participant.displayName)}
              >
                {texts.correct}
              </Button>
            )}
          </Group>
        </Group>
        {participant.attendanceMarkedAt && (
          <Text size="xs" c="dimmed">
            {texts.markedBy(
              participant.attendanceMarkedBy,
              formatDateTime(participant.attendanceMarkedAt, timeZone),
            )}
          </Text>
        )}
        {participant.history.length > 0 && (
          <details>
            <Text component="summary" size="xs" c="blue.7" style={{ cursor: 'pointer' }}>
              {texts.history(participant.history.length)}
            </Text>
            <Stack gap={4} mt={4} component="ol" style={{ paddingLeft: 16, margin: 0 }}>
              {participant.history.map((change, index) => (
                <HistoryEntry key={index} change={change} timeZone={timeZone} />
              ))}
            </Stack>
          </details>
        )}
      </Stack>
    </Paper>
  );
}

function HistoryEntry({ change, timeZone }: { change: AttendanceChange; timeZone: string }) {
  const texts = pl.schedule.attendance;
  const status = (value: Attendance | null) => (value ? texts.statuses[value] : '—');
  return (
    <Text component="li" size="xs">
      {formatDateTime(change.changedAt, timeZone)} · {texts.historyKinds[change.kind]} (
      {change.changedBy ?? texts.unknownAuthor}):{' '}
      {texts.change(status(change.previousAttendance), status(change.newAttendance))}
      {change.reason && (
        <Text component="span" size="xs" c="dimmed" display="block">
          {texts.reasonLabel(change.reason)}
        </Text>
      )}
    </Text>
  );
}
