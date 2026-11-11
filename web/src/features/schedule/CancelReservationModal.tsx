import { useState } from 'react';
import { Alert, Button, Group, Modal, Radio, Stack, Text, Textarea } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { attendanceKeys } from './attendanceApi';
import {
  cancelReservation,
  reservationKeys,
  type EventReservation,
  type StaffCancellationInput,
} from './reservationsApi';

type Props = {
  eventId: string;
  /** The place as currently loaded; it is replaced by fresh data after a conflict. */
  reservation: EventReservation;
  onClose: () => void;
};

type EntryChoice = 'RETURN' | 'FORFEIT';

function cancellationError(error: unknown): string {
  const messages = pl.schedule.reservations.cancellation.errors;
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'VERSION_CONFLICT':
      case 'CANCELLATION_CONSEQUENCE_CHANGED':
        return messages.conflict;
      case 'EVENT_ALREADY_STARTED':
        return messages.started;
      case 'EVENT_NOT_BOOKABLE':
        return messages.notBookable;
      case 'VALIDATION_FAILED':
        return messages.validation;
      case 'NOT_FOUND':
        return messages.notFound;
    }
  }
  return messages.unknown;
}

function suggestion(suggested: boolean | null): EntryChoice | null {
  return suggested === null ? null : suggested ? 'RETURN' : 'FORFEIT';
}

/**
 * Cancels a participant's place on their behalf. The administrator decides whether the pass entry
 * goes back to the pass; the choice starts at what the cancellation terms give.
 */
export function CancelReservationModal({ eventId, reservation, onClose }: Props) {
  const queryClient = useQueryClient();
  const texts = pl.schedule.reservations.cancellation;
  const isMakeup = reservation.makeupCreditId != null;
  const hasPass = reservation.customerPassId !== null || isMakeup;
  const suggested = reservation.cancellation.suggestedEntryReturned;
  const [entry, setEntry] = useState<EntryChoice | null>(suggestion(suggested));
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const cancel = useMutation({
    mutationFn: (input: StaffCancellationInput) =>
      cancelReservation(eventId, reservation.reservationId, input),
    retry: false,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: reservationKeys.event(eventId) }),
        queryClient.invalidateQueries({ queryKey: attendanceKeys.event(eventId) }),
      ]);
      onClose();
    },
    // The loaded place may be out of date; a fresh one replaces `reservation`.
    onError: () => queryClient.invalidateQueries({ queryKey: reservationKeys.event(eventId) }),
  });
  const pending = cancel.isPending;

  function submit() {
    setSubmitted(true);
    if (pending || (hasPass && entry === null)) return;
    cancel.mutate({
      expectedVersion: reservation.version,
      returnEntry: hasPass ? entry === 'RETURN' : null,
      reason: reason.trim() || null,
    });
  }

  const suggestedText =
    suggested === null
      ? isMakeup
        ? texts.makeup.unknown
        : texts.suggested.unknown
      : suggested
        ? isMakeup
          ? texts.makeup.returned
          : texts.suggested.returned
        : isMakeup
          ? texts.makeup.forfeited
          : texts.suggested.forfeited;

  return (
    <Modal
      opened
      onClose={onClose}
      title={texts.title}
      size="md"
      closeOnEscape={!pending}
      closeOnClickOutside={!pending}
      withCloseButton={!pending}
    >
      <Stack>
        <Stack gap={2}>
          <Text fw={600}>{reservation.displayName}</Text>
          {hasPass && (
            <Text size="sm" c="dimmed">
              {texts.pass(reservation.passName)}
            </Text>
          )}
        </Stack>
        {isMakeup && <Text size="sm">{texts.makeup.note}</Text>}
        {hasPass ? (
          <>
            <Text size="sm">{suggestedText}</Text>
            {!reservation.cancellation.participant.allowed && (
              <Text size="sm" c="dimmed">
                {texts.participantCannot}
              </Text>
            )}
            <Radio.Group
              label={isMakeup ? texts.makeup.entry : texts.entry}
              value={entry}
              onChange={(value) => setEntry(value as EntryChoice)}
              error={submitted && entry === null ? texts.required : undefined}
            >
              <Group mt="xs">
                <Radio
                  value="RETURN"
                  label={isMakeup ? texts.makeup.returnEntry : texts.returnEntry}
                  disabled={pending}
                />
                <Radio
                  value="FORFEIT"
                  label={isMakeup ? texts.makeup.forfeitEntry : texts.forfeitEntry}
                  disabled={pending}
                />
              </Group>
            </Radio.Group>
          </>
        ) : (
          <Text size="sm">{texts.noPass}</Text>
        )}
        <Textarea
          label={texts.reason}
          placeholder={texts.reasonPlaceholder}
          maxLength={500}
          rows={2}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
          disabled={pending}
        />
        <Text size="sm" c="dimmed">
          {texts.waitlistNote}
        </Text>
        <Text size="sm" c="dimmed">
          {texts.historyNote}
        </Text>
        {cancel.isError && <Alert color="red">{cancellationError(cancel.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose} disabled={pending}>
            {texts.cancel}
          </Button>
          <Button color="red" onClick={submit} loading={pending}>
            {texts.save}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
