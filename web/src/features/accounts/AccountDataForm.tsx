import { useState, type FormEvent } from 'react';
import { Alert, Button, Group, Stack, TextInput } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { accountKeys, updateAccount, type AccountDetails } from './accountsApi';
import { NameSyncModal } from './ProfileForms';
import { changedNames, FIELDS_MIN_HEIGHT, type NameFields } from './profileValidation';

// Same limits as the backend's UpdateAccountRequest.
const NAME_MAX_LENGTH = 80;
const EMAIL_MAX_LENGTH = 254;
const PHONE_MAX_LENGTH = 20;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_PATTERN = /^[+0-9 ()-]*$/;

const texts = pl.accounts.details.form;

/**
 * The account owner's name, e-mail (login) and phone. The name is also saved in the staff profile;
 * the phone only in the client profile, or the staff profile without one.
 */
export function AccountDataForm({ account }: { account: AccountDetails }) {
  const hasStaffProfile =
    account.profiles.some((profile) => profile.kind === 'CLIENT') &&
    account.profiles.some((profile) => profile.kind === 'STAFF');
  const [confirming, setConfirming] = useState<NameFields | null>(null);
  const queryClient = useQueryClient();
  const initial = {
    firstName: account.firstName ?? '',
    lastName: account.lastName ?? '',
    email: account.email,
    phone: account.phone ?? '',
  };
  const [values, setValues] = useState(initial);
  const [submitted, setSubmitted] = useState(false);
  const [saved, setSaved] = useState(false);
  const save = useMutation({
    mutationFn: () =>
      updateAccount(account.id, {
        email: values.email.trim(),
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        phone: values.phone.trim() === '' ? null : values.phone.trim(),
      }),
    onSuccess: async (updated) => {
      setSaved(true);
      queryClient.setQueryData(accountKeys.details(account.id), updated);
      await queryClient.invalidateQueries({ queryKey: accountKeys.all });
    },
  });

  const closed = account.status === 'CLOSED';
  const errors = {
    firstName: requiredText(values.firstName, NAME_MAX_LENGTH),
    lastName: requiredText(values.lastName, NAME_MAX_LENGTH),
    email:
      requiredText(values.email, EMAIL_MAX_LENGTH) ??
      (EMAIL_PATTERN.test(values.email.trim()) ? undefined : texts.invalidEmail),
    phone:
      values.phone.trim().length > PHONE_MAX_LENGTH
        ? pl.accounts.invite.tooLong(PHONE_MAX_LENGTH)
        : PHONE_PATTERN.test(values.phone.trim())
          ? undefined
          : texts.invalidPhone,
  };
  const accountExists =
    save.error instanceof ApiError && save.error.code === 'ACCOUNT_ALREADY_EXISTS';
  const changed = (Object.keys(initial) as (keyof typeof initial)[]).some(
    (field) => values[field].trim() !== initial[field],
  );

  function change(field: keyof typeof values, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setSaved(false);
    if (save.isError) {
      save.reset();
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (Object.values(errors).some((error) => error !== undefined)) {
      return;
    }
    const names = hasStaffProfile ? changedNames(initial, values) : null;
    if (names) {
      setConfirming(names);
      return;
    }
    save.mutate();
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <Stack maw={560}>
        {closed && <Alert color="gray">{texts.closedHint}</Alert>}
        <Stack mih={FIELDS_MIN_HEIGHT}>
          <Group grow align="flex-start">
            <TextInput
              label={texts.firstName}
              withAsterisk
              disabled={closed}
              value={values.firstName}
              onChange={(event) => change('firstName', event.currentTarget.value)}
              error={submitted ? errors.firstName : undefined}
            />
            <TextInput
              label={texts.lastName}
              withAsterisk
              disabled={closed}
              value={values.lastName}
              onChange={(event) => change('lastName', event.currentTarget.value)}
              error={submitted ? errors.lastName : undefined}
            />
          </Group>
          <TextInput
            label={texts.email}
            withAsterisk
            type="email"
            disabled={closed}
            value={values.email}
            onChange={(event) => change('email', event.currentTarget.value)}
            error={
              accountExists ? texts.errors.accountExists : submitted ? errors.email : undefined
            }
          />
          <TextInput
            label={texts.phone}
            type="tel"
            disabled={closed}
            value={values.phone}
            onChange={(event) => change('phone', event.currentTarget.value)}
            error={submitted ? errors.phone : undefined}
            w={{ base: '100%', sm: 260 }}
          />
        </Stack>
        {!closed && (
          <Group>
            <Button type="submit" loading={save.isPending} disabled={!changed}>
              {texts.save}
            </Button>
            <Button
              variant="default"
              disabled={!changed || save.isPending}
              onClick={() => {
                setValues(initial);
                setSubmitted(false);
                save.reset();
              }}
            >
              {texts.reset}
            </Button>
          </Group>
        )}
        {saved && !changed && (
          <Alert color="green" role="status">
            {texts.saved}
          </Alert>
        )}
        {save.isError && !accountExists && (
          <Alert color="red" role="alert">
            {texts.errors.unknown}
          </Alert>
        )}
      </Stack>
      {hasStaffProfile && (
        <NameSyncModal
          fields={confirming}
          other="STAFF"
          onCancel={() => setConfirming(null)}
          onConfirm={() => {
            setConfirming(null);
            save.mutate();
          }}
        />
      )}
    </form>
  );
}

function requiredText(value: string, maxLength: number): string | undefined {
  if (!value.trim()) {
    return pl.auth.fieldRequired;
  }
  return value.trim().length > maxLength ? pl.accounts.invite.tooLong(maxLength) : undefined;
}
