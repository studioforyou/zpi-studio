import { useState } from 'react';
import { Alert, Button, Group, List, Modal, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { paths } from '@/app/paths';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import {
  accountKeys,
  closeAccount,
  reactivateAccount,
  suspendAccount,
  type AccountDetails,
  type AccountProfile,
} from './accountsApi';

export type StatusAction = 'suspend' | 'reactivate' | 'close';

const texts = pl.accounts.details.actions;

const REASON_MAX_LENGTH = 500;

type AccountStatusModalProps = {
  account: AccountDetails;
  action: StatusAction;
  /** The account's profiles other than the one shown; suspending and closing affect them too. */
  otherProfiles?: AccountProfile[];
  onClose: () => void;
};

/**
 * Confirms suspending, reactivating or closing an account, with an optional reason for its
 * history. Closing cannot be undone, so it also asks for the account's name to be typed in.
 */
export function AccountStatusModal({
  account,
  action,
  otherProfiles = [],
  onClose,
}: AccountStatusModalProps) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [reason, setReason] = useState('');
  const [typedName, setTypedName] = useState('');
  const change = useMutation({
    mutationFn: async () => {
      const trimmed = reason.trim() === '' ? null : reason.trim();
      if (action === 'close') {
        await closeAccount(account.id, trimmed);
        return null;
      }
      return action === 'suspend'
        ? suspendAccount(account.id, trimmed)
        : reactivateAccount(account.id, trimmed);
    },
    onSuccess: async (updated) => {
      if (updated) {
        queryClient.setQueryData(accountKeys.details(account.id), updated);
      }
      await queryClient.invalidateQueries({ queryKey: accountKeys.all });
      onClose();
      if (action === 'close') {
        void navigate(paths.accounts);
      }
    },
  });

  const content = {
    suspend: {
      title: texts.suspendTitle,
      message: texts.suspendConfirm(account.displayName),
      submit: texts.suspend,
      color: 'red',
    },
    reactivate: {
      title: texts.reactivateTitle,
      message: texts.reactivateConfirm(account.displayName),
      submit: texts.reactivate,
      color: 'green',
    },
    close: {
      title: texts.closeTitle,
      message: texts.closeConfirm(account.displayName),
      submit: texts.closeSubmit,
      color: 'red',
    },
  }[action];
  const nameConfirmed =
    action !== 'close' ||
    typedName.trim().toLocaleLowerCase('pl') === account.displayName.toLocaleLowerCase('pl');
  const statusChanged =
    change.error instanceof ApiError && change.error.code === 'ACCOUNT_STATUS_CONFLICT';

  return (
    <Modal opened onClose={onClose} title={content.title} centered>
      <Stack>
        {change.isError && (
          <Alert color="red" role="alert">
            {statusChanged ? texts.errors.statusChanged : texts.errors.unknown}
          </Alert>
        )}
        <Text size="sm">{content.message}</Text>
        {action !== 'reactivate' && otherProfiles.length > 0 && (
          <Stack gap={4}>
            <Text size="sm" fw={700}>
              {texts.otherProfiles}
            </Text>
            <Text size="sm">{texts.affected}</Text>
            <List size="sm">
              {otherProfiles.map((profile) => (
                <List.Item key={profile.id}>
                  {texts.affectedProfile(`${profile.firstName} ${profile.lastName}`, profile.kind)}
                </List.Item>
              ))}
            </List>
          </Stack>
        )}
        <Textarea
          label={texts.reason}
          placeholder={texts.reasonPlaceholder}
          rows={2}
          maxLength={REASON_MAX_LENGTH}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
        />
        {action === 'close' && (
          <TextInput
            label={texts.closeTypeName}
            description={account.displayName}
            value={typedName}
            onChange={(event) => setTypedName(event.currentTarget.value)}
          />
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {texts.cancel}
          </Button>
          <Button
            color={content.color}
            loading={change.isPending}
            disabled={!nameConfirmed}
            onClick={() => change.mutate()}
          >
            {content.submit}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
