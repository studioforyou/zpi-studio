import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  ColorInput,
  Divider,
  Group,
  Modal,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Tabs,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { scheduleKeys } from '@/features/schedule/scheduleApi';
import {
  createServiceType,
  getBookingDefaults,
  serviceCategories,
  serviceTypeKeys,
  updateServiceType,
  type ServiceCategory,
  type ServiceType,
  type ServiceTypeInput,
  type BookingPolicyValues,
} from './serviceTypesApi';
import { BookingPolicyFields } from './BookingPolicyFields';
import {
  bookingPolicyInput,
  validateBookingPolicy,
  type BookingPolicyFormValues,
  type BookingPolicyErrors,
} from './bookingPolicyFormValues';

function policyForm(values: BookingPolicyValues): BookingPolicyFormValues {
  return { ...values, autoCancelHoursBefore: values.autoCancelHoursBefore ?? 0 };
}

type Props = { opened: boolean; onClose: () => void; service?: ServiceType };
type NumberField =
  'defaultDurationMinutes' | 'minAge' | 'maxAge' | 'minParticipants' | 'maxParticipants';
type Values = {
  name: string;
  description: string;
  category: ServiceCategory;
  ageRestricted: boolean;
  levelSpecified: boolean;
  requireMinParticipants: boolean;
  limitParticipants: boolean;
  level: string;
  calendarColor: string;
  isActive: boolean;
} & Record<NumberField, number | string>;
type Errors = Partial<Record<keyof Values, string>>;

