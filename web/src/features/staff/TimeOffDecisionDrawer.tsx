import { useState } from 'react';
import { Alert, Badge, Button, Drawer, Group, Paper, Stack, Text, Textarea } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { scheduleKeys } from '@/features/schedule/scheduleApi';
import { eventTitle, formatDateTime } from '@/features/schedule/scheduleFormat';
import { approveTimeOff, rejectTimeOff, timeOffKeys, type TimeOff } from './timeOffApi';
import { formatPeriod, kindColors, timeOffErrorMessage } from './timeOffFormat';
import { useTrainerEventsDuring } from './trainerEvents';

type TimeOffDecisionDrawerProps = {
  timeOff: TimeOff;
  timeZone: string;
  onClose: () => void;
};

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Group gap="md" wrap="nowrap" align="flex-start">
      <Text c="dimmed" w={130} style={{ flexShrink: 0 }}>
        {label}
      </Text>
      <div>{children}</div>
    </Group>
  );
}

/**
 * Shows a trainer's request with the events they run in that period, so the administrator
 * sees what needs a substitute before approving or rejecting it.
 */
export function TimeOffDecisionDrawer({ timeOff, timeZone, onClose }: TimeOffDecisionDrawerProps) {
  const texts = pl.timeOff;
  const queryClient = useQueryClient();
  const [note, setNote] = useState('');
  const { events, own, capped } = useTrainerEventsDuring(timeOff);

  const decide = useMutation({
    mutationFn: (approve: boolean) => {
      const trimmed = note.trim() === '' ? null : note.trim();
      return approve ? approveTimeOff(timeOff.id, trimmed) : rejectTimeOff(timeOff.id, trimmed);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: timeOffKeys.all });
      // Approved leave changes the conflicts shown in the schedule.
      await queryClient.invalidateQueries({ queryKey: scheduleKeys.all });
      onClose();
    },
  });

  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size={520}
      title={
        <Text fw={700} size="lg">
          {texts.decision.title}
        </Text>
      }
    >
      <Stack>
        <Row label={texts.decision.staff}>
          <Text>{timeOff.staffDisplayName}</Text>
        </Row>
        <Row label={texts.decision.kind}>
          <Badge variant="light" color={kindColors[timeOff.kind]}>
            {texts.kinds[timeOff.kind]}
          </Badge>
        </Row>
        <Row label={texts.decision.period}>
          <Text>{formatPeriod(timeOff.startsAt, timeOff.endsAt, timeZone)}</Text>
        </Row>
        {timeOff.reason && (
          <Row label={texts.decision.reason}>
            <Text>{timeOff.reason}</Text>
          </Row>
        )}
        <Row label={texts.decision.requested}>
          <Text>{formatDateTime(timeOff.requestedAt, timeZone)}</Text>
        </Row>
        <Paper withBorder radius="md" p="sm">
          <Text fw={600} mb={6}>
            {texts.decision.eventsTitle}
            {events.isSuccess && ` (${own.length})`}
          </Text>
          {events.isPending ? (
            <Text size="sm">{texts.decision.eventsLoading}</Text>
          ) : events.isError ? (
            <Text size="sm" c="red">
              {texts.decision.eventsError}
            </Text>
          ) : own.length === 0 ? (
            <Text size="sm" c="dimmed">
              {texts.decision.noEvents}
            </Text>
          ) : (
            <Stack gap={4}>
              {own.map((event) => (
                <Text size="sm" key={event.id}>
                  {formatDateTime(event.startsAt, timeZone)} · {eventTitle(event)}
                </Text>
              ))}
            </Stack>
          )}
          {capped && (
            <Text size="xs" c="dimmed" mt={6}>
              {texts.decision.eventsCapped}
            </Text>
          )}
        </Paper>
        <Alert color="blue" variant="light">
          {texts.decision.eventsHint}
        </Alert>
        <Textarea
          label={texts.decision.note}
          placeholder={texts.decision.notePlaceholder}
          maxLength={500}
          rows={3}
          value={note}
          onChange={(event) => setNote(event.currentTarget.value)}
        />
        {decide.error && <Alert color="red">{timeOffErrorMessage(decide.error)}</Alert>}
        <Group justify="flex-end">
          <Button
            color="red"
            variant="light"
            loading={decide.isPending && decide.variables === false}
            disabled={decide.isPending}
            onClick={() => decide.mutate(false)}
          >
            {texts.decision.reject}
          </Button>
          <Button
            color="green"
            loading={decide.isPending && decide.variables === true}
            disabled={decide.isPending}
            onClick={() => decide.mutate(true)}
          >
            {texts.decision.approve}
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}
