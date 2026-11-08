import { useState } from 'react';
import { Alert, Badge, Button, Group, Paper, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { BookParticipantModal } from './BookParticipantModal';
import { CancelReservationModal } from './CancelReservationModal';
import {
  getEventReservations,
  reservationKeys,
  type EventReservation,
  type EventReservations,
} from './reservationsApi';
import type { ScheduleEvent } from './scheduleApi';

type Props = {
  event: ScheduleEvent;
};

/**
 * The places booked in an upcoming group class. Administrators book participants and cancel their
 * places on their behalf, e.g. after a phone call or at the desk, until the class starts.
 */
export function EventReservationsSection({ event }: Props) {
  const texts = pl.schedule.reservations;
  const reservations = useQuery({
    queryKey: reservationKeys.event(event.id),
    queryFn: () => getEventReservations(event.id),
  });
  const [booking, setBooking] = useState(false);
  const [cancelling, setCancelling] = useState<string | null>(null);
  const cancelled = reservations.data?.participants.find(
    (participant) => participant.reservationId === cancelling,
  );

  return (
    <Stack gap="xs">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {texts.title}
      </Text>
      {reservations.isPending && (
        <Text size="sm" c="dimmed">
          {texts.loading}
        </Text>
      )}
      {reservations.isError && (
        <Alert color="red">
          <Stack gap="xs" align="flex-start">
            <Text size="sm">{texts.loadError}</Text>
            <Button size="xs" variant="light" onClick={() => reservations.refetch()}>
              {texts.retry}
            </Button>
          </Stack>
        </Alert>
      )}
      {reservations.data && (
        <Roster
          roster={reservations.data}
          onBook={() => setBooking(true)}
          onCancel={(reservation) => setCancelling(reservation.reservationId)}
        />
      )}
      {booking && <BookParticipantModal eventId={event.id} onClose={() => setBooking(false)} />}
      {cancelled && (
        <CancelReservationModal
          eventId={event.id}
          reservation={cancelled}
          onClose={() => setCancelling(null)}
        />
      )}
    </Stack>
  );
}

function Roster({
  roster,
  onBook,
  onCancel,
}: {
  roster: EventReservations;
  onBook: () => void;
  onCancel: (reservation: EventReservation) => void;
}) {
  const texts = pl.schedule.reservations;
  const over =
    roster.capacity === null ? 0 : Math.max(0, roster.activeReservations - roster.capacity);
  const counts = [
    texts.places(roster.activeReservations, roster.capacity),
    over > 0 ? texts.overCapacity(over) : null,
    roster.heldOffers > 0 ? texts.heldOffers(roster.heldOffers) : null,
  ].filter(Boolean);

  return (
    <Stack gap="xs">
      <Text size="sm" fw={600}>
        {counts.join(' · ')}
      </Text>
      {roster.waitingCount > 0 && <Text size="sm">{texts.waiting(roster.waitingCount)}</Text>}
      {roster.clientBookingReason && (
        <Text size="sm" c="dimmed">
          {texts.clientWindow[roster.clientBookingReason]}
        </Text>
      )}
      {roster.bookable ? (
        <Group>
          <Button size="xs" onClick={onBook}>
            {texts.book}
          </Button>
        </Group>
      ) : (
        <Text size="sm" c="dimmed">
          {texts.notBookable}
        </Text>
      )}
      {roster.participants.length === 0 ? (
        <Text size="sm">{texts.empty}</Text>
      ) : (
        roster.participants.map((participant) => (
          <Participant
            key={participant.reservationId}
            participant={participant}
            onCancel={() => onCancel(participant)}
          />
        ))
      )}
    </Stack>
  );
}

function Participant({
  participant,
  onCancel,
}: {
  participant: EventReservation;
  onCancel: () => void;
}) {
  const texts = pl.schedule.reservations;
  const source =
    participant.source === 'STAFF'
      ? texts.bookedBy(participant.bookedBy)
      : texts.sources[participant.source];
  return (
    <Paper withBorder radius="sm" p="xs" aria-label={participant.displayName}>
      <Stack gap={4}>
        <Group justify="space-between" wrap="nowrap" gap="xs">
          <Group gap="xs" wrap="wrap">
            <Text size="sm" fw={600}>
              {participant.displayName}
            </Text>
            {participant.kind === 'CHILD' && (
              <Badge size="xs" variant="outline" color="gray">
                {texts.child}
              </Badge>
            )}
          </Group>
          {participant.cancellation.allowed && (
            <Button
              size="compact-xs"
              variant="subtle"
              color="red"
              onClick={onCancel}
              aria-label={texts.cancelFor(participant.displayName)}
            >
              {texts.cancel}
            </Button>
          )}
        </Group>
        <Text size="xs" c="dimmed">
          {[participant.customerPassId ? participant.passName : texts.noPass, source]
            .filter(Boolean)
            .join(' · ')}
        </Text>
        {participant.overriddenIssues.length > 0 && (
          <Text size="xs" c="orange.8">
            {texts.overridden(
              participant.overriddenIssues.map((issue) => texts.issueShort[issue]).join(', '),
            )}
          </Text>
        )}
        {participant.bookingReason && (
          <Text size="xs" c="dimmed">
            {texts.reasonLabel(participant.bookingReason)}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}
