import { useState } from 'react';
import { Alert, Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/shared/api/apiClient';
import { pl } from '@/shared/i18n/pl';
import { scheduleKeys, type EventKind } from './scheduleApi';
import { scheduleErrorMessage } from './scheduleFormat';

type Target = { id: string; version: number; title: string; kind?: EventKind };

export function CancelStudioEventModal({
  target,
  onClose,
  onCancelled,
}: {
  target: Target | null;
  onClose: () => void;
  onCancelled: () => void;
}) {
  return target ? (
    <CancelForm key={target.id} target={target} onClose={onClose} onCancelled={onCancelled} />
  ) : null;
}

function CancelForm({
  target,
  onClose,
  onCancelled,
}: {
  target: Target;
  onClose: () => void;
  onCancelled: () => void;
}) {
  const t = pl.substitutions;
  const consultation = target.kind === 'CONSULTATION';
  const consultationTexts = pl.schedule.consultation;
  const client = useQueryClient();
  const [reason, setReason] = useState('');
  const mutation = useMutation({
    mutationFn: () =>
      apiFetch<void>(`/api/v1/schedule-events/${target.id}/cancel`, {
        method: 'POST',
        body: { version: target.version, reason: reason.trim() },
      }),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: scheduleKeys.all });
      onCancelled();
      onClose();
    },
  });
  return (
    <Modal
      opened
      onClose={onClose}
      closeOnEscape={!mutation.isPending}
      closeOnClickOutside={!mutation.isPending}
      withCloseButton={!mutation.isPending}
      title={consultation ? consultationTexts.cancelTitle : t.cancelTitle}
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (reason.trim()) mutation.mutate();
        }}
      >
        <Stack>
          <Text fw={600}>{target.title}</Text>
          <Text size="sm">{consultation ? consultationTexts.cancelHint : t.cancelHint}</Text>
          <Textarea
            label={t.cancelReason}
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
            required
            maxLength={1000}
            minRows={2}
            disabled={mutation.isPending}
          />
          {mutation.error && <Alert color="red">{scheduleErrorMessage(mutation.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose} disabled={mutation.isPending}>
              {t.back}
            </Button>
            <Button
              type="submit"
              color="red"
              loading={mutation.isPending}
              disabled={!reason.trim()}
            >
              {consultation ? consultationTexts.cancel : t.cancelEvent}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
