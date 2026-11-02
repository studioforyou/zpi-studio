import { useState } from 'react';
import {
  Alert,
  Button,
  Group,
  Modal,
  NumberInput,
  Paper,
  Stack,
  Text,
  Textarea,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, INVALID_RESPONSE } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import {
  correctPassEntries,
  getCustomerPass,
  type CorrectPassEntries,
  type Customer,
  type CustomerPass,
} from './customerPassesApi';

type Props = {
  customer: Customer;
  pass: CustomerPass;
  onClose: () => void;
};

function needsRefresh(error: unknown): boolean {
  return (
    !(error instanceof ApiError) ||
    error.code === 'VERSION_CONFLICT' ||
    error.code === INVALID_RESPONSE ||
    error.status === null ||
    error.status >= 500
  );
}

function correctionError(error: unknown): string {
  const messages = pl.customerPasses.correction.errors;
  if (error instanceof ApiError) {
    if (error.code === 'VERSION_CONFLICT') return messages.conflict;
    if (needsRefresh(error)) return messages.uncertain;
    if (error.code === 'VALIDATION_FAILED') return messages.validation;
    if (error.code === 'NOT_FOUND') return messages.notFound;
    if (error.code === 'ACCESS_DENIED') return messages.accessDenied;
  }
  return needsRefresh(error) ? messages.uncertain : messages.unknown;
}

/** A correction is always bound to the participant, pass and version shown before saving. */
export function CorrectPassEntriesModal({ customer, pass, onClose }: Props) {
  const queryClient = useQueryClient();
  const [basis, setBasis] = useState(pass);
  const [newBalance, setNewBalance] = useState<number | string>('');
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [refreshed, setRefreshed] = useState(false);
  const text = pl.customerPasses.correction;

  const correction = useMutation({
    mutationFn: (request: CorrectPassEntries) => correctPassEntries(customer.id, pass.id, request),
    retry: false,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['customer-passes', customer.id] });
      onClose();
    },
  });

  const refresh = useMutation({
    mutationFn: () => getCustomerPass(customer.id, pass.id),
    retry: false,
    onSuccess: async (updated) => {
      setBasis(updated);
      setNewBalance('');
      setSubmitted(false);
      setRefreshed(true);
      correction.reset();
      await queryClient.invalidateQueries({ queryKey: ['customer-passes', customer.id] });
    },
  });

  const delta =
    typeof newBalance === 'number' && basis.entriesRemaining !== null
      ? newBalance - basis.entriesRemaining
      : null;
  const invalidBalance =
    typeof newBalance !== 'number' ||
    !Number.isInteger(newBalance) ||
    newBalance < 0 ||
    newBalance > 32767;
  const unchanged = delta === 0;
  const previewBalance = !invalidBalance && typeof newBalance === 'number' ? newBalance : null;
  const changeTone =
    delta !== null && delta > 0 ? 'green' : delta !== null && delta < 0 ? 'red' : 'gray';
  const mustRefresh = correction.isError && needsRefresh(correction.error);
  const pending = correction.isPending || refresh.isPending;

  function submit() {
    setSubmitted(true);
    if (pending || mustRefresh || invalidBalance || unchanged || delta === null) return;
    setRefreshed(false);
    correction.mutate({
      entriesDelta: delta,
      reason: reason.trim() || null,
      version: basis.version,
    });
  }

  return (
    <Modal
      opened
      onClose={onClose}
      title={text.title}
      size="md"
      closeOnEscape={!pending}
      closeOnClickOutside={!pending}
      withCloseButton={!pending}
    >
      <Stack>
        <Stack gap={2}>
          <Text fw={600}>{customer.displayName}</Text>
          <Text size="sm" c="dimmed">
            {basis.productName}
          </Text>
        </Stack>
        <NumberInput
          label={text.newBalance}
          description={text.balanceHint}
          required
          value={newBalance}
          onChange={setNewBalance}
          allowDecimal={false}
          allowNegative={false}
          min={0}
          max={32767}
          clampBehavior="none"
          disabled={pending || mustRefresh || basis.entriesRemaining === null}
          error={submitted && invalidBalance ? text.invalidBalance : undefined}
        />
        <Paper withBorder radius="md" p="md" bg="gray.0" role="group" aria-label={text.summary}>
          <Group justify="space-between" gap="sm">
            <Group gap="md" wrap="nowrap">
              <Stack gap={2}>
                <Text size="xs" c="dimmed">
                  {text.before}
                </Text>
                <Text size="xl" fw={700} lh={1.3}>
                  {basis.entriesRemaining ?? '—'}
                </Text>
              </Stack>
              <Text c="dimmed" aria-hidden="true">
                →
              </Text>
              <Stack gap={2}>
                <Text size="xs" c="dimmed">
                  {text.after}
                </Text>
                <Text
                  size="xl"
                  fw={700}
                  lh={1.3}
                  c={previewBalance === null ? 'dimmed' : undefined}
                >
                  {previewBalance ?? '—'}
                </Text>
              </Stack>
            </Group>
            {previewBalance !== null && delta !== null && (
              <Text
                component="span"
                px="sm"
                py={6}
                size="sm"
                fw={600}
                style={{
                  backgroundColor: `var(--mantine-color-${changeTone}-0)`,
                  color: `var(--mantine-color-${changeTone}-9)`,
                  borderRadius: 'var(--mantine-radius-sm)',
                  whiteSpace: 'nowrap',
                }}
                role="status"
                aria-live="polite"
              >
                {unchanged ? text.noChange : text.difference(delta)}
              </Text>
            )}
          </Group>
        </Paper>
        <Textarea
          label={text.reason}
          placeholder={text.reasonPlaceholder}
          maxLength={500}
          rows={2}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
          disabled={pending || mustRefresh}
        />
        <Text size="sm" c="dimmed">
          {text.historyNote}
        </Text>
        {basis.entriesRemaining === null && <Alert color="yellow">{text.unlimited}</Alert>}
        {refreshed && <Alert color="blue">{text.refreshed}</Alert>}
        {correction.isError && <Alert color="red">{correctionError(correction.error)}</Alert>}
        {refresh.isError && <Alert color="red">{text.errors.refresh}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose} disabled={pending}>
            {text.cancel}
          </Button>
          {mustRefresh ? (
            <Button onClick={() => refresh.mutate()} loading={refresh.isPending}>
              {text.refresh}
            </Button>
          ) : (
            <Button
              onClick={submit}
              loading={correction.isPending}
              disabled={refresh.isPending || basis.entriesRemaining === null || unchanged}
            >
              {text.save}
            </Button>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}
