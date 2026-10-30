import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  Code,
  CopyButton,
  Group,
  Modal,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { createAdminAccount, type NewAdminAccount } from './adminAccountsApi';
import { inviteAccount, type InvitedRole, type NewInvitation } from './invitationsApi';

// Same limits as the backend's InviteRequest and CreateAdminRequest.
const NAME_MAX_LENGTH = 80;
const EMAIL_MAX_LENGTH = 254;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Clients and trainers get an invitation e-mail; administrators get an active account with a
 * generated password right away.
 */
type Role = InvitedRole | 'ADMIN';

type AddPersonInput = {
  role: Role;
  email: string;
  firstName: string;
  lastName: string;
};

type AddPersonResult =
  { kind: 'invited'; invitation: NewInvitation } | { kind: 'created'; account: NewAdminAccount };

async function addPerson({ role, ...person }: AddPersonInput): Promise<AddPersonResult> {
  if (role === 'ADMIN') {
    return { kind: 'created', account: await createAdminAccount(person) };
  }
  return { kind: 'invited', invitation: await inviteAccount({ ...person, role }) };
}

type InviteAccountModalProps = {
  opened: boolean;
  onClose: () => void;
};

export function InviteAccountModal({ opened, onClose }: InviteAccountModalProps) {
  const texts = pl.accounts.invite;
  const [role, setRole] = useState<Role>('CLIENT');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const add = useMutation({ mutationFn: addPerson });

  function reset() {
    setRole('CLIENT');
    setFirstName('');
    setLastName('');
    setEmail('');
    setSubmitted(false);
    add.reset();
  }

  function handleClose() {
    reset();
    onClose();
  }

  const isAdmin = role === 'ADMIN';
  const errors = {
    firstName: requiredText(firstName, NAME_MAX_LENGTH),
    lastName: requiredText(lastName, NAME_MAX_LENGTH),
    email:
      requiredText(email, EMAIL_MAX_LENGTH) ??
      (EMAIL_PATTERN.test(email.trim()) ? undefined : texts.invalidEmail),
  };
  const accountExists =
    add.error instanceof ApiError && add.error.code === 'ACCOUNT_ALREADY_EXISTS';

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (Object.values(errors).some((error) => error !== undefined)) {
      return;
    }
    add.mutate({
      role,
      email: email.trim(),
      firstName: firstName.trim(),
      lastName: lastName.trim(),
    });
  }

  const name = `${firstName.trim()} ${lastName.trim()}`;

  return (
    <Modal
      opened={opened}
      onClose={handleClose}
      title={texts.title}
      size="md"
      centered
      // The generated password is shown only once, so a stray click must not close it.
      closeOnClickOutside={add.data?.kind !== 'created'}
    >
      {add.isSuccess ? (
        <Stack>
          {add.data.kind === 'invited' ? (
            <SentAlert invitation={add.data.invitation} name={name} />
          ) : (
            <CreatedAdmin account={add.data.account} name={name} />
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={handleClose}>
              {texts.close}
            </Button>
            <Button onClick={reset}>{texts.inviteAnother}</Button>
          </Group>
        </Stack>
      ) : (
        <form onSubmit={handleSubmit} noValidate>
          <Stack>
            {add.isError && !accountExists && (
              <Alert color="red" role="alert">
                {errorMessage(add.error, isAdmin)}
              </Alert>
            )}
            <Stack gap={4}>
              <Text size="sm" fw={500}>
                {texts.role}
              </Text>
              <SegmentedControl
                fullWidth
                value={role}
                onChange={(value) => {
                  setRole(value as Role);
                  add.reset();
                }}
                data={[
                  { value: 'CLIENT', label: pl.accounts.roles.CLIENT },
                  { value: 'TRAINER', label: pl.accounts.roles.TRAINER },
                  { value: 'ADMIN', label: pl.accounts.roles.ADMIN },
                ]}
              />
            </Stack>
            <Group grow align="flex-start">
              <TextInput
                label={texts.firstName}
                withAsterisk
                value={firstName}
                onChange={(event) => setFirstName(event.currentTarget.value)}
                error={submitted ? errors.firstName : undefined}
              />
              <TextInput
                label={texts.lastName}
                withAsterisk
                value={lastName}
                onChange={(event) => setLastName(event.currentTarget.value)}
                error={submitted ? errors.lastName : undefined}
              />
            </Group>
            <TextInput
              label={texts.email}
              withAsterisk
              type="email"
              value={email}
              onChange={(event) => {
                setEmail(event.currentTarget.value);
                if (accountExists) {
                  add.reset();
                }
              }}
              error={
                accountExists ? texts.errors.accountExists : submitted ? errors.email : undefined
              }
            />
            <Text size="sm" c="dimmed">
              {isAdmin ? texts.adminHint : texts.hint}
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={handleClose}>
                {texts.cancel}
              </Button>
              <Button type="submit" loading={add.isPending}>
                {isAdmin ? texts.createAdmin : texts.submit}
              </Button>
            </Group>
          </Stack>
        </form>
      )}
    </Modal>
  );
}

function SentAlert({ invitation, name }: { invitation: NewInvitation; name: string }) {
  const validUntil = new Date(invitation.expiresAt).toLocaleDateString('pl-PL');
  return (
    <Alert color="green" title={pl.accounts.invite.sentTitle}>
      {pl.accounts.invite.sent(name, invitation.email, validUntil)}
    </Alert>
  );
}

function CreatedAdmin({ account, name }: { account: NewAdminAccount; name: string }) {
  const texts = pl.accounts.invite;
  return (
    <Stack gap="sm">
      <Alert color="green" title={texts.createdTitle}>
        {texts.created(name, account.email)}
      </Alert>
      <Stack gap={4}>
        <Text size="sm" fw={500}>
          {texts.generatedPassword}
        </Text>
        <Group gap="xs" wrap="nowrap">
          <Code fz="md" px="sm" py={6} style={{ flex: 1 }} aria-label={texts.generatedPassword}>
            {account.password}
          </Code>
          <CopyButton value={account.password}>
            {({ copied, copy }) => (
              <Button
                variant={copied ? 'light' : 'default'}
                color={copied ? 'teal' : undefined}
                onClick={copy}
              >
                {copied ? texts.copied : texts.copy}
              </Button>
            )}
          </CopyButton>
        </Group>
      </Stack>
      <Alert color="yellow">{texts.passwordShownOnce}</Alert>
    </Stack>
  );
}

function requiredText(value: string, maxLength: number): string | undefined {
  if (!value.trim()) {
    return pl.auth.fieldRequired;
  }
  return value.trim().length > maxLength ? pl.accounts.invite.tooLong(maxLength) : undefined;
}

function errorMessage(error: Error, isAdmin: boolean): string {
  if (error instanceof ApiError && error.code === 'EMAIL_NOT_SENT') {
    return pl.accounts.invite.errors.emailNotSent;
  }
  return isAdmin ? pl.accounts.invite.errors.adminNotCreated : pl.accounts.invite.errors.unknown;
}
