import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  Group,
  Loader,
  Modal,
  NumberInput,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { AssignmentFields } from './AssignmentFields';
import { ConflictList, IssueList } from './IssueList';
import {
  changeScheduleEvent,
  previewEventChange,
  scheduleKeys,
  type EventChange,
  type ResourceAssignment,
  type ScheduleEvent,
  type StaffAssignment,
} from './scheduleApi';
import { conflictsOf, eventTitle, formatDateTime, scheduleErrorMessage } from './scheduleFormat';
import { formatDate, minutesToTime, timeToMinutes, toInstant, toZoned } from './studioTime';
import { useRoomRules, type ScheduleLookups } from './useScheduleLookups';

type OccurrenceChangeModalProps = {
  event: ScheduleEvent;
  lookups: ScheduleLookups;
  onClose: () => void;
};

/** Whether a rejected change failed on its capacity. */
function isCapacityError(error: Error | null): boolean {
  return (
    error instanceof ApiError &&
    error.code === 'VALIDATION_FAILED' &&
    (error.problem?.errors ?? []).some((fieldError) => fieldError.field === 'capacity')
  );
}

/**
 * Changes the time, name, capacity, staff and rooms of one event. For an occurrence of a series
 * only that occurrence changes; the series and its other dates stay as they are. The new time
 * can be checked for conflicts before saving. Mounted fresh for each event.
 */
