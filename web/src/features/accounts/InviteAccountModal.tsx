import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
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
import { inviteAccount, type InvitedRole, type NewInvitation } from './invitationsApi';

// Same limits as the backend's InviteRequest.
const NAME_MAX_LENGTH = 80;
const EMAIL_MAX_LENGTH = 254;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

type InviteAccountModalProps = {
  opened: boolean;
  onClose: () => void;
};

export function InviteAccountModal({ opened, onClose }: InviteAccountModalProps) {
  const [role, setRole] = useState<InvitedRole>('CLIENT');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const invite = useMutation({ mutationFn: inviteAccount });

  function reset() {
    setRole('CLIENT');
    setFirstName('');
    setLastName('');
    setEmail('');
    setSubmitted(false);
    invite.reset();
  }

  function handleClose() {
    reset();
    onClose();
  }

  const errors = {
    firstName: requiredText(firstName, NAME_MAX_LENGTH),
    lastName: requiredText(lastName, NAME_MAX_LENGTH),
    email:
      requiredText(email, EMAIL_MAX_LENGTH) ??
      (EMAIL_PATTERN.test(email.trim()) ? undefined : pl.accounts.invite.invalidEmail),
  };
  const accountExists =
    invite.error instanceof ApiError && invite.error.code === 'ACCOUNT_ALREADY_EXISTS';

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (errors.firstName || errors.lastName || errors.email) {
      return;
    }
    invite.mutate({
      email: email.trim(),
      firstName: firstName.trim(),
      lastName: lastName.trim(),
      role,
    });
  }

  return (
    <Modal
      opened={opened}
      onClose={handleClose}
      title={pl.accounts.invite.title}
      size="md"
      centered
    >
      {invite.isSuccess ? (
        <Stack>
          <SentAlert invitation={invite.data} name={`${firstName.trim()} ${lastName.trim()}`} />
          <Group justify="flex-end">
            <Button variant="default" onClick={handleClose}>
              {pl.accounts.invite.close}
            </Button>
            <Button onClick={reset}>{pl.accounts.invite.inviteAnother}</Button>
          </Group>
        </Stack>
      ) : (
        <form onSubmit={handleSubmit} noValidate>
          <Stack>
            {invite.isError && !accountExists && (
              <Alert color="red" role="alert">
                {errorMessage(invite.error)}
              </Alert>
            )}
            <Stack gap={4}>
              <Text size="sm" fw={500}>
                {pl.accounts.invite.role}
              </Text>
              <SegmentedControl
                fullWidth
                value={role}
                onChange={(value) => setRole(value as InvitedRole)}
                data={[
                  { value: 'CLIENT', label: pl.accounts.roles.CLIENT },
                  { value: 'TRAINER', label: pl.accounts.roles.TRAINER },
                ]}
              />
            </Stack>
            <Group grow align="flex-start">
              <TextInput
                label={pl.accounts.invite.firstName}
                withAsterisk
                value={firstName}
                onChange={(event) => setFirstName(event.currentTarget.value)}
                error={submitted ? errors.firstName : undefined}
              />
              <TextInput
                label={pl.accounts.invite.lastName}
                withAsterisk
                value={lastName}
                onChange={(event) => setLastName(event.currentTarget.value)}
                error={submitted ? errors.lastName : undefined}
              />
            </Group>
            <TextInput
              label={pl.accounts.invite.email}
              withAsterisk
              type="email"
              value={email}
              onChange={(event) => {
                setEmail(event.currentTarget.value);
                if (accountExists) {
                  invite.reset();
                }
              }}
              error={
                accountExists
                  ? pl.accounts.invite.errors.accountExists
                  : submitted
                    ? errors.email
                    : undefined
              }
            />
            <Text size="sm" c="dimmed">
              {pl.accounts.invite.hint}
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={handleClose}>
                {pl.accounts.invite.cancel}
              </Button>
              <Button type="submit" loading={invite.isPending}>
                {pl.accounts.invite.submit}
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

function requiredText(value: string, maxLength: number): string | undefined {
  if (!value.trim()) {
    return pl.auth.fieldRequired;
  }
  return value.trim().length > maxLength ? pl.accounts.invite.tooLong(maxLength) : undefined;
}

function errorMessage(error: Error): string {
  if (error instanceof ApiError && error.code === 'EMAIL_NOT_SENT') {
    return pl.accounts.invite.errors.emailNotSent;
  }
  return pl.accounts.invite.errors.unknown;
}
