import { useState } from 'react';
import {
  Alert,
  Button,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
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
  const [direction, setDirection] = useState('add');
  const [amount, setAmount] = useState<number | string>('');
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
      setAmount('');
      setSubmitted(false);
      setRefreshed(true);
      correction.reset();
      await queryClient.invalidateQueries({ queryKey: ['customer-passes', customer.id] });
    },
  });

  const delta = typeof amount === 'number' ? (direction === 'add' ? amount : -amount) : null;
  const resulting =
    basis.entriesRemaining === null || delta === null ? null : basis.entriesRemaining + delta;
  const invalidAmount =
    typeof amount !== 'number' || !Number.isInteger(amount) || amount < 1 || amount > 32767;
  const invalidBalance = resulting === null || resulting < 0 || resulting > 32767;
  const mustRefresh = correction.isError && needsRefresh(correction.error);
  const pending = correction.isPending || refresh.isPending;

  function submit() {
    setSubmitted(true);
    if (pending || mustRefresh || invalidAmount || invalidBalance || delta === null) return;
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
        <Text fw={600}>{customer.displayName}</Text>
        <Text>{basis.productName}</Text>
        <Text>{text.currentBalance(basis.entriesRemaining)}</Text>
        <SegmentedControl
          aria-label={text.direction}
          value={direction}
          onChange={setDirection}
          disabled={pending || mustRefresh}
          data={[
            { value: 'add', label: text.add },
            { value: 'subtract', label: text.subtract },
          ]}
        />
        <NumberInput
          label={text.amount}
          required
          value={amount}
          onChange={setAmount}
          allowDecimal={false}
          allowNegative={false}
          min={1}
          max={32767}
          clampBehavior="none"
          disabled={pending || mustRefresh || basis.entriesRemaining === null}
          error={
            submitted && invalidAmount
              ? text.invalidAmount
              : !invalidAmount && invalidBalance
                ? text.invalidBalance
                : undefined
          }
        />
        {!invalidAmount && !invalidBalance && (
          <Text fw={600}>{text.resultingBalance(resulting!)}</Text>
        )}
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
              disabled={refresh.isPending || basis.entriesRemaining === null}
            >
              {text.save}
            </Button>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}