export function ServiceTypeFormModal({ opened, onClose, service }: Props) {
  const texts = pl.trainings.form;
  const queryClient = useQueryClient();
  const [values, setValues] = useState<Values>({
    name: service?.name ?? '',
    description: service?.description ?? '',
    category: service?.category ?? 'GROUP_CLASS',
    ageRestricted: service?.minAge != null || service?.maxAge != null,
    levelSpecified: !!service?.level,
    requireMinParticipants:
      service?.bookingPolicy?.overrides?.autoCancelMinParticipants != null ||
      service?.minParticipants != null,
    limitParticipants: service?.maxParticipants != null,
    level: service?.level ?? '',
    calendarColor: service?.calendarColor ?? '',
    isActive: service?.isActive ?? true,
    defaultDurationMinutes: service?.defaultDurationMinutes ?? 60,
    minAge: service?.minAge ?? '',
    maxAge: service?.maxAge ?? '',
    minParticipants:
      service?.bookingPolicy?.overrides?.autoCancelMinParticipants ??
      service?.minParticipants ??
      '',
    maxParticipants: service?.maxParticipants ?? '',
  });
  const [errors, setErrors] = useState<Errors>({});
  const [activeTab, setActiveTab] = useState<string | null>('details');
  const [policy, setPolicy] = useState<BookingPolicyFormValues | null>(
    service?.bookingPolicy?.overrides ? policyForm(service.bookingPolicy.effective) : null,
  );
  const [policyErrors, setPolicyErrors] = useState<BookingPolicyErrors>({});
  const defaults = useQuery({
    queryKey: ['service-booking-defaults'],
    queryFn: getBookingDefaults,
  });
  const groupRulesAllowed = values.category !== 'PERSONAL_TRAINING';
  const policyBase = policy ?? (defaults.data ? policyForm(defaults.data) : undefined);
  const displayedPolicy = policyBase && {
    ...policyBase,
    autoCancelEnabled: groupRulesAllowed && policyBase.autoCancelEnabled,
    waitlistEnabled: groupRulesAllowed && policyBase.waitlistEnabled,
  };
  const minimumParticipants =
    values.requireMinParticipants && values.minParticipants !== ''
      ? Number(values.minParticipants)
      : (defaults.data?.autoCancelMinParticipants ?? 1);

  function set<K extends keyof Values>(field: K, value: Values[K]) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  const save = useMutation({
    mutationFn: () => {
      const optionalNumber = (field: NumberField) =>
        values[field] === '' ? null : Number(values[field]);
      const input: ServiceTypeInput = {
        name: values.name.trim(),
        description: values.description.trim() || null,
        category: values.category,
        level: values.levelSpecified ? values.level.trim() : null,
        defaultDurationMinutes: Number(values.defaultDurationMinutes),
        minAge: values.ageRestricted ? optionalNumber('minAge') : null,
        maxAge: values.ageRestricted ? optionalNumber('maxAge') : null,
        minParticipants: values.requireMinParticipants ? optionalNumber('minParticipants') : null,
        maxParticipants: values.limitParticipants ? optionalNumber('maxParticipants') : null,
        calendarColor: values.calendarColor || null,
        bookingPolicy: policy
          ? bookingPolicyInput(
              {
                ...policy,
                autoCancelMinParticipants: values.requireMinParticipants
                  ? minimumParticipants
                  : null,
              },
              groupRulesAllowed,
            )
          : null,
      };
      return service
        ? updateServiceType(service.id, { ...input, isActive: values.isActive })
        : createServiceType(input);
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: serviceTypeKeys.all }),
        queryClient.invalidateQueries({ queryKey: ['resources'] }),
        queryClient.invalidateQueries({ queryKey: scheduleKeys.serviceTypes }),
      ]);
      onClose();
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'VALIDATION_FAILED') {
        const next: Errors = {};
        const rules: BookingPolicyErrors = {};
        for (const issue of error.problem?.errors ?? []) {
          if (issue.field.startsWith('bookingPolicy.')) {
            rules[issue.field.slice('bookingPolicy.'.length) as keyof BookingPolicyValues] =
              issue.field.endsWith('autoCancelMinParticipants')
                ? pl.trainings.policy.capacityError
                : texts.invalidValue;
          } else if (issue.field in values) {
            next[issue.field as keyof Values] =
              issue.field === 'maxAge'
                ? texts.ageRange
                : issue.field === 'maxParticipants'
                  ? texts.participantRange
                  : texts.invalidValue;
          }
        }
        setErrors(next);
        setPolicyErrors(rules);
        if (Object.keys(rules).length > 0) setActiveTab('rules');
      }
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (save.isPending || !displayedPolicy) return;
    const next: Errors = {};
    if (!values.name.trim()) next.name = texts.nameRequired;
    else if (values.name.trim().length > 120) next.name = texts.nameTooLong;
    if (values.levelSpecified) {
      if (!values.level.trim()) next.level = texts.levelRequired;
      else if (values.level.trim().length > 20) next.level = texts.levelTooLong;
    }
    if (values.ageRestricted && values.minAge === '' && values.maxAge === '') {
      next.minAge = texts.ageRequired;
    }
    const numberFields: NumberField[] = [
      'defaultDurationMinutes',
      'minAge',
      'maxAge',
      'minParticipants',
      'maxParticipants',
    ];
    for (const field of numberFields) {
      const value = values[field];
      const min = field === 'minAge' || field === 'maxAge' ? 0 : 1;
      if ((field === 'minAge' || field === 'maxAge') && !values.ageRestricted) continue;
      if (field === 'minParticipants' && !values.requireMinParticipants) continue;
      if (field === 'maxParticipants' && !values.limitParticipants) continue;
      if (value === '' && field !== 'defaultDurationMinutes') continue;
      if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > 32767) {
        next[field] = min === 0 ? texts.nonnegativeNumber : texts.positiveNumber;
      }
    }
    if (values.requireMinParticipants && values.minParticipants === '') {
      next.minParticipants = texts.positiveNumber;
    }
    if (values.limitParticipants && values.maxParticipants === '') {
      next.maxParticipants = texts.positiveNumber;
    }
    if (
      values.ageRestricted &&
      values.minAge !== '' &&
      values.maxAge !== '' &&
      Number(values.minAge) > Number(values.maxAge)
    ) {
      next.maxAge = texts.ageRange;
    }
    if (
      values.requireMinParticipants &&
      values.limitParticipants &&
      values.minParticipants !== '' &&
      values.maxParticipants !== '' &&
      Number(values.minParticipants) > Number(values.maxParticipants)
    ) {
      next.maxParticipants = texts.participantRange;
    }
    if (values.calendarColor && !/^#[0-9a-fA-F]{6}$/.test(values.calendarColor)) {
      next.calendarColor = texts.colorInvalid;
    }
    const rules = validateBookingPolicy(displayedPolicy, groupRulesAllowed);
    if (
      groupRulesAllowed &&
      displayedPolicy.autoCancelEnabled &&
      values.limitParticipants &&
      values.maxParticipants !== '' &&
      minimumParticipants > Number(values.maxParticipants)
    ) {
      rules.autoCancelMinParticipants = pl.trainings.policy.capacityError;
    }
    setErrors(next);
    setPolicyErrors(rules);
    if (Object.keys(next).length > 0) {
      setActiveTab(next.minParticipants ? 'rules' : 'details');
    } else if (Object.keys(rules).length > 0) {
      setActiveTab('rules');
    } else save.mutate();
  }

  function numberInput(field: NumberField, min: number, required = false, description?: string) {
    return (
      <NumberInput
        label={texts[field]}
        description={description}
        min={min}
        max={32767}
        allowDecimal={false}
        required={required}
        value={values[field]}
        onChange={(value) => set(field, value)}
        error={errors[field]}
        disabled={save.isPending}
      />
    );
  }

  const errorMessage =
    save.error instanceof ApiError && save.error.code === NETWORK_ERROR
      ? texts.networkError
      : texts.saveError;

  return (
    <Modal
      opened={opened}
      onClose={save.isPending ? () => {} : onClose}
      closeOnClickOutside={!save.isPending}
      closeOnEscape={!save.isPending}
      title={service ? texts.editTitle : texts.createTitle}
      size="lg"
    >
      <form onSubmit={submit} noValidate>
        <Stack>
          {save.isError && <Alert color="red">{errorMessage}</Alert>}
          {defaults.isError && (
            <Alert color="red" title={pl.trainings.policy.loadError}>
              <Button variant="subtle" onClick={() => void defaults.refetch()}>
                {pl.trainings.retry}
              </Button>
            </Alert>
          )}
          <Tabs value={activeTab} onChange={setActiveTab} keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="details">{texts.detailsTab}</Tabs.Tab>
              <Tabs.Tab value="rules">{texts.rulesTab}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="details" pt="md">
              <Stack>
                <TextInput
                  label={texts.name}
                  description={texts.nameHint}
                  required
                  maxLength={120}
                  value={values.name}
                  onChange={(event) => set('name', event.currentTarget.value)}
                  error={errors.name}
                  disabled={save.isPending}
                />
                <Textarea
                  label={texts.description}
                  rows={3}
                  value={values.description}
                  onChange={(event) => set('description', event.currentTarget.value)}
                  disabled={save.isPending}
                />
                <Select
                  label={texts.category}
                  description={texts.categoryHint}
                  required
                  allowDeselect={false}
                  data={serviceCategories.map((value) => ({
                    value,
                    label: pl.trainings.categories[value],
                  }))}
                  value={values.category}
                  onChange={(value) => {
                    if (!value) return;
                    set('category', value as ServiceCategory);
                    if (value === 'PERSONAL_TRAINING') {
                      if (values.minParticipants === '') {
                        set('requireMinParticipants', true);
                        set('minParticipants', 1);
                      }
                      if (values.maxParticipants === '') {
                        set('limitParticipants', true);
                        set('maxParticipants', 1);
                      }
                      setPolicy(
                        (current) =>
                          current && {
                            ...current,
                            autoCancelEnabled: false,
                            waitlistEnabled: false,
                          },
                      );
                    }
                  }}
                  disabled={save.isPending}
                />
                {numberInput('defaultDurationMinutes', 1, true, texts.durationHint)}
                <Divider label={texts.participationRules} labelPosition="left" />
                <Switch
                  label={texts.ageRestricted}
                  description={texts.ageHint}
                  checked={values.ageRestricted}
                  onChange={(event) => {
                    const checked = event.currentTarget.checked;
                    set('ageRestricted', checked);
                    if (!checked) {
                      set('minAge', '');
                      set('maxAge', '');
                    }
                  }}
                  disabled={save.isPending}
                />
                {values.ageRestricted && (
                  <SimpleGrid cols={{ base: 1, sm: 2 }}>
                    {numberInput('minAge', 0)}
                    {numberInput('maxAge', 0)}
                  </SimpleGrid>
                )}
                <Switch
                  label={texts.levelSpecified}
                  description={texts.levelHint}
                  checked={values.levelSpecified}
                  onChange={(event) => {
                    const checked = event.currentTarget.checked;
                    set('levelSpecified', checked);
                    if (!checked) set('level', '');
                  }}
                  disabled={save.isPending}
                />
                {values.levelSpecified && (
                  <Select
                    label={texts.level}
                    required
                    allowDeselect={false}
                    data={[
                      ...Object.entries(pl.trainings.levels).map(([value, label]) => ({
                        value,
                        label,
                      })),
                      ...(values.level && !(values.level in pl.trainings.levels)
                        ? [{ value: values.level, label: values.level }]
                        : []),
                    ]}
                    value={values.level || null}
                    onChange={(value) => set('level', value ?? '')}
                    error={errors.level}
                    disabled={save.isPending}
                  />
                )}
                <Divider label={texts.participantDefaults} labelPosition="left" />
                {!groupRulesAllowed && (
                  <Text size="sm">{pl.trainings.policy.personalCapacity}</Text>
                )}
                <Switch
                  label={texts.limitParticipants}
                  description={texts.participantLimitHint}
                  checked={values.limitParticipants}
                  onChange={(event) => {
                    const checked = event.currentTarget.checked;
                    set('limitParticipants', checked);
                    if (!checked) set('maxParticipants', '');
                  }}
                  disabled={save.isPending}
                />
                {values.limitParticipants && numberInput('maxParticipants', 1, true)}
                <ColorInput
                  label={texts.calendarColor}
                  format="hex"
                  value={values.calendarColor}
                  onChange={(value) => set('calendarColor', value)}
                  error={errors.calendarColor}
                  disabled={save.isPending}
                />
                {service && (
                  <Switch
                    label={texts.isActive}
                    description={texts.activeHint}
                    checked={values.isActive}
                    onChange={(event) => set('isActive', event.currentTarget.checked)}
                    disabled={save.isPending}
                  />
                )}
              </Stack>
            </Tabs.Panel>
            <Tabs.Panel value="rules" pt="md">
              <Stack>
                {defaults.isPending ? (
                  <Text>{pl.trainings.policy.loading}</Text>
                ) : defaults.isError ? null : (
                  displayedPolicy && (
                    <>
                      <Switch
                        label={pl.trainings.policy.useStudioDefaults}
                        description={pl.trainings.policy.useStudioDefaultsHint}
                        checked={policy === null}
                        onChange={(event) => {
                          setPolicy(event.currentTarget.checked ? null : { ...displayedPolicy });
                          setPolicyErrors({});
                        }}
                        disabled={save.isPending}
                      />
                      <Divider label={texts.participantDefaults} labelPosition="left" />
                      <Switch
                        label={texts.requireMinParticipants}
                        description={
                          groupRulesAllowed
                            ? texts.minParticipantsHint
                            : texts.personalMinParticipantsHint
                        }
                        checked={values.requireMinParticipants}
                        onChange={(event) => {
                          const checked = event.currentTarget.checked;
                          set('requireMinParticipants', checked);
                          if (!checked) set('minParticipants', '');
                        }}
                        disabled={save.isPending}
                      />
                      {values.requireMinParticipants && numberInput('minParticipants', 1, true)}
                      <BookingPolicyFields
                        values={displayedPolicy}
                        onChange={(next) => {
                          setPolicy(next);
                          setPolicyErrors({});
                        }}
                        groupRulesAllowed={groupRulesAllowed}
                        minimumParticipants={minimumParticipants}
                        disabled={save.isPending || policy === null}
                        errors={policyErrors}
                      />
                    </>
                  )
                )}
              </Stack>
            </Tabs.Panel>
          </Tabs>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose} disabled={save.isPending}>
              {texts.cancel}
            </Button>
            <Button
              type="submit"
              loading={save.isPending}
              disabled={defaults.isPending || defaults.isError}
            >
              {texts.save}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