export function OccurrenceChangeModal({ event, lookups, onClose }: OccurrenceChangeModalProps) {
  const texts = pl.schedule.occurrenceChange;
  const timeZone = lookups.timeZone;
  const queryClient = useQueryClient();
  const roomRules = useRoomRules(lookups.rooms);
  const start = toZoned(event.startsAt, timeZone);
  const initialDuration = Math.round(
    (new Date(event.endsAt).getTime() - new Date(event.startsAt).getTime()) / 60000,
  );
  const [date, setDate] = useState(start.date);
  const [startTime, setStartTime] = useState(minutesToTime(start.minutes));
  const [duration, setDuration] = useState<number | string>(initialDuration);
  const [title, setTitle] = useState(event.title ?? '');
  const [capacity, setCapacity] = useState<number | string>(event.capacity ?? '');
  const [staff, setStaff] = useState<StaffAssignment[]>(
    event.staff.map(({ staffId, role }) => ({ staffId, role })),
  );
  const [resources, setResources] = useState<ResourceAssignment[]>(
    event.resources.map(({ resourceId, blocksWholeRoom }) => ({ resourceId, blocksWholeRoom })),
  );
  const [submitted, setSubmitted] = useState(false);
  // A block keeps the rules it was planned with: a title, rooms taken whole, no staff or capacity.
  const isBlock = event.kind === 'BLOCK';

  const durationValid =
    typeof duration === 'number' && Number.isInteger(duration) && duration >= 5 && duration <= 720;
  const capacityValid =
    capacity === '' ||
    (typeof capacity === 'number' && Number.isInteger(capacity) && capacity >= 1);
  const blockValid = !isBlock || (title.trim() !== '' && resources.length > 0);
  const valid = date !== '' && startTime !== '' && durationValid && capacityValid && blockValid;

  function change(): EventChange {
    const startsAt = toInstant(date, startTime, timeZone);
    return {
      version: event.version,
      startsAt: startsAt.toISOString(),
      endsAt: new Date(startsAt.getTime() + Number(duration) * 60000).toISOString(),
      title: title.trim() === '' ? null : title.trim(),
      capacity: isBlock || capacity === '' ? null : Number(capacity),
      staff: isBlock ? [] : staff,
      resources: isBlock
        ? resources.map((resource) => ({ ...resource, blocksWholeRoom: true }))
        : resources,
    };
  }

  const preview = useMutation({ mutationFn: () => previewEventChange(event.id, change()) });
  const save = useMutation({
    mutationFn: () => changeScheduleEvent(event.id, change()),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: scheduleKeys.all });
      onClose();
    },
  });

  function handleSubmit(formEvent: FormEvent) {
    formEvent.preventDefault();
    setSubmitted(true);
    if (valid) {
      save.mutate();
    }
  }

  function handleCheck() {
    setSubmitted(true);
    if (valid) {
      preview.mutate();
    }
  }

  /** Any edit makes an earlier check outdated. */
  function edited<T>(setter: (value: T) => void) {
    return (value: T) => {
      preview.reset();
      setter(value);
    };
  }

  const error = (invalid: boolean, message: string) => (submitted && invalid ? message : undefined);
  const occurrence = preview.data?.occurrences[0];
  const blocking = occurrence?.issues.filter((issue) => issue.blocking) ?? [];
  const softIssues = [
    ...(occurrence?.issues.filter((issue) => !issue.blocking) ?? []),
    ...(preview.data?.warnings ?? []),
  ];
  const conflicts = conflictsOf(save.error);
  const isOccurrence = event.seriesId !== null;
  const moved = event.seriesChange?.usualStartsAt ?? null;

  return (
    <Modal
      opened
      onClose={onClose}
      title={`${isOccurrence ? texts.titleOccurrence : texts.titleEvent}: ${eventTitle(event)}`}
      size="lg"
    >
      <form onSubmit={handleSubmit} noValidate>
        <Stack>
          {isOccurrence && (
            <Alert color="grape" variant="light">
              <Text size="sm">
                {texts.onlyThis(formatDate(toZoned(event.startsAt, timeZone).date))}
              </Text>
              {moved && (
                <Text size="xs" c="dimmed" mt={4}>
                  {texts.originalTime(formatDateTime(moved, timeZone))}
                </Text>
              )}
            </Alert>
          )}
          <SimpleGrid cols={{ base: 1, sm: 3 }}>
            <TextInput
              type="date"
              label={texts.date}
              required
              value={date}
              onChange={(input) => edited(setDate)(input.currentTarget.value)}
              error={error(date === '', texts.required)}
            />
            <TextInput
              type="time"
              label={texts.startTime}
              required
              value={startTime}
              onChange={(input) => edited(setStartTime)(input.currentTarget.value)}
              error={error(startTime === '', texts.required)}
            />
            <NumberInput
              label={texts.duration}
              inputWrapperOrder={['label', 'input', 'description', 'error']}
              required
              min={5}
              max={720}
              step={15}
              allowDecimal={false}
              value={duration}
              onChange={edited(setDuration)}
              description={
                durationValid && startTime !== ''
                  ? texts.endsAt(
                      minutesToTime((timeToMinutes(startTime) + Number(duration)) % 1440),
                    )
                  : undefined
              }
              error={error(!durationValid, texts.durationRange)}
            />
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, sm: isBlock ? 1 : 2 }}>
            <TextInput
              label={texts.eventTitle}
              placeholder={event.serviceTypeName ?? undefined}
              maxLength={120}
              required={isBlock}
              value={title}
              onChange={(input) => setTitle(input.currentTarget.value)}
              error={error(isBlock && title.trim() === '', texts.required)}
            />
            {!isBlock && (
              <NumberInput
                label={texts.capacity}
                description={texts.capacityDescription}
                inputWrapperOrder={['label', 'input', 'description', 'error']}
                min={1}
                max={32767}
                allowDecimal={false}
                value={capacity}
                onChange={edited(setCapacity)}
                error={error(!capacityValid, texts.positiveNumber)}
              />
            )}
          </SimpleGrid>
          <AssignmentFields
            block={isBlock}
            serviceTypeId={event.serviceTypeId}
            staff={staff}
            onStaffChange={edited(setStaff)}
            resources={resources}
            onResourcesChange={edited(setResources)}
            lookups={lookups}
            roomRules={roomRules}
          />
          {submitted && isBlock && resources.length === 0 && (
            <Text size="xs" c="red">
              {texts.blockNeedsRoom}
            </Text>
          )}
          {preview.isPending && (
            <Group gap="xs">
              <Loader size="xs" />
              <Text size="sm">{texts.checking}</Text>
            </Group>
          )}
          {preview.isError && <Alert color="red">{scheduleErrorMessage(preview.error)}</Alert>}
          {preview.isSuccess && (
            <Alert color={blocking.length > 0 ? 'red' : softIssues.length > 0 ? 'yellow' : 'green'}>
              <Stack gap="xs">
                {blocking.length > 0 ? (
                  <>
                    <Text size="sm">{texts.blocked}</Text>
                    <IssueList issues={blocking} lookups={lookups} />
                  </>
                ) : (
                  <Text size="sm">{texts.free}</Text>
                )}
                {softIssues.length > 0 && (
                  <>
                    <Text size="sm">{texts.warnings}</Text>
                    <IssueList issues={softIssues} lookups={lookups} />
                  </>
                )}
              </Stack>
            </Alert>
          )}
          {save.isError && (
            <Alert color="red">
              <Stack gap="xs">
                <Text size="sm">
                  {isCapacityError(save.error)
                    ? texts.capacityError
                    : scheduleErrorMessage(save.error)}
                </Text>
                {conflicts.length > 0 && <ConflictList conflicts={conflicts} lookups={lookups} />}
              </Stack>
            </Alert>
          )}
          <Group justify="space-between">
            <Button variant="light" onClick={handleCheck} loading={preview.isPending}>
              {texts.check}
            </Button>
            <Group>
              <Button variant="default" onClick={onClose}>
                {texts.cancel}
              </Button>
              <Button type="submit" loading={save.isPending}>
                {texts.save}
              </Button>
            </Group>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
