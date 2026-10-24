import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Chip,
  Group,
  Loader,
  Modal,
  NumberInput,
  ScrollArea,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { AssignmentFields } from './AssignmentFields';
import { ConflictList, IssueList } from './IssueList';
import {
  createRecurrenceSeries,
  createScheduleEvent,
  eventVisibilities,
  plannableKinds,
  previewRecurrenceSeries,
  previewScheduleEvent,
  scheduleKeys,
  type EventDetailsInput,
  type EventVisibility,
  type OccurrencePreview,
  type OneOffEventInput,
  type PlanPreview,
  type PlannableKind,
  type ResourceAssignment,
  type SeriesInput,
  type StaffAssignment,
} from './scheduleApi';
import { conflictsOf, formatTimeRange, scheduleErrorMessage } from './scheduleFormat';
import {
  addDays,
  formatDate,
  isoWeekday,
  minutesToTime,
  timeToMinutes,
  toInstant,
} from './studioTime';
import { useRoomRules, type ScheduleLookups } from './useScheduleLookups';

type Mode = 'oneOff' | 'recurring';

/** The event kind a service type's category suggests. */
const kindOfCategory: Record<string, PlannableKind> = {
  GROUP_CLASS: 'CLASS',
  WORKSHOP: 'WORKSHOP',
  PERSONAL_TRAINING: 'PERSONAL_TRAINING',
};

type EventFormModalProps = {
  lookups: ScheduleLookups;
  /** Pre-filled date, e.g. the day clicked in the calendar. */
  initialDate: string;
  initialTime?: string;
  onClose: () => void;
  /** Called after saving with the (first) day of the new event. */
  onSaved: (date: string) => void;
};

function hasBlockingIssue(occurrence: OccurrencePreview): boolean {
  return occurrence.issues.some((issue) => issue.blocking);
}

/**
 * Creates a one-off or a recurring event in two steps: its details with the staff and rooms,
 * then every date it would take with the conflicts found by the backend. Dates of a recurring
 * event can be left out, e.g. those with a conflict.
 */
