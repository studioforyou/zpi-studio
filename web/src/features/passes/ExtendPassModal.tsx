import { useState } from 'react';
import { Alert, Button, Group, Modal, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { extendCustomerPass, type CustomerPass } from './customerPassesApi';
import { daysInclusive, formatDate, passChangeErrorMessage } from './passFormat';

type Props = {
  /** The pass to extend; `null` keeps the modal closed. */
  pass: CustomerPass | null;
  onClose: () => void;
};

/** Moves a pass's last day to a later date, as an exception agreed with the participant. */
export function ExtendPassModal({ pass, onClose }: Props) {
  const queryClient = useQueryClient();
  const [validUntil, setValidUntil] = useState('');
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const extend = useMutation({
    mutationFn: () =>
      extendCustomerPass(pass!.customerId, pass!.id, {
        validUntil,
        reason: reason.trim() || null,
        version: pass!.version,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['customer-passes', pass!.customerId] });
      close();
    },
  });

  function close() {
    setValidUntil('');
    setReason('');
    setSubmitted(false);
    extend.reset();
    onClose();
  }

  function submit() {
    setSubmitted(true);
    if (validUntil !== '') {
      extend.mutate();
    }
  }

  const current = pass?.validUntil ?? null;
  const longerBy =
    current !== null && validUntil > current ? daysInclusive(current, validUntil) - 1 : null;

  return (
    <Modal
      opened={pass !== null}
      onClose={close}
      title={pass ? pl.passChanges.extendTitle(pass.productName) : ''}
    >
      <Stack>
        {current !== null && (
          <Text size="sm">{pl.passChanges.currentValidUntil(formatDate(current))}</Text>
        )}
        <TextInput
          type="date"
          label={pl.passChanges.newValidUntil}
          required
          min={current ?? undefined}
          value={validUntil}
          onChange={(event) => setValidUntil(event.currentTarget.value)}
          description={longerBy !== null ? pl.passChanges.extendedBy(longerBy) : undefined}
          error={submitted && validUntil === '' ? pl.passChanges.required : undefined}
        />
        <Textarea
          label={pl.passChanges.reason}
          placeholder={pl.passChanges.reasonPlaceholder}
          maxLength={500}
          rows={2}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
        />
        {extend.isError && <Alert color="red">{passChangeErrorMessage(extend.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>
            {pl.passChanges.cancel}
          </Button>
          <Button onClick={submit} loading={extend.isPending}>
            {pl.passChanges.save}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
