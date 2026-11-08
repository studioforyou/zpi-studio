import { apiFetch } from '@/shared/api/apiClient';
import type { EventStatus } from './scheduleApi';

/** What an administrator may override when booking a place, once they confirm it. */
export type BookingIssue =
  | 'CLASS_FULL'
  | 'SERVICE_TYPE_UNAVAILABLE'
  | 'AGE_RESTRICTION'
  | 'REQUIRED_DOCUMENTS_MISSING'
  | 'NO_ELIGIBLE_PASS';

/** Who booked the place. */
export type ReservationSource = 'SELF' | 'GUARDIAN' | 'STAFF' | 'WAITLIST' | 'GROUP_AUTO';

/** What the participant could do in the app now. */
export type ParticipantCancellation = {
  allowed: boolean;
  reason: string | null;
  /** `null` when cancelling is unavailable or the place has no pass. */
  entryReturned: boolean | null;
};

export type StaffCancellation = {
  /** The place is active and the class is scheduled and has not started. */
  allowed: boolean;
  reason: string | null;
  /** The free cancellation deadline stored at booking has passed. */
  late: boolean;
  /** What the stored terms give; the administrator decides. `null` without a pass. */
  suggestedEntryReturned: boolean | null;
  participant: ParticipantCancellation;
};

export type EventReservation = {
  reservationId: string;
  customerId: string;
  kind: 'ADULT' | 'CHILD';
  firstName: string;
  lastName: string;
  displayName: string;
  status: 'ACTIVE' | 'CANCELLED';
  bookedAt: string;
  source: ReservationSource;
  /** The administrator who booked a `STAFF` place, or `null` when unknown. */
  bookedBy: string | null;
  bookingReason: string | null;
  overriddenIssues: BookingIssue[];
  /** `null`: booked without a pass entry. */
  customerPassId: string | null;
  passName: string | null;
  cancellation: StaffCancellation;
  cancelledAt: string | null;
  entryReturned: boolean | null;
  /** Sent back with a cancellation; a stale one is rejected with `VERSION_CONFLICT`. */
  version: number;
};

export type EventReservations = {
  eventId: string;
  eventStatus: EventStatus;
  startsAt: string;
  /** `null` = no limit. */
  capacity: number | null;
  activeReservations: number;
  heldOffers: number;
  /** `null` = no limit. */
  availablePlaces: number | null;
  waitingCount: number;
  /** An administrator can book and cancel places now. */
  bookable: boolean;
  bookableReason: string | null;
  /** `BOOKING_NOT_OPEN` or `BOOKING_CLOSED` when participants cannot book themselves now. */
  clientBookingReason: 'BOOKING_NOT_OPEN' | 'BOOKING_CLOSED' | null;
  /** Sorted by last and first name. */
  participants: EventReservation[];
};

export type EligiblePass = {
  id: string;
  productName: string;
  /** `null` = unlimited. */
  entriesRemaining: number | null;
  /** `yyyy-MM-dd`, or `null`. */
  validUntil: string | null;
};

export type BookingOptions = {
  eventId: string;
  customerId: string;
  /** A place can be booked, possibly after confirming the issues. */
  allowed: boolean;
  /** `EVENT_NOT_BOOKABLE`, `ALREADY_RESERVED` or `CUSTOMER_NOT_ACTIVE`. */
  reason: string | null;
  /** To be confirmed before booking, in the order checked. */
  issues: BookingIssue[];
  clientBookingReason: 'BOOKING_NOT_OPEN' | 'BOOKING_CLOSED' | null;
  reservationId: string | null;
  waitlistStatus: 'WAITING' | 'OFFERED' | null;
  eligiblePasses: EligiblePass[];
};

export type StaffBooking = {
  customerId: string;
  /** Required when the participant has an eligible pass. */
  customerPassId: string | null;
  acceptedIssues: BookingIssue[];
  reason: string | null;
};

export type StaffCancellationInput = {
  expectedVersion: number;
  /** Required for a place with a pass. */
  returnEntry: boolean | null;
  reason: string | null;
};

/** An active participant found by name, phone or e-mail address. */
export type Participant = {
  id: string;
  kind: 'ADULT' | 'CHILD';
  displayName: string;
  /** The participant's address or, for a child, its guardian's; `null` when there is none. */
  email: string | null;
  emailOfGuardian: boolean;
};

export const reservationKeys = {
  event: (eventId: string) => ['schedule', 'reservations', eventId] as const,
  options: (eventId: string, customerId: string) =>
    ['schedule', 'reservations', eventId, 'options', customerId] as const,
  participants: (query: string) => ['schedule', 'participants', query] as const,
};

const base = (eventId: string) => `/api/v1/schedule-events/${eventId}`;

export function getEventReservations(eventId: string): Promise<EventReservations> {
  return apiFetch<EventReservations>(`${base(eventId)}/reservations`);
}

export function getBookingOptions(eventId: string, customerId: string): Promise<BookingOptions> {
  const params = new URLSearchParams({ customerId });
  return apiFetch<BookingOptions>(`${base(eventId)}/booking-options?${params.toString()}`);
}

export function bookReservation(eventId: string, booking: StaffBooking): Promise<EventReservation> {
  return apiFetch<EventReservation>(`${base(eventId)}/reservations`, {
    method: 'POST',
    body: booking,
  });
}

export function cancelReservation(
  eventId: string,
  reservationId: string,
  input: StaffCancellationInput,
): Promise<EventReservation> {
  return apiFetch<EventReservation>(`${base(eventId)}/reservations/${reservationId}/cancel`, {
    method: 'POST',
    body: input,
  });
}

export async function searchParticipants(query: string): Promise<Participant[]> {
  const params = new URLSearchParams({ size: '20' });
  if (query.trim() !== '') {
    params.set('query', query.trim());
  }
  const page = await apiFetch<{ items: Participant[] }>(`/api/v1/customers?${params.toString()}`);
  return page.items;
}