export function EventFormModal({
  lookups,
  initialDate,
  initialTime,
  onClose,
  onSaved,
}: EventFormModalProps) {
  const texts = pl.schedule.form;
  const timeZone = lookups.timeZone;
  const queryClient = useQueryClient();
  const roomRules = useRoomRules(lookups.rooms);
  const [step, setStep] = useState<'details' | 'preview'>('details');
  const [mode, setMode] = useState<Mode>('oneOff');
  const [serviceTypeId, setServiceTypeId] = useState<string | null>(null);
  const [kind, setKind] = useState<PlannableKind>('CLASS');
  const [title, setTitle] = useState('');
  const [date, setDate] = useState(initialDate);
  const [startTime, setStartTime] = useState(initialTime ?? '18:00');
  const [duration, setDuration] = useState<number | string>(60);
  const [weekdays, setWeekdays] = useState<string[]>([String(isoWeekday(initialDate))]);
  const [intervalWeeks, setIntervalWeeks] = useState('1');
  const [endsOn, setEndsOn] = useState(addDays(initialDate, 7 * 12 - 1));
  const [capacity, setCapacity] = useState<number | string>('');
  const [visibility, setVisibility] = useState<EventVisibility>('PUBLIC');
  const [staff, setStaff] = useState<StaffAssignment[]>([]);
  const [resources, setResources] = useState<ResourceAssignment[]>([]);
  const [excluded, setExcluded] = useState<string[]>([]);
  const [submitted, setSubmitted] = useState(false);

  const recurring = mode === 'recurring';
  const durationValid =
    typeof duration === 'number' && Number.isInteger(duration) && duration >= 5 && duration <= 720;
  const capacityValid =
    capacity === '' ||
    (typeof capacity === 'number' && Number.isInteger(capacity) && capacity >= 1);
  const endsOnValid = !recurring || (endsOn !== '' && endsOn >= date);
  const valid =
    serviceTypeId !== null &&
    date !== '' &&
    startTime !== '' &&
    durationValid &&
    capacityValid &&
    endsOnValid &&
    (!recurring || weekdays.length > 0);

  function details(): EventDetailsInput {
    return {
      kind,
      serviceTypeId: serviceTypeId!,
      title: title.trim() === '' ? null : title.trim(),
      capacity: capacity === '' ? null : Number(capacity),
      visibility,
      staff,
      resources,
    };
  }

  function oneOffInput(): OneOffEventInput {
    const start = toInstant(date, startTime, timeZone);
    return {
      ...details(),
      startsAt: start.toISOString(),
      endsAt: new Date(start.getTime() + Number(duration) * 60000).toISOString(),
    };
  }

  function seriesInput(): SeriesInput {
    return {
      ...details(),
      weekdays: weekdays.map(Number).sort((a, b) => a - b),
      localStartTime: startTime,
      durationMinutes: Number(duration),
      intervalWeeks: Number(intervalWeeks),
      startsOn: date,
      endsOn,
      excludedDates: [...excluded].sort(),
    };
  }

  const preview = useMutation({
    mutationFn: (): Promise<PlanPreview> =>
      recurring ? previewRecurrenceSeries(seriesInput()) : previewScheduleEvent(oneOffInput()),
  });
  const save = useMutation({
    mutationFn: async () => {
      if (recurring) {
        await createRecurrenceSeries(seriesInput());
      } else {
        await createScheduleEvent(oneOffInput());
      }
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: scheduleKeys.all });
      onSaved(date);
    },
  });

  function selectServiceType(id: string | null) {
    setServiceTypeId(id);
    const serviceType = id ? lookups.serviceTypeById.get(id) : undefined;
    if (!serviceType) {
      return;
    }
    const suggestedKind = kindOfCategory[serviceType.category] ?? 'CLASS';
    const personal = suggestedKind === 'PERSONAL_TRAINING';
    setKind(suggestedKind);
    setVisibility(personal ? 'PRIVATE' : 'PUBLIC');
    setDuration(serviceType.defaultDurationMinutes);
    setCapacity(personal ? 1 : (serviceType.maxParticipants ?? ''));
    const hasRoom = resources.length > 0;
    const defaultRoom = lookups.rooms.find(
      (room) =>
        room.isActive &&
        roomRules.get(room.id)?.some((rule) => rule.serviceTypeId === id && rule.isDefault),
    );
    if (!hasRoom && defaultRoom) {
      setResources([...resources, { resourceId: defaultRoom.id, blocksWholeRoom: !personal }]);
    }
  }

  function selectKind(value: PlannableKind) {
    setKind(value);
    setVisibility(value === 'PERSONAL_TRAINING' ? 'PRIVATE' : 'PUBLIC');
  }

  function handleNext(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (!valid) {
      return;
    }
    save.reset();
    setStep('preview');
    preview.mutate();
  }

  function toggleDate(day: string, include: boolean) {
    setExcluded((current) =>
      include ? current.filter((value) => value !== day) : [...current, day],
    );
  }

  const error = (invalid: boolean, message: string) => (submitted && invalid ? message : undefined);
  const serviceOptions = lookups.serviceTypes
    .filter((type) => type.isActive)
    .map((type) => ({ value: type.id, label: type.name }))
    .sort((a, b) => a.label.localeCompare(b.label, 'pl'));
  const selectedService = serviceTypeId ? lookups.serviceTypeById.get(serviceTypeId) : undefined;
  const occurrences = preview.data?.occurrences ?? [];
  const included = occurrences.filter((occurrence) => !excluded.includes(occurrence.date));
  const conflicting = included.filter(hasBlockingIssue);
  const saveConflicts = conflictsOf(save.error);
  const stepLabel = step === 'details' ? texts.stepDetails : texts.stepPreview;

  return (
    <Modal opened onClose={onClose} title={`${texts.title} – ${stepLabel}`} size="xl">
      {step === 'details' ? (
        <form onSubmit={handleNext} noValidate>
          <Stack>
            <SegmentedControl
              aria-label={texts.type}
              value={mode}
              onChange={(value) => setMode(value as Mode)}
              data={[
                { value: 'oneOff', label: texts.oneOff },
                { value: 'recurring', label: texts.recurring },
              ]}
            />
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <Select
                label={texts.serviceType}
                placeholder={texts.serviceTypePlaceholder}
                required
                searchable
                data={serviceOptions}
                value={serviceTypeId}
                onChange={selectServiceType}
                error={error(serviceTypeId === null, texts.required)}
              />
              <Select
                label={texts.kind}
                required
                allowDeselect={false}
                data={plannableKinds.map((value) => ({
                  value,
                  label: pl.schedule.kinds[value],
                }))}
                value={kind}
                onChange={(value) => value && selectKind(value as PlannableKind)}
              />
            </SimpleGrid>
            <TextInput
              label={texts.eventTitle}
              placeholder={selectedService?.name ?? texts.eventTitlePlaceholder}
              maxLength={120}
              value={title}
              onChange={(event) => setTitle(event.currentTarget.value)}
            />
            {recurring && (
              <Stack gap={4}>
                <Text size="sm" fw={500}>
                  {texts.weekdays}
                </Text>
                <Chip.Group multiple value={weekdays} onChange={setWeekdays}>
                  <Group gap="xs">
                    {pl.schedule.weekdaysShort.map((label, index) => (
                      <Chip key={label} value={String(index + 1)} size="sm">
                        {label}
                      </Chip>
                    ))}
                  </Group>
                </Chip.Group>
                {submitted && weekdays.length === 0 && (
                  <Text size="xs" c="red">
                    {texts.chooseWeekday}
                  </Text>
                )}
              </Stack>
            )}
            <SimpleGrid cols={{ base: 1, sm: recurring ? 4 : 3 }}>
              <TextInput
                type="date"
                label={recurring ? texts.startsOn : texts.date}
                required
                value={date}
                onChange={(event) => setDate(event.currentTarget.value)}
                error={error(date === '', texts.required)}
              />
              {recurring && (
                <TextInput
                  type="date"
                  label={texts.endsOn}
                  description={texts.endsOnDescription}
                  inputWrapperOrder={['label', 'input', 'description', 'error']}
                  required
                  value={endsOn}
                  onChange={(event) => setEndsOn(event.currentTarget.value)}
                  error={error(
                    !endsOnValid,
                    endsOn === '' ? texts.required : texts.endsBeforeStart,
                  )}
                />
              )}
              <TextInput
                type="time"
                label={texts.startTime}
                required
                value={startTime}
                onChange={(event) => setStartTime(event.currentTarget.value)}
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
                onChange={setDuration}
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
            {recurring && (
              <Select
                label={texts.interval}
                allowDeselect={false}
                data={[1, 2, 3, 4].map((weeks) => ({
                  value: String(weeks),
                  label: texts.intervalOptions(weeks),
                }))}
                value={intervalWeeks}
                onChange={(value) => value && setIntervalWeeks(value)}
                w={220}
              />
            )}
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <NumberInput
                label={texts.capacity}
                description={texts.capacityDescription}
                inputWrapperOrder={['label', 'input', 'description', 'error']}
                min={1}
                max={32767}
                allowDecimal={false}
                value={capacity}
                onChange={setCapacity}
                error={error(!capacityValid, texts.positiveNumber)}
              />
              <Select
                label={texts.visibility}
                allowDeselect={false}
                data={eventVisibilities.map((value) => ({
                  value,
                  label: pl.schedule.visibilities[value],
                }))}
                value={visibility}
                onChange={(value) => value && setVisibility(value as EventVisibility)}
              />
            </SimpleGrid>
            <AssignmentFields
              serviceTypeId={serviceTypeId}
              staff={staff}
              onStaffChange={setStaff}
              resources={resources}
              onResourcesChange={setResources}
              lookups={lookups}
              roomRules={roomRules}
            />
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>
                {texts.cancel}
              </Button>
              <Button type="submit">{texts.next}</Button>
            </Group>
          </Stack>
        </form>
      ) : (
        <Stack>
          {preview.isPending ? (
            <Group>
              <Loader size="sm" />
              <Text>{texts.checking}</Text>
            </Group>
          ) : preview.isError ? (
            <Alert color="red">{scheduleErrorMessage(preview.error)}</Alert>
          ) : (
            <>
              <Text size="sm">{texts.summary(occurrences.length, included.length)}</Text>
              {(preview.data?.warnings.length ?? 0) > 0 && (
                <Alert color="yellow" title={texts.planWarnings}>
                  <IssueList issues={preview.data!.warnings} lookups={lookups} />
                </Alert>
              )}
              {conflicting.length > 0 ? (
                <Alert color="red">
                  <Stack gap="xs" align="flex-start">
                    <Text size="sm">{texts.conflictsLeft(conflicting.length)}</Text>
                    {recurring && (
                      <Button
                        size="xs"
                        variant="white"
                        color="red"
                        onClick={() =>
                          setExcluded([
                            ...excluded,
                            ...conflicting.map((occurrence) => occurrence.date),
                          ])
                        }
                      >
                        {texts.skipConflicting}
                      </Button>
                    )}
                  </Stack>
                </Alert>
              ) : included.length > 0 ? (
                <Alert color="green">{texts.noConflicts}</Alert>
              ) : (
                <Alert color="yellow">{texts.nothingToSave}</Alert>
              )}
              <ScrollArea.Autosize mah={380}>
                <Table striped>
                  <Table.Thead>
                    <Table.Tr>
                      {recurring && <Table.Th w={40} />}
                      <Table.Th>{texts.dateColumn}</Table.Th>
                      <Table.Th>{texts.timeColumn}</Table.Th>
                      <Table.Th>{texts.issuesColumn}</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {occurrences.map((occurrence) => {
                      const isIncluded = !excluded.includes(occurrence.date);
                      return (
                        <Table.Tr
                          key={occurrence.startsAt}
                          style={{ opacity: isIncluded ? 1 : 0.5 }}
                        >
                          {recurring && (
                            <Table.Td>
                              <Checkbox
                                aria-label={`${texts.include}: ${formatDate(occurrence.date)}`}
                                checked={isIncluded}
                                onChange={(event) =>
                                  toggleDate(occurrence.date, event.currentTarget.checked)
                                }
                              />
                            </Table.Td>
                          )}
                          <Table.Td>
                            {pl.schedule.weekdaysShort[isoWeekday(occurrence.date) - 1]}{' '}
                            {formatDate(occurrence.date)}
                          </Table.Td>
                          <Table.Td>
                            {formatTimeRange(occurrence.startsAt, occurrence.endsAt, timeZone)}
                          </Table.Td>
                          <Table.Td>
                            {occurrence.issues.length > 0 ? (
                              <IssueList issues={occurrence.issues} lookups={lookups} />
                            ) : (
                              <Text size="xs" c="green.7">
                                {texts.ok}
                              </Text>
                            )}
                          </Table.Td>
                        </Table.Tr>
                      );
                    })}
                  </Table.Tbody>
                </Table>
              </ScrollArea.Autosize>
            </>
          )}
          {save.isError && (
            <Alert color="red">
              <Stack gap="xs" align="flex-start">
                <Text size="sm">{scheduleErrorMessage(save.error)}</Text>
                {saveConflicts.length > 0 && (
                  <ConflictList conflicts={saveConflicts} lookups={lookups} />
                )}
                <Button size="xs" variant="white" color="red" onClick={() => preview.mutate()}>
                  {texts.recheck}
                </Button>
              </Stack>
            </Alert>
          )}
          <Group justify="space-between">
            <Button variant="default" onClick={() => setStep('details')}>
              {texts.back}
            </Button>
            <Group>
              <Button variant="default" onClick={onClose}>
                {texts.cancel}
              </Button>
              <Button
                onClick={() => save.mutate()}
                loading={save.isPending}
                disabled={!preview.isSuccess || included.length === 0 || conflicting.length > 0}
              >
                {recurring ? texts.saveCount(included.length) : texts.save}
              </Button>
            </Group>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
