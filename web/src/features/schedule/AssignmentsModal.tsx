import { useState } from 'react';
import { Alert, Button, Group, Modal, Radio, Stack, Text } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { AssignmentFields } from './AssignmentFields';
import { ConflictList } from './IssueList';
import {
  changeEventAssignments,
  scheduleKeys,
  type AssignmentScope,
  type ResourceAssignment,
  type ScheduleEvent,
  type StaffAssignment,
} from './scheduleApi';
import { conflictsOf, eventTitle, formatDateTime, scheduleErrorMessage } from './scheduleFormat';
import { useRoomRules, type ScheduleLookups } from './useScheduleLookups';

type AssignmentsModalProps = {
  event: ScheduleEvent;
  lookups: ScheduleLookups;
  onClose: () => void;
};

/**
 * Changes who runs an event and in which rooms; for an occurrence of a
 * series also of the following occurrences. Mounted fresh for each event.
 */
export function AssignmentsModal({ event, lookups, onClose }: AssignmentsModalProps) {
  const texts = pl.schedule.assignments;
  const queryClient = useQueryClient();
  const [staff, setStaff] = useState<StaffAssignment[]>(
    event.staff.map(({ staffId, role }) => ({ staffId, role })),
  );
  const [resources, setResources] = useState<ResourceAssignment[]>(
    event.resources.map(({ resourceId, blocksWholeRoom }) => ({ resourceId, blocksWholeRoom })),
  );
  const [scope, setScope] = useState<AssignmentScope>('THIS_EVENT');
  const roomRules = useRoomRules(lookups.rooms);

  const save = useMutation({
    mutationFn: () =>
      changeEventAssignments(event.id, { version: event.version, scope, staff, resources }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: scheduleKeys.all });
      onClose();
    },
  });
  const conflicts = conflictsOf(save.error);

  return (
    <Modal opened onClose={onClose} title={`${texts.title}: ${eventTitle(event)}`} size="lg">
      <Stack>
        <Text size="sm" c="dimmed">
          {formatDateTime(event.startsAt, lookups.timeZone)}
        </Text>
        <AssignmentFields
          serviceTypeId={event.serviceTypeId}
          staff={staff}
          onStaffChange={setStaff}
          resources={resources}
          onResourcesChange={setResources}
          lookups={lookups}
          roomRules={roomRules}
          block={event.kind === 'BLOCK'}
        />
        {event.seriesId && (
          <Radio.Group
            label={texts.scope}
            value={scope}
            onChange={(value) => setScope(value as AssignmentScope)}
          >
            <Stack gap="xs" mt={4}>
              <Radio
                value="THIS_EVENT"
                label={texts.scopeThis}
                description={texts.scopeThisDescription}
              />
              <Radio
                value="THIS_AND_FOLLOWING"
                label={texts.scopeFollowing}
                description={texts.scopeFollowingDescription}
              />
            </Stack>
          </Radio.Group>
        )}
        {save.isError && (
          <Alert color="red">
            <Stack gap="xs">
              <Text size="sm">{scheduleErrorMessage(save.error)}</Text>
              {conflicts.length > 0 && <ConflictList conflicts={conflicts} lookups={lookups} />}
            </Stack>
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {texts.cancel}
          </Button>
          <Button onClick={() => save.mutate()} loading={save.isPending}>
            {texts.save}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
