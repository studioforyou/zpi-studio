import { useState } from 'react';
import { Alert, Badge, Button, Drawer, Group, Paper, Stack, Text, Textarea } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { scheduleKeys } from '@/features/schedule/scheduleApi';
import { formatDateTime } from '@/features/schedule/scheduleFormat';
import {
  chosenTimeOffEvents,
  approveTimeOff,
  rejectTimeOff,
  getTimeOffPlan,
  timeOffCoverKeys,
  timeOffKeys,
  type TimeOff,
} from './timeOffApi';
import { formatPeriod, kindColors, timeOffErrorMessage } from './timeOffFormat';
import { TimeOffSessionPicker } from './TimeOffCoverage';

type TimeOffDecisionDrawerProps = {
  timeOff: TimeOff;
  timeZone: string;
  onClose: () => void;
  onApproved: (entry: TimeOff) => void;
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
export function TimeOffDecisionDrawer({
  timeOff,
  timeZone,
  onClose,
  onApproved,
}: TimeOffDecisionDrawerProps) {
  const texts = pl.timeOff;
  const queryClient = useQueryClient();
  const [note, setNote] = useState('');
  const [choices, setChoices] = useState<Record<string, boolean>>({});
  const plan = useQuery({
    queryKey: timeOffCoverKeys.plan(timeOff.id),
    queryFn: () => getTimeOffPlan(timeOff.id),
  });

  const decide = useMutation({
    mutationFn: (approve: boolean) => {
      const trimmed = note.trim() === '' ? null : note.trim();
      return approve
        ? approveTimeOff(
            timeOff.id,
            trimmed,
            plan.data!.version,
            chosenTimeOffEvents(plan.data, choices),
          )
        : rejectTimeOff(timeOff.id, trimmed, plan.data?.version);
    },
    onSuccess: async (entry, approved) => {
      await queryClient.invalidateQueries({ queryKey: timeOffKeys.all });
      // Approved leave changes the conflicts shown in the schedule.
      await queryClient.invalidateQueries({ queryKey: scheduleKeys.all });
      if (approved) onApproved(entry);
      else onClose();
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: timeOffKeys.all });
    },
  });

  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size={520}
      closeOnEscape={!decide.isPending}
      closeOnClickOutside={!decide.isPending}
      withCloseButton={!decide.isPending}
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
          {plan.isPending ? (
            <Text size="sm">{texts.decision.eventsLoading}</Text>
          ) : plan.isError ? (
            <Alert color="red">
              {texts.decision.eventsError}
              <Button variant="subtle" onClick={() => void plan.refetch()}>
                {pl.timeOffCover.retry}
              </Button>
            </Alert>
          ) : (
            <TimeOffSessionPicker
              plan={plan.data}
              choices={choices}
              onChange={setChoices}
              zone={timeZone}
              disabled={decide.isPending}
            />
          )}
        </Paper>
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
            disabled={decide.isPending || !plan.isSuccess || plan.data.status !== 'REQUESTED'}
            onClick={() => decide.mutate(true)}
          >
            {texts.decision.approve}
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}
