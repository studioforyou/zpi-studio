import { useState } from 'react';
import {
  Alert,
  Button,
  Group,
  Modal,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { formatMoney } from '@/features/passes/passFormat';
import { pl } from '@/shared/i18n/pl';
import {
  recordPayout,
  reversePayout,
  settlementAmount,
  settlementError,
  type PayoutMethod,
  type TrainerPayout,
  type TrainerSettlement,
} from './trainerSettlementsApi';

const texts = pl.statistics.settlements;

export function TrainerPayoutForm({
  settlement,
  reverse,
  today,
  onClose,
  onSaved,
  onRefresh,
}: {
  settlement: TrainerSettlement;
  reverse?: TrainerPayout;
  today: string;
  onClose: () => void;
  onSaved: (data: TrainerSettlement) => void;
  onRefresh: () => Promise<unknown>;
}) {
  const row = settlement.summary;
  const [operationId] = useState(() => crypto.randomUUID());
  const [amount, setAmount] = useState(
    (row.remaining.amountMinor / 100).toFixed(2).replace('.', ','),
  );
  const [date, setDate] = useState(today);
  const [method, setMethod] = useState<PayoutMethod>('TRANSFER');
  const [note, setNote] = useState('');
  const minor = settlementAmount(amount);
  const validAmount = minor !== null && minor > 0 && minor <= row.remaining.amountMinor;
  const valid = reverse ? !!note.trim() : validAmount && date >= '1900-01-01' && date <= today;
  const save = useMutation({
    retry: false,
    mutationFn: () =>
      reverse
        ? reversePayout(row.id, reverse.id, {
            operationId,
            version: row.version,
            reason: note.trim(),
          })
        : recordPayout(row.id, {
            operationId,
            version: row.version,
            amount: { amountMinor: minor!, currency: row.amount.currency },
            paidOn: date,
            method,
            note: note.trim(),
          }),
    onSuccess: onSaved,
  });
  return (
    <Modal
      opened
      centered
      title={reverse ? texts.reverseTitle : texts.payoutTitle}
      onClose={onClose}
      closeOnClickOutside={!save.isPending}
      closeOnEscape={!save.isPending}
      withCloseButton={!save.isPending}
    >
      <Stack gap="md">
        <Group justify="space-between">
          <Text fw={600}>{row.displayName}</Text>
          <Text size="sm">
            {reverse
              ? formatMoney(reverse.amount)
              : `${texts.remaining}: ${formatMoney(row.remaining)}`}
          </Text>
        </Group>
        <Text size="sm" c="dimmed">
          {reverse ? texts.reversalNotice : texts.payoutNotice}
        </Text>
        {!reverse && (
          <>
            <TextInput
              label={`${texts.payoutAmount} (${row.amount.currency})`}
              value={amount}
              onChange={(event) => setAmount(event.currentTarget.value)}
              inputMode="decimal"
              error={!validAmount ? texts.tooMuch : undefined}
              disabled={save.isPending}
            />
            <TextInput
              type="date"
              label={texts.payoutDate}
              min="1900-01-01"
              max={today}
              value={date}
              onChange={(event) => setDate(event.currentTarget.value)}
              disabled={save.isPending}
            />
            <Select
              label={texts.method}
              value={method}
              onChange={(value) => setMethod(value as PayoutMethod)}
              data={Object.entries(texts.methods).map(([value, label]) => ({ value, label }))}
              disabled={save.isPending}
            />
          </>
        )}
        <Textarea
          label={reverse ? texts.reason : texts.note}
          value={note}
          onChange={(event) => setNote(event.currentTarget.value)}
          required={!!reverse}
          maxLength={1000}
          rows={2}
          disabled={save.isPending}
        />
        {save.isError && (
          <Alert color="red" role="alert">
            <Stack gap="xs">
              <Text size="sm">{settlementError(save.error)}</Text>
              <Button
                variant="light"
                size="xs"
                onClick={async () => {
                  await onRefresh();
                  save.reset();
                }}
              >
                {texts.refresh}
              </Button>
            </Stack>
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose} disabled={save.isPending}>
            {texts.cancel}
          </Button>
          <Button
            color={reverse ? 'red' : undefined}
            onClick={() => save.mutate()}
            loading={save.isPending}
            disabled={!valid}
          >
            {reverse ? texts.reverse : texts.payout}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
