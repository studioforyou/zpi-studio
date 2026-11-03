import { useState, type FormEvent, type ReactNode } from 'react';
import { Alert, Button, Group, Modal, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import {
  accountKeys,
  updateChild,
  updateStaffProfile,
  type AccountDetails,
  type AccountProfile,
} from './accountsApi';
import {
  changedNames,
  FIELDS_MIN_HEIGHT,
  nameError,
  phoneError,
  todayIso,
  type NameFields,
} from './profileValidation';

const BIO_MAX_LENGTH = 2000;

const EMERGENCY_NAME_MAX_LENGTH = 120;

const texts = pl.accounts.details.form;

/**
 * Tells that a changed name is also saved in the other profile of the same person; OK saves,
 * closing the dialog cancels the save.
 */
export function NameSyncModal({
  fields,
  other,
  onConfirm,
  onCancel,
}: {
  fields: NameFields | null;
  other: 'CLIENT' | 'STAFF';
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Modal opened={fields !== null} onClose={onCancel} title={texts.nameSync.title} centered>
      <Stack>
        <Text size="sm">{fields && texts.nameSync.message(fields, other)}</Text>
        <Group justify="flex-end">
          <Button onClick={onConfirm}>{texts.nameSync.ok}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function blankToNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim();
}

type Values = Record<string, string>;

/**
 * State and saving shared by the profile forms: the form saves when valid and, for a changed name
 * shared with [sharedWith], only after the name dialog is confirmed.
 */
function useProfileForm<T extends Values>(
  account: AccountDetails,
  initial: T,
  validate: (values: T) => Partial<Record<keyof T, string>>,
  save: (values: T) => Promise<AccountDetails>,
  sharedWith: 'CLIENT' | 'STAFF' | null,
) {
  const queryClient = useQueryClient();
  const [values, setValues] = useState(initial);
  const [submitted, setSubmitted] = useState(false);
  const [saved, setSaved] = useState(false);
  const [confirming, setConfirming] = useState<NameFields | null>(null);
  const mutation = useMutation({
    mutationFn: () => save(values),
    onSuccess: async (updated) => {
      setSaved(true);
      queryClient.setQueryData(accountKeys.details(account.id), updated);
      await queryClient.invalidateQueries({ queryKey: accountKeys.all });
    },
  });
  const errors = validate(values);
  const changed = (Object.keys(initial) as (keyof T)[]).some(
    (field) => values[field].trim() !== initial[field],
  );

  function change(field: keyof T, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setSaved(false);
    if (mutation.isError) {
      mutation.reset();
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (Object.values(errors).some((error) => error !== undefined)) {
      return;
    }
    const names =
      sharedWith && 'firstName' in initial && 'lastName' in initial
        ? changedNames(
            { firstName: initial.firstName, lastName: initial.lastName },
            { firstName: values.firstName, lastName: values.lastName },
          )
        : null;
    if (names) {
      setConfirming(names);
      return;
    }
    mutation.mutate();
  }

  function reset() {
    setValues(initial);
    setSubmitted(false);
    mutation.reset();
  }

  const nameSync = sharedWith && (
    <NameSyncModal
      fields={confirming}
      other={sharedWith}
      onCancel={() => setConfirming(null)}
      onConfirm={() => {
        setConfirming(null);
        mutation.mutate();
      }}
    />
  );

  return {
    values,
    errors: (submitted ? errors : {}) as Partial<Record<keyof T, string>>,
    change,
    submit,
    reset,
    changed,
    saved,
    mutation,
    nameSync,
  };
}

function FormLayout({
  form,
  closed,
  children,
}: {
  form: ReturnType<typeof useProfileForm>;
  closed: boolean;
  children: ReactNode;
}) {
  return (
    <form onSubmit={form.submit} noValidate>
      <Stack maw={560}>
        {closed && <Alert color="gray">{texts.closedHint}</Alert>}
        <Stack mih={FIELDS_MIN_HEIGHT}>{children}</Stack>
        {!closed && (
          <Group>
            <Button type="submit" loading={form.mutation.isPending} disabled={!form.changed}>
              {texts.save}
            </Button>
            <Button
              variant="default"
              disabled={!form.changed || form.mutation.isPending}
              onClick={form.reset}
            >
              {texts.reset}
            </Button>
          </Group>
        )}
        {form.saved && !form.changed && (
          <Alert color="green" role="status">
            {texts.saved}
          </Alert>
        )}
        {form.mutation.isError && (
          <Alert color="red" role="alert">
            {texts.errors.unknown}
          </Alert>
        )}
      </Stack>
      {form.nameSync}
    </form>
  );
}

/** The staff profile: name (shared with the own client profile), phone and description. */
export function StaffProfileForm({
  account,
  profile,
}: {
  account: AccountDetails;
  profile: AccountProfile;
}) {
  const hasClientProfile = account.profiles.some((other) => other.kind === 'CLIENT');
  const form = useProfileForm(
    account,
    {
      firstName: profile.firstName,
      lastName: profile.lastName,
      phone: profile.phone ?? '',
      bio: profile.bio ?? '',
    },
    (values) => ({
      firstName: nameError(values.firstName),
      lastName: nameError(values.lastName),
      phone: phoneError(values.phone),
      bio:
        values.bio.trim().length > BIO_MAX_LENGTH
          ? pl.accounts.invite.tooLong(BIO_MAX_LENGTH)
          : undefined,
    }),
    (values) =>
      updateStaffProfile(account.id, {
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        phone: blankToNull(values.phone),
        bio: blankToNull(values.bio),
      }),
    hasClientProfile ? 'CLIENT' : null,
  );
  const closed = account.status === 'CLOSED';

  return (
    <FormLayout form={form as ReturnType<typeof useProfileForm>} closed={closed}>
      <Group grow align="flex-start">
        <TextInput
          label={texts.firstName}
          withAsterisk
          disabled={closed}
          value={form.values.firstName}
          onChange={(event) => form.change('firstName', event.currentTarget.value)}
          error={form.errors.firstName}
        />
        <TextInput
          label={texts.lastName}
          withAsterisk
          disabled={closed}
          value={form.values.lastName}
          onChange={(event) => form.change('lastName', event.currentTarget.value)}
          error={form.errors.lastName}
        />
      </Group>
      <TextInput
        label={texts.phone}
        type="tel"
        disabled={closed}
        value={form.values.phone}
        onChange={(event) => form.change('phone', event.currentTarget.value)}
        error={form.errors.phone}
        w={{ base: '100%', sm: 260 }}
      />
      <Textarea
        label={texts.bio}
        rows={3}
        disabled={closed}
        value={form.values.bio}
        onChange={(event) => form.change('bio', event.currentTarget.value)}
        error={form.errors.bio}
      />
    </FormLayout>
  );
}

/** A child of the account: name, birth date and emergency contact. */
export function ChildProfileForm({
  account,
  profile,
}: {
  account: AccountDetails;
  profile: AccountProfile;
}) {
  const [today] = useState(todayIso);
  const form = useProfileForm(
    account,
    {
      firstName: profile.firstName,
      lastName: profile.lastName,
      birthDate: profile.birthDate ?? '',
      emergencyContactName: profile.emergencyContactName ?? '',
      emergencyPhone: profile.emergencyPhone ?? '',
    },
    (values) => {
      const contactName = values.emergencyContactName.trim();
      const contactPhone = values.emergencyPhone.trim();
      const incomplete = (contactName === '') !== (contactPhone === '');
      return {
        firstName: nameError(values.firstName),
        lastName: nameError(values.lastName),
        birthDate:
          values.birthDate && values.birthDate >= today ? texts.futureBirthDate : undefined,
        emergencyContactName: contactName
          ? nameError(contactName, EMERGENCY_NAME_MAX_LENGTH)
          : incomplete
            ? texts.emergencyIncomplete
            : undefined,
        emergencyPhone:
          phoneError(contactPhone) ??
          (incomplete && contactPhone === '' ? texts.emergencyIncomplete : undefined),
      };
    },
    (values) =>
      updateChild(account.id, profile.id, {
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        birthDate: blankToNull(values.birthDate),
        emergencyContactName: blankToNull(values.emergencyContactName),
        emergencyPhone: blankToNull(values.emergencyPhone),
      }),
    null,
  );
  const closed = account.status === 'CLOSED';

  return (
    <FormLayout form={form as ReturnType<typeof useProfileForm>} closed={closed}>
      <Group grow align="flex-start">
        <TextInput
          label={texts.firstName}
          withAsterisk
          disabled={closed}
          value={form.values.firstName}
          onChange={(event) => form.change('firstName', event.currentTarget.value)}
          error={form.errors.firstName}
        />
        <TextInput
          label={texts.lastName}
          withAsterisk
          disabled={closed}
          value={form.values.lastName}
          onChange={(event) => form.change('lastName', event.currentTarget.value)}
          error={form.errors.lastName}
        />
      </Group>
      <TextInput
        label={texts.birthDate}
        type="date"
        max={today}
        disabled={closed}
        value={form.values.birthDate}
        onChange={(event) => form.change('birthDate', event.currentTarget.value)}
        error={form.errors.birthDate}
        w={{ base: '100%', sm: 260 }}
      />
      <Group grow align="flex-start">
        <TextInput
          label={texts.emergencyContactName}
          disabled={closed}
          value={form.values.emergencyContactName}
          onChange={(event) => form.change('emergencyContactName', event.currentTarget.value)}
          error={form.errors.emergencyContactName}
        />
        <TextInput
          label={texts.emergencyPhone}
          type="tel"
          disabled={closed}
          value={form.values.emergencyPhone}
          onChange={(event) => form.change('emergencyPhone', event.currentTarget.value)}
          error={form.errors.emergencyPhone}
        />
      </Group>
    </FormLayout>
  );
}
