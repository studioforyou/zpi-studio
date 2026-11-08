import { useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Group,
  List,
  Modal,
  Select,
  Stack,
  Text,
  Textarea,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { attendanceKeys } from './attendanceApi';
import {
  bookReservation,
  getBookingOptions,
  reservationKeys,
  searchParticipants,
  type BookingOptions,
  type Participant,
  type StaffBooking,
} from './reservationsApi';
import { formatDate } from './studioTime';

type Props = {
  eventId: string;
  onClose: () => void;
};

function bookingError(error: unknown): string {
  const messages = pl.schedule.reservations.booking.errors;
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'BOOKING_OVERRIDE_REQUIRED':
        return messages.override;
      case 'EVENT_NOT_BOOKABLE':
        return messages.notBookable;
      case 'CUSTOMER_NOT_ACTIVE':
        return messages.notActive;
      case 'PASS_NOT_USABLE':
      case 'PASS_ENTRIES_EXHAUSTED':
      case 'PASS_SERVICE_MISMATCH':
        return messages.pass;
      case 'VALIDATION_FAILED':
        return messages.validation;
      case 'NOT_FOUND':
        return messages.notFound;
    }
  }
  return messages.unknown;
}

function participantName(participant: Participant): string {
  const texts = pl.schedule.reservations;
  return participant.kind === 'CHILD'
    ? `${participant.displayName} (${texts.child.toLowerCase()})`
    : participant.displayName;
}

/**
 * Books a participant on the class on their behalf. The booking window does not apply; a full
 * class and each requirement the participant does not meet are shown and have to be confirmed.
 */
