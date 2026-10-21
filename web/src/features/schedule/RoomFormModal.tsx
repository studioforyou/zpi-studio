import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Group,
  Modal,
  MultiSelect,
  NumberInput,
  Select,
  Stack,
  Switch,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import {
  createResource,
  listServiceTypeRules,
  listServiceTypes,
  resourceKeys,
  resourceKinds,
  updateResource,
  type Resource,
  type ResourceKind,
  type ServiceTypeAssignment,
} from './resourcesApi';
import { roomErrorMessage } from './roomErrors';

type RoomFormModalProps = {
  opened: boolean;
  onClose: () => void;
  /** The room to edit; a new room is created when missing. */
  room?: Resource;
  /** All rooms, used to choose the parent room. */
  rooms: Resource[];
};

/**
 * Two-step form: the room's details, then the service types allowed in it. Both are saved
 * together in one request. Mounted fresh for every room (keyed by its id), so the form starts
 * from that room's values.
 */
export function RoomFormModal({ opened, onClose, room, rooms }: RoomFormModalProps) {
  const texts = pl.rooms.form;
  const queryClient = useQueryClient();
  const [step, setStep] = useState<'details' | 'serviceTypes'>('details');
  const [name, setName] = useState(room?.name ?? '');
  const [kind, setKind] = useState<ResourceKind>(room?.kind ?? 'ROOM');
  const [parentResourceId, setParentResourceId] = useState<string | null>(
    room?.parentResourceId ?? null,
  );
  const [capacityUnits, setCapacityUnits] = useState<number | string>(room?.capacityUnits ?? 1);
  const [maxPeople, setMaxPeople] = useState<number | string>(room?.maxPeople ?? '');
  const [description, setDescription] = useState(room?.description ?? '');
  const [isActive, setIsActive] = useState(room?.isActive ?? true);
  const [submitted, setSubmitted] = useState(false);
  // null until the user changes the selection; until then an edited room keeps its saved one.
  const [selectedServiceTypes, setSelectedServiceTypes] = useState<ServiceTypeAssignment[] | null>(
    room ? null : [],
  );

  const currentRules = useQuery({
    queryKey: resourceKeys.rules(room?.id ?? ''),
    queryFn: () => listServiceTypeRules(room!.id),
    enabled: room !== undefined,
  });
  const serviceTypes = useQuery({
    queryKey: resourceKeys.serviceTypes,
    queryFn: listServiceTypes,
  });

  const assignments: ServiceTypeAssignment[] | undefined =
    selectedServiceTypes ??
    currentRules.data?.map(({ serviceTypeId, isDefault }) => ({ serviceTypeId, isDefault }));

  const save = useMutation({
    mutationFn: () => {
      const input = {
        name: name.trim(),
        kind,
        parentResourceId,
        capacityUnits: Number(capacityUnits),
        maxPeople: maxPeople === '' ? null : Number(maxPeople),
        description: description.trim() === '' ? null : description.trim(),
        serviceTypes: assignments ?? [],
      };
      return room ? updateResource(room.id, { ...input, isActive }) : createResource(input);
    },
    onSuccess: async () => {
      // Making the room a service's default room changes other rooms' service types too.
      await queryClient.invalidateQueries({ queryKey: resourceKeys.all });
      onClose();
    },
  });

  const nameError = submitted && name.trim() === '' ? texts.nameRequired : undefined;
  const capacityError =
    submitted && !isPositiveInteger(capacityUnits) ? texts.capacityRequired : undefined;
  const maxPeopleError =
    submitted && maxPeople !== '' && !isPositiveInteger(maxPeople)
      ? texts.positiveNumber
      : undefined;

  function handleNext(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (
      name.trim() === '' ||
      !isPositiveInteger(capacityUnits) ||
      (maxPeople !== '' && !isPositiveInteger(maxPeople))
    ) {
      return;
    }
    setStep('serviceTypes');
  }

  function handleSave(event: FormEvent) {
    event.preventDefault();
    save.mutate();
  }

  // A room cannot be its own parent; the backend also rejects longer cycles.
  const parentOptions = rooms
    .filter((candidate) => candidate.id !== room?.id)
    .map((candidate) => ({ value: candidate.id, label: candidate.name }));

  // Active service types, plus those already allowed in the room even if deactivated since.
  const serviceTypeNames = new Map<string, string>();
  for (const rule of currentRules.data ?? []) {
    serviceTypeNames.set(rule.serviceTypeId, rule.serviceTypeName ?? texts.unknownServiceType);
  }
  for (const serviceType of serviceTypes.data ?? []) {
    serviceTypeNames.set(serviceType.id, serviceType.name);
  }
  const serviceTypeOptions = [...serviceTypeNames]
    .map(([value, label]) => ({ value, label }))
    .sort((a, b) => a.label.localeCompare(b.label, 'pl'));

  function selectServiceTypes(ids: string[]) {
    const previous = new Map(
      (assignments ?? []).map((assignment) => [assignment.serviceTypeId, assignment.isDefault]),
    );
    setSelectedServiceTypes(
      ids.map((serviceTypeId) => ({
        serviceTypeId,
        isDefault: previous.get(serviceTypeId) ?? false,
      })),
    );
  }

  function setDefault(serviceTypeId: string, isDefault: boolean) {
    setSelectedServiceTypes(
      (assignments ?? []).map((assignment) =>
        assignment.serviceTypeId === serviceTypeId ? { ...assignment, isDefault } : assignment,
      ),
    );
  }

  const title = room ? texts.editTitle : texts.createTitle;
  const stepLabel = step === 'details' ? texts.stepDetails : texts.stepServiceTypes;

  return (
    <Modal opened={opened} onClose={onClose} title={`${title} – ${stepLabel}`} size="lg">
      {step === 'details' ? (
        <form onSubmit={handleNext} noValidate>
          <Stack>
            <TextInput
              label={texts.name}
              required
              maxLength={80}
              value={name}
              onChange={(event) => setName(event.currentTarget.value)}
              error={nameError}
            />
            <Select
              label={texts.kind}
              required
              allowDeselect={false}
              data={resourceKinds.map((value) => ({ value, label: pl.rooms.kinds[value] }))}
              value={kind}
              onChange={(value) => value && setKind(value as ResourceKind)}
            />
            <Select
              label={texts.parent}
              placeholder={texts.parentPlaceholder}
              clearable
              data={parentOptions}
              value={parentResourceId}
              onChange={setParentResourceId}
            />
            <NumberInput
              label={texts.capacityUnits}
              description={texts.capacityUnitsDescription}
              required
              min={1}
              max={32767}
              allowDecimal={false}
              value={capacityUnits}
              onChange={setCapacityUnits}
              error={capacityError}
            />
            <NumberInput
              label={texts.maxPeople}
              description={texts.maxPeopleDescription}
              min={1}
              max={32767}
              allowDecimal={false}
              value={maxPeople}
              onChange={setMaxPeople}
              error={maxPeopleError}
            />
            <Textarea
              label={texts.description}
              rows={3}
              value={description}
              onChange={(event) => setDescription(event.currentTarget.value)}
            />
            {room && (
              <Switch
                label={texts.isActive}
                description={texts.isActiveDescription}
                checked={isActive}
                onChange={(event) => setIsActive(event.currentTarget.checked)}
              />
            )}
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>
                {texts.cancel}
              </Button>
              <Button type="submit">{texts.next}</Button>
            </Group>
          </Stack>
        </form>
      ) : (
        <form onSubmit={handleSave} noValidate>
          <Stack>
            {save.isError && <Alert color="red">{roomErrorMessage(save.error)}</Alert>}
            {serviceTypes.isError && <Alert color="red">{texts.serviceTypesLoadError}</Alert>}
            {currentRules.isError && (
              <Alert color="red">{texts.currentServiceTypesLoadError}</Alert>
            )}
            <MultiSelect
              label={texts.serviceTypes}
              description={texts.serviceTypesDescription}
              placeholder={texts.serviceTypesPlaceholder}
              nothingFoundMessage={texts.noServiceTypes}
              searchable
              data={serviceTypeOptions}
              value={(assignments ?? []).map((assignment) => assignment.serviceTypeId)}
              onChange={selectServiceTypes}
              disabled={assignments === undefined}
            />
            {assignments !== undefined && assignments.length > 0 && (
              <Stack gap="xs">
                <Text size="sm" fw={500}>
                  {texts.defaultRoom}
                </Text>
                <Text size="xs" c="dimmed">
                  {texts.defaultRoomDescription}
                </Text>
                {assignments.map((assignment) => (
                  <Checkbox
                    key={assignment.serviceTypeId}
                    label={
                      serviceTypeNames.get(assignment.serviceTypeId) ?? texts.unknownServiceType
                    }
                    checked={assignment.isDefault}
                    onChange={(event) =>
                      setDefault(assignment.serviceTypeId, event.currentTarget.checked)
                    }
                  />
                ))}
              </Stack>
            )}
            <Group justify="space-between">
              <Button variant="default" onClick={() => setStep('details')}>
                {texts.back}
              </Button>
              <Group>
                <Button variant="default" onClick={onClose}>
                  {texts.cancel}
                </Button>
                <Button type="submit" loading={save.isPending} disabled={assignments === undefined}>
                  {texts.save}
                </Button>
              </Group>
            </Group>
          </Stack>
        </form>
      )}
    </Modal>
  );
}

function isPositiveInteger(value: number | string): boolean {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1;
}
