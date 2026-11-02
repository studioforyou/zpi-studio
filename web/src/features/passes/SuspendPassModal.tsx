import { useState } from 'react';
import {
  Alert,
  Button,
  Group,
  Modal,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { cancelPassSuspension, suspendCustomerPass, type CustomerPass } from './customerPassesApi';
import {
  addDays,
  daysInclusive,
  formatDate,
  passChangeErrorMessage,
  todayIsoDate,
} from './passFormat';
import type { PassProduct } from './passesApi';

type Props = {
  /** The pass to suspend; `null` keeps the modal closed. */
  pass: CustomerPass | null;
  /** The pass's product with its suspension rules, if it is known. */
  product: PassProduct | undefined;
  onClose: () => void;
};

/** Suspends a pass for a period, and cancels its current or future suspensions. */
export function SuspendPassModal({ pass, product, onClose }: Props) {
  const queryClient = useQueryClient();
  const [startsOn, setStartsOn] = useState(todayIsoDate);
  const [endsOn, setEndsOn] = useState('');
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ['customer-passes', pass!.customerId] });
  }

  const suspend = useMutation({
    mutationFn: () =>
      suspendCustomerPass(pass!.customerId, pass!.id, {
        startsOn,
        endsOn,
        reason: reason.trim() || null,
        version: pass!.version,
      }),
    onSuccess: async () => {
      await refresh();
      close();
    },
  });

  const cancelSuspension = useMutation({
    mutationFn: (suspensionId: string) =>
      cancelPassSuspension(pass!.customerId, pass!.id, suspensionId, pass!.version),
    onSuccess: async () => {
      await refresh();
      close();
    },
  });

  function close() {
    setStartsOn(todayIsoDate());
    setEndsOn('');
    setReason('');
    setSubmitted(false);
    suspend.reset();
    cancelSuspension.reset();
    onClose();
  }

  function submit() {
    setSubmitted(true);
    if (startsOn !== '' && endsOn !== '') {
      suspend.mutate();
    }
  }

  const today = todayIsoDate();
  const allowed = product?.suspensionAllowed ?? true;
  const suspensions = pass?.suspensions ?? [];
  const usedDays = suspensions.reduce(
    (sum, suspension) => sum + daysInclusive(suspension.startsOn, suspension.endsOn),
    0,
  );
  const days = startsOn !== '' && endsOn >= startsOn ? daysInclusive(startsOn, endsOn) : null;
  const required = (missing: boolean) =>
    submitted && missing ? pl.passChanges.required : undefined;
  const error = suspend.error ?? cancelSuspension.error;

  return (
    <Modal
      opened={pass !== null}
      onClose={close}
      title={pass ? pl.passChanges.suspendTitle(pass.productName) : ''}
      size="lg"
    >
      <Stack>
        {!allowed ? (
          <Alert color="yellow">{pl.passChanges.notAllowed}</Alert>
        ) : (
          <Text size="sm">
            {product?.maxSuspensionDays != null
              ? pl.passChanges.limit(product.maxSuspensionDays, usedDays)
              : pl.passChanges.noLimit}
          </Text>
        )}
        {suspensions.length > 0 && (
          <Stack gap={4}>
            <Text fw={500} size="sm">
              {pl.passChanges.suspensions}
            </Text>
            <Table>
              <Table.Tbody>
                {suspensions.map((suspension) => (
                  <Table.Tr key={suspension.id}>
                    <Table.Td>
                      {formatDate(suspension.startsOn)} – {formatDate(suspension.endsOn)}
                    </Table.Td>
                    <Table.Td>{suspension.reason ?? ''}</Table.Td>
                    <Table.Td>
                      {suspension.endsOn >= today && (
                        <Button
                          size="xs"
                          variant="subtle"
                          color="red"
                          loading={
                            cancelSuspension.isPending &&
                            cancelSuspension.variables === suspension.id
                          }
                          onClick={() => cancelSuspension.mutate(suspension.id)}
                        >
                          {pl.passChanges.cancelSuspension}
                        </Button>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
            <Text size="xs" c="dimmed">
              {pl.passChanges.cancelSuspensionHint}
            </Text>
          </Stack>
        )}
        {allowed && (
          <>
            <Group grow>
              <TextInput
                type="date"
                label={pl.passChanges.startsOn}
                required
                min={today}
                max={pass?.validUntil ?? undefined}
                value={startsOn}
                onChange={(event) => setStartsOn(event.currentTarget.value)}
                error={required(startsOn === '')}
              />
              <TextInput
                type="date"
                label={pl.passChanges.endsOn}
                required
                min={startsOn || today}
                max={pass?.validUntil ?? undefined}
                value={endsOn}
                onChange={(event) => setEndsOn(event.currentTarget.value)}
                error={required(endsOn === '')}
              />
            </Group>
            {days !== null && pass?.validUntil && (
              <Text size="sm">
                {pl.passChanges.suspendedDays(days, formatDate(addDays(pass.validUntil, days)))}
              </Text>
            )}
            <Text size="sm" c="dimmed">
              {pl.passChanges.reservationsKept}
            </Text>
            <Textarea
              label={pl.passChanges.reason}
              placeholder={pl.passChanges.reasonPlaceholder}
              maxLength={500}
              rows={2}
              value={reason}
              onChange={(event) => setReason(event.currentTarget.value)}
            />
          </>
        )}
        {error && <Alert color="red">{passChangeErrorMessage(error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>
            {pl.passChanges.cancel}
          </Button>
          {allowed && (
            <Button onClick={submit} loading={suspend.isPending}>
              {pl.passChanges.save}
            </Button>
          )}
        </Group>
      </Stack>
    </Modal>
  );
}