export function BookParticipantModal({ eventId, onClose }: Props) {
  const queryClient = useQueryClient();
  const texts = pl.schedule.reservations.booking;
  const [search, setSearch] = useState('');
  const [debouncedSearch] = useDebouncedValue(search, 300);
  const [participant, setParticipant] = useState<Participant | null>(null);
  const [passId, setPassId] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const participants = useQuery({
    queryKey: reservationKeys.participants(debouncedSearch),
    queryFn: () => searchParticipants(debouncedSearch),
    placeholderData: keepPreviousData,
  });
  // The chosen participant stays an option even when the current search no longer matches them.
  const found = participants.data ?? [];
  const participantOptions =
    participant && !found.some((item) => item.id === participant.id)
      ? [participant, ...found]
      : found;

  const options = useQuery({
    queryKey: reservationKeys.options(eventId, participant?.id ?? ''),
    queryFn: () => getBookingOptions(eventId, participant!.id),
    enabled: participant !== null,
  });
  const current = options.data;
  const passes = current?.eligiblePasses ?? [];
  const pass = passes.find((candidate) => candidate.id === passId) ?? null;
  const needsPass = passes.length > 0;
  const issues = current?.issues ?? [];

  const book = useMutation({
    mutationFn: (request: StaffBooking) => bookReservation(eventId, request),
    retry: false,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: reservationKeys.event(eventId) }),
        queryClient.invalidateQueries({ queryKey: attendanceKeys.event(eventId) }),
      ]);
      onClose();
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'BOOKING_OVERRIDE_REQUIRED') {
        // The issues changed meanwhile: show the current ones and ask again.
        setConfirmed(false);
        void options.refetch();
      }
    },
  });
  const pending = book.isPending;

  function selectParticipant(id: string | null) {
    const chosen = participantOptions.find((item) => item.id === id) ?? null;
    setParticipant(chosen);
    setPassId(null);
    setConfirmed(false);
    setSubmitted(false);
    book.reset();
  }

  // A single eligible pass is chosen for the administrator.
  const chosenPass = pass ?? (passes.length === 1 ? passes[0] : null);
  const canSubmit =
    current !== undefined &&
    current.allowed &&
    (!needsPass || chosenPass !== null) &&
    (issues.length === 0 || confirmed);

  function submit() {
    setSubmitted(true);
    if (pending || !canSubmit || participant === null) return;
    book.mutate({
      customerId: participant.id,
      customerPassId: needsPass ? chosenPass!.id : null,
      acceptedIssues: issues,
      reason: reason.trim() || null,
    });
  }

  return (
    <Modal
      opened
      onClose={onClose}
      title={texts.title}
      size="lg"
      closeOnEscape={!pending}
      closeOnClickOutside={!pending}
      withCloseButton={!pending}
    >
      <Stack>
        <Select
          label={texts.participant}
          placeholder={texts.participantPlaceholder}
          required
          searchable
          clearable
          searchValue={search}
          onSearchChange={setSearch}
          // The server filters by name, phone and e-mail; the options are not filtered again here.
          filter={({ options: all }) => all}
          data={participantOptions.map((item) => ({
            value: item.id,
            label: participantName(item),
          }))}
          renderOption={({ option }) => {
            const item = participantOptions.find((candidate) => candidate.id === option.value);
            return item ? <ParticipantOption participant={item} /> : option.label;
          }}
          value={participant?.id ?? null}
          onChange={selectParticipant}
          nothingFoundMessage={participants.isError ? texts.searchError : texts.noParticipants}
          disabled={pending}
          error={submitted && participant === null ? texts.required : undefined}
        />
        {participant !== null && options.isPending && (
          <Text size="sm" c="dimmed">
            {texts.checking}
          </Text>
        )}
        {options.isError && <Alert color="red">{texts.optionsError}</Alert>}
        {current && (
          <Options
            options={current}
            passId={chosenPass?.id ?? null}
            onPassChange={setPassId}
            confirmed={confirmed}
            onConfirmedChange={setConfirmed}
            submitted={submitted}
            disabled={pending}
          />
        )}
        {current?.allowed && (
          <>
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
              {texts.historyNote}
            </Text>
          </>
        )}
        {book.isError && <Alert color="red">{bookingError(book.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose} disabled={pending}>
            {texts.cancel}
          </Button>
          <Button
            onClick={submit}
            loading={pending}
            disabled={current === undefined || !current.allowed}
          >
            {texts.save}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function ParticipantOption({ participant }: { participant: Participant }) {
  const texts = pl.schedule.reservations.booking;
  return (
    <Stack gap={0}>
      <Text size="sm">{participantName(participant)}</Text>
      <Text size="xs" c="dimmed">
        {participant.email === null
          ? texts.noEmail
          : participant.emailOfGuardian
            ? texts.guardianEmail(participant.email)
            : participant.email}
      </Text>
    </Stack>
  );
}

function Options({
  options,
  passId,
  onPassChange,
  confirmed,
  onConfirmedChange,
  submitted,
  disabled,
}: {
  options: BookingOptions;
  passId: string | null;
  onPassChange: (passId: string | null) => void;
  confirmed: boolean;
  onConfirmedChange: (confirmed: boolean) => void;
  submitted: boolean;
  disabled: boolean;
}) {
  const reservations = pl.schedule.reservations;
  const texts = reservations.booking;
  if (!options.allowed) {
    return (
      <Alert color="gray">
        {(options.reason && texts.blocked[options.reason]) ?? texts.errors.unknown}
      </Alert>
    );
  }
  return (
    <Stack gap="sm">
      {options.clientBookingReason && (
        <Text size="sm" c="dimmed">
          {reservations.clientWindow[options.clientBookingReason]}
        </Text>
      )}
      {options.waitlistStatus && <Text size="sm">{texts.waitlist[options.waitlistStatus]}</Text>}
      {options.eligiblePasses.length > 0 && (
        <Select
          label={texts.pass}
          placeholder={texts.passPlaceholder}
          required
          data={options.eligiblePasses.map((pass) => ({
            value: pass.id,
            label: texts.passLabel(
              pass.productName,
              pass.entriesRemaining,
              pass.validUntil ? formatDate(pass.validUntil) : null,
            ),
          }))}
          value={passId}
          onChange={onPassChange}
          disabled={disabled}
          error={submitted && passId === null ? texts.required : undefined}
        />
      )}
      {options.issues.length > 0 && (
        <Alert color="orange" title={texts.issuesTitle}>
          <Stack gap="xs">
            <List size="sm" spacing={4}>
              {options.issues.map((issue) => (
                <List.Item key={issue}>{reservations.issues[issue]}</List.Item>
              ))}
            </List>
            <Checkbox
              label={texts.confirmIssues}
              checked={confirmed}
              onChange={(event) => onConfirmedChange(event.currentTarget.checked)}
              disabled={disabled}
              error={submitted && !confirmed ? texts.required : undefined}
            />
          </Stack>
        </Alert>
      )}
    </Stack>
  );
}
