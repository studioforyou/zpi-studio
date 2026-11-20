import { Alert, Button, Stack, Switch, Text } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import {
  autoCancellationKeys,
  changeForceRun,
  changeGroupAutoCancellation,
  getEventAutoCancellation,
  type EventAutoCancellation,
} from './autoCancellationApi';
import type { ScheduleEvent } from './scheduleApi';
import { formatDateTime } from './scheduleFormat';
import type { ScheduleLookups } from './useScheduleLookups';

type Props = {
  event: ScheduleEvent;
  lookups: ScheduleLookups;
};

function errorMessage(error: Error): string {
  const texts = pl.schedule.autoCancellation.errors;
  if (error instanceof ApiError && error.code === 'EVENT_NOT_EDITABLE') {
    return texts.notEditable;
  }
  if (error instanceof ApiError && error.code === 'NOT_FOUND') {
    return texts.notFound;
  }
  return texts.unknown;
}

/**
 * Whether an upcoming group class will be cancelled for too few participants. An administrator
 * forces the class to run anyway, or switches the rule off for every class of its fixed group.
 */
export function EventAutoCancellationSection({ event, lookups }: Props) {
  const texts = pl.schedule.autoCancellation;
  const queryClient = useQueryClient();
  const status = useQuery({
    queryKey: autoCancellationKeys.event(event.id),
    queryFn: () => getEventAutoCancellation(event.id),
  });
  const forceRun = useMutation({
    mutationFn: (value: boolean) => changeForceRun(event.id, value),
    onSuccess: (updated) => queryClient.setQueryData(autoCancellationKeys.event(event.id), updated),
    onError: () =>
      queryClient.invalidateQueries({ queryKey: autoCancellationKeys.event(event.id) }),
  });
  const group = useMutation({
    mutationFn: ({ groupId, disabled }: { groupId: string; disabled: boolean }) =>
      changeGroupAutoCancellation(groupId, disabled),
    // Every class of the group changes, also the ones shown before.
    onSettled: () =>
      queryClient.invalidateQueries({
        predicate: (query) => query.queryKey[3] === 'auto-cancellation',
      }),
  });
  const error = forceRun.error ?? group.error;

  return (
    <Stack gap="xs">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {texts.title}
      </Text>
      {status.isPending && (
        <Text size="sm" c="dimmed">
          {texts.loading}
        </Text>
      )}
      {status.isError && (
        <Alert color="red">
          <Stack gap="xs" align="flex-start">
            <Text size="sm">{texts.loadError}</Text>
            <Button size="xs" variant="light" onClick={() => status.refetch()}>
              {texts.retry}
            </Button>
          </Stack>
        </Alert>
      )}
      {status.data && (
        <Details
          status={status.data}
          groupName={groupName(status.data.trainingGroupId, lookups)}
          timeZone={lookups.timeZone}
          busy={forceRun.isPending || group.isPending}
          onForceRun={(value) => {
            group.reset();
            forceRun.mutate(value);
          }}
          onDisableForGroup={(groupId, disabled) => {
            forceRun.reset();
            group.mutate({ groupId, disabled });
          }}
        />
      )}
      {error && <Alert color="red">{errorMessage(error)}</Alert>}
    </Stack>
  );
}

function groupName(groupId: string | null, lookups: ScheduleLookups): string {
  return (
    lookups.groups.find((group) => group.id === groupId)?.name ??
    pl.schedule.autoCancellation.unknownGroup
  );
}

function Details({
  status,
  groupName,
  timeZone,
  busy,
  onForceRun,
  onDisableForGroup,
}: {
  status: EventAutoCancellation;
  groupName: string;
  timeZone: string;
  busy: boolean;
  onForceRun: (forceRun: boolean) => void;
  onDisableForGroup: (groupId: string, disabled: boolean) => void;
}) {
  const texts = pl.schedule.autoCancellation;
  const minimum = status.minimumParticipants;
  const belowMinimum = minimum !== null && status.participants < minimum;
  const groupId = status.trainingGroupId;

  return (
    <Stack gap="xs">
      {minimum !== null && (
        <Text size="sm">{texts.participants(status.participants, minimum)}</Text>
      )}
      {status.state === 'PENDING' && status.checkAt && (
        <Text size="sm">{texts.pending(formatDateTime(status.checkAt, timeZone))}</Text>
      )}
      {status.state === 'PENDING' && belowMinimum && (
        <Alert color="orange">{texts.belowMinimum}</Alert>
      )}
      {status.state === 'FORCED' && (
        <Text size="sm" c="green.8">
          {texts.forced}
        </Text>
      )}
      {status.state === 'OFF' && (
        <Text size="sm" c="dimmed">
          {status.disabledForGroup ? texts.offForGroup(groupName) : texts.off}
        </Text>
      )}
      {status.state === 'CLOSED' && (
        <Text size="sm" c="dimmed">
          {texts.closed}
        </Text>
      )}
      {(status.ruleEnabled || status.forceRun) && (
        <Switch
          label={texts.forceRun}
          description={texts.forceRunHint}
          checked={status.forceRun}
          disabled={busy}
          onChange={(change) => onForceRun(change.currentTarget.checked)}
        />
      )}
      {groupId !== null && (status.ruleEnabled || status.disabledForGroup) && (
        <Switch
          label={texts.disableForGroup(groupName)}
          description={texts.disableForGroupHint}
          checked={status.disabledForGroup}
          disabled={busy}
          onChange={(change) => onDisableForGroup(groupId, change.currentTarget.checked)}
        />
      )}
    </Stack>
  );
}
