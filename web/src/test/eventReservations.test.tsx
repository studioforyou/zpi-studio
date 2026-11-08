import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { EventReservationsSection } from '@/features/schedule/EventReservationsSection';
import type {
  BookingOptions,
  EventReservation,
  EventReservations,
} from '@/features/schedule/reservationsApi';
import type { ScheduleEvent } from '@/features/schedule/scheduleApi';
import { renderWithProviders } from './renderWithProviders';

const event: ScheduleEvent = {
  id: 'pole-class',
  kind: 'CLASS',
  status: 'SCHEDULED',
  visibility: 'PUBLIC',
  title: 'Pole dance',
  serviceTypeId: 'pole',
  serviceTypeName: 'Pole dance - początkujący',
  seriesId: null,
  isSeriesException: false,
  originalStartsAt: null,
  trainingGroupId: null,
  startsAt: '2030-11-03T17:00:00Z',
  endsAt: '2030-11-03T18:00:00Z',
  capacity: 2,
  staff: [],
  resources: [],
  cancelReason: null,
  blockReason: null,
  guestName: null,
  version: 1,
};

const ola: EventReservation = {
  reservationId: 'reservation-ola',
  customerId: 'ola',
  kind: 'ADULT',
  firstName: 'Ola',
  lastName: 'Nowak',
  displayName: 'Ola Nowak',
  status: 'ACTIVE',
  bookedAt: '2030-10-30T10:00:00Z',
  source: 'SELF',
  bookedBy: null,
  bookingReason: null,
  overriddenIssues: [],
  customerPassId: 'pass-ola',
  passName: 'Karnet 8 wejść',
  cancellation: {
    allowed: true,
    reason: null,
    late: true,
    suggestedEntryReturned: false,
    participant: { allowed: false, reason: 'FREE_CANCELLATION_EXPIRED', entryReturned: null },
  },
  cancelledAt: null,
  entryReturned: null,
  version: 0,
};

const zosia: EventReservation = {
  ...ola,
  reservationId: 'reservation-zosia',
  customerId: 'zosia',
  kind: 'CHILD',
  firstName: 'Zosia',
  lastName: 'Żak',
  displayName: 'Zosia Żak',
  source: 'STAFF',
  bookedBy: 'Beata Wrona',
  bookingReason: 'Ustalone telefonicznie',
  overriddenIssues: ['CLASS_FULL', 'NO_ELIGIBLE_PASS'],
  customerPassId: null,
  passName: null,
  cancellation: {
    allowed: true,
    reason: null,
    late: false,
    suggestedEntryReturned: null,
    participant: { allowed: true, reason: null, entryReturned: null },
  },
  version: 2,
};

const roster: EventReservations = {
  eventId: event.id,
  eventStatus: 'SCHEDULED',
  startsAt: event.startsAt,
  capacity: 2,
  activeReservations: 3,
  heldOffers: 0,
  availablePlaces: 0,
  waitingCount: 1,
  bookable: true,
  bookableReason: null,
  clientBookingReason: 'BOOKING_CLOSED',
  participants: [ola, zosia],
};

const options: BookingOptions = {
  eventId: event.id,
  customerId: 'kasia',
  allowed: true,
  reason: null,
  issues: ['CLASS_FULL', 'AGE_RESTRICTION'],
  clientBookingReason: 'BOOKING_CLOSED',
  reservationId: null,
  waitlistStatus: 'WAITING',
  eligiblePasses: [
    {
      id: 'pass-1',
      productName: 'Karnet 4 wejścia',
      entriesRemaining: 3,
      validUntil: '2030-12-01',
    },
    { id: 'pass-2', productName: 'Open', entriesRemaining: null, validUntil: null },
  ],
};

const kasia = {
  id: 'kasia',
  kind: 'ADULT',
  firstName: 'Katarzyna',
  lastName: 'Zielińska',
  displayName: 'Katarzyna Zielińska',
  birthDate: null,
  email: 'katarzyna@example.com',
  emailOfGuardian: false,
};

const rosterUrl = '/api/v1/schedule-events/pole-class/reservations';
const optionsUrl = '/api/v1/schedule-events/pole-class/booking-options?customerId=kasia';
const cancelUrl = (reservationId: string) => `${rosterUrl}/${reservationId}/cancel`;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

type Answers = {
  options?: BookingOptions;
  book?: (body: unknown) => Response;
  cancel?: (body: unknown) => Response;
};

function stubApi(initial: EventReservations, answers: Answers = {}) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    if (method === 'GET' && url === rosterUrl) return json(initial);
    if (method === 'GET' && url.startsWith('/api/v1/customers?')) return json({ items: [kasia] });
    if (method === 'GET' && url === optionsUrl) return json(answers.options ?? options);
    if (method === 'POST' && url === rosterUrl) {
      return answers.book?.(body) ?? json({ ...ola, reservationId: 'new' }, 201);
    }
    if (method === 'POST' && url.endsWith('/cancel')) {
      return answers.cancel?.(body) ?? json({ ...ola, status: 'CANCELLED' });
    }
    throw new Error(`Unexpected request: ${method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function requests(fetchMock: ReturnType<typeof stubApi>, method: string, url: string) {
  return fetchMock.mock.calls.filter(
    ([callUrl, init]) => callUrl === url && (init?.method ?? 'GET') === method,
  );
}

function sentBody(fetchMock: ReturnType<typeof stubApi>, method: string, url: string): unknown {
  const [[, init]] = requests(fetchMock, method, url);
  return JSON.parse(String(init!.body));
}

async function openBooking() {
  await userEvent.click(await screen.findByRole('button', { name: 'Zapisz uczestnika' }));
  const dialog = await screen.findByRole('dialog', { name: 'Zapisz uczestnika na zajęcia' });
  await userEvent.click(within(dialog).getByRole('combobox', { name: /^Uczestnik/ }));
  const option = await screen.findByRole('option', { name: /Katarzyna Zielińska/, hidden: true });
  expect(within(option).getByText('katarzyna@example.com')).toBeInTheDocument();
  await userEvent.click(option);
  return dialog;
}

test('lists the places with their passes, who booked them and why', async () => {
  stubApi(roster);
  renderWithProviders(<EventReservationsSection event={event} />);

  const olaRow = await screen.findByLabelText('Ola Nowak');
  expect(screen.getByText('Zapisanych: 3 z 2 · ponad limit: 1')).toBeInTheDocument();
  expect(screen.getByText('Na liście rezerwowej: 1')).toBeInTheDocument();
  expect(
    screen.getByText(
      'Zapisy dla klientów są już zamknięte. Jako administrator możesz zapisać uczestnika do startu zajęć.',
    ),
  ).toBeInTheDocument();
  expect(within(olaRow).getByText('Karnet 8 wejść · zapis samodzielny')).toBeInTheDocument();
  const zosiaRow = screen.getByLabelText('Zosia Żak');
  expect(within(zosiaRow).getByText('Dziecko')).toBeInTheDocument();
  expect(within(zosiaRow).getByText('bez karnetu · zapisał(a): Beata Wrona')).toBeInTheDocument();
  expect(
    within(zosiaRow).getByText('Zapisano mimo: braku miejsc, braku karnetu'),
  ).toBeInTheDocument();
  expect(within(zosiaRow).getByText('Powód: Ustalone telefonicznie')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Wypisz: Ola Nowak' })).toBeInTheDocument();
});

test('books a participant on a chosen pass once the issues are confirmed', async () => {
  const fetchMock = stubApi(roster);
  renderWithProviders(<EventReservationsSection event={event} />);

  const dialog = await openBooking();
  expect(
    await within(dialog).findByText('Zajęcia są pełne. Zapis przekroczy limit miejsc.'),
  ).toBeInTheDocument();
  expect(
    within(dialog).getByText(
      'Wiek uczestnika (albo brak daty urodzenia) nie mieści się w limicie wieku usługi.',
    ),
  ).toBeInTheDocument();
  expect(
    within(dialog).getByText('Uczestnik czeka na liście rezerwowej. Zapis usunie go z kolejki.'),
  ).toBeInTheDocument();

  const save = within(dialog).getByRole('button', { name: 'Zapisz' });
  await userEvent.click(save);
  expect(requests(fetchMock, 'POST', rosterUrl)).toHaveLength(0);
  expect(within(dialog).getAllByText('To pole jest wymagane.')).toHaveLength(2);

  await userEvent.click(within(dialog).getByRole('combobox', { name: /^Karnet/ }));
  await userEvent.click(
    await screen.findByRole('option', {
      name: 'Karnet 4 wejścia · zostało: 3 wejścia · ważny do 01.12.2030',
      hidden: true,
    }),
  );
  await userEvent.click(
    within(dialog).getByRole('checkbox', { name: 'Rozumiem i zapisuję uczestnika mimo to' }),
  );
  await userEvent.type(within(dialog).getByLabelText('Powód (opcjonalnie)'), ' Telefon ');
  await userEvent.click(save);

  await vi.waitFor(() =>
    expect(
      screen.queryByRole('dialog', { name: 'Zapisz uczestnika na zajęcia' }),
    ).not.toBeInTheDocument(),
  );
  expect(sentBody(fetchMock, 'POST', rosterUrl)).toEqual({
    customerId: 'kasia',
    customerPassId: 'pass-1',
    acceptedIssues: ['CLASS_FULL', 'AGE_RESTRICTION'],
    reason: 'Telefon',
  });
  await vi.waitFor(() => expect(requests(fetchMock, 'GET', rosterUrl)).toHaveLength(2));
});

test('books a participant without a pass and reloads changed issues', async () => {
  const fetchMock = stubApi(roster, {
    options: { ...options, issues: ['NO_ELIGIBLE_PASS'], eligiblePasses: [], waitlistStatus: null },
    book: () =>
      json(
        {
          status: 409,
          code: 'BOOKING_OVERRIDE_REQUIRED',
          detail: 'Changed',
          issues: ['CLASS_FULL'],
        },
        409,
      ),
  });
  renderWithProviders(<EventReservationsSection event={event} />);

  const dialog = await openBooking();
  expect(
    await within(dialog).findByText(
      'Uczestnik nie ma karnetu, który obejmuje te zajęcia. Zostanie zapisany bez pobrania wejścia.',
    ),
  ).toBeInTheDocument();
  expect(within(dialog).queryByRole('combobox', { name: /^Karnet/ })).not.toBeInTheDocument();
  await userEvent.click(
    within(dialog).getByRole('checkbox', { name: 'Rozumiem i zapisuję uczestnika mimo to' }),
  );
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  expect(
    await within(dialog).findByText(
      'Warunki zapisu zmieniły się w międzyczasie. Sprawdź aktualne ostrzeżenia i potwierdź je ponownie.',
    ),
  ).toBeInTheDocument();
  expect(sentBody(fetchMock, 'POST', rosterUrl)).toEqual({
    customerId: 'kasia',
    customerPassId: null,
    acceptedIssues: ['NO_ELIGIBLE_PASS'],
    reason: null,
  });
  await vi.waitFor(() => expect(requests(fetchMock, 'GET', optionsUrl)).toHaveLength(2));
  expect(
    within(dialog).getByRole('checkbox', { name: 'Rozumiem i zapisuję uczestnika mimo to' }),
  ).not.toBeChecked();
});

test('a participant who is already booked cannot be booked again', async () => {
  stubApi(roster, {
    options: {
      ...options,
      allowed: false,
      reason: 'ALREADY_RESERVED',
      issues: [],
      reservationId: 'r',
    },
  });
  renderWithProviders(<EventReservationsSection event={event} />);

  const dialog = await openBooking();
  expect(
    await within(dialog).findByText('Ten uczestnik jest już zapisany na te zajęcia.'),
  ).toBeInTheDocument();
  expect(within(dialog).getByRole('button', { name: 'Zapisz' })).toBeDisabled();
});

test('cancels a place returning the entry although the terms would forfeit it', async () => {
  const fetchMock = stubApi(roster);
  renderWithProviders(<EventReservationsSection event={event} />);

  await userEvent.click(await screen.findByRole('button', { name: 'Wypisz: Ola Nowak' }));
  const dialog = await screen.findByRole('dialog', { name: 'Wypisz uczestnika z zajęć' });
  expect(within(dialog).getByText('Karnet: Karnet 8 wejść')).toBeInTheDocument();
  expect(
    within(dialog).getByText('Według zasad odwołania to późne wypisanie, więc wejście przepada.'),
  ).toBeInTheDocument();
  expect(
    within(dialog).getByText('Uczestnik nie mógłby się już wypisać sam w aplikacji.'),
  ).toBeInTheDocument();
  expect(within(dialog).getByRole('radio', { name: 'Wejście przepada' })).toBeChecked();
  await userEvent.click(within(dialog).getByRole('radio', { name: 'Zwróć wejście na karnet' }));
  await userEvent.type(within(dialog).getByLabelText('Powód (opcjonalnie)'), 'Choroba');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Wypisz' }));

  await vi.waitFor(() =>
    expect(
      screen.queryByRole('dialog', { name: 'Wypisz uczestnika z zajęć' }),
    ).not.toBeInTheDocument(),
  );
  expect(sentBody(fetchMock, 'POST', cancelUrl('reservation-ola'))).toEqual({
    expectedVersion: 0,
    returnEntry: true,
    reason: 'Choroba',
  });
});

test('cancels a place without a pass and explains a conflict', async () => {
  const fetchMock = stubApi(roster, {
    cancel: () => json({ status: 409, code: 'VERSION_CONFLICT', detail: 'Changed' }, 409),
  });
  renderWithProviders(<EventReservationsSection event={event} />);

  await userEvent.click(await screen.findByRole('button', { name: 'Wypisz: Zosia Żak' }));
  const dialog = await screen.findByRole('dialog', { name: 'Wypisz uczestnika z zajęć' });
  expect(
    within(dialog).getByText('Uczestnik jest zapisany bez karnetu, więc nie ma wejścia do zwrotu.'),
  ).toBeInTheDocument();
  expect(within(dialog).queryByRole('radio')).not.toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Wypisz' }));

  expect(
    await within(dialog).findByText(
      'Rezerwacja zmieniła się w międzyczasie. Lista została odświeżona. Sprawdź stan i spróbuj ponownie.',
    ),
  ).toBeInTheDocument();
  expect(sentBody(fetchMock, 'POST', cancelUrl('reservation-zosia'))).toEqual({
    expectedVersion: 2,
    returnEntry: null,
    reason: null,
  });
  await vi.waitFor(() => expect(requests(fetchMock, 'GET', rosterUrl)).toHaveLength(2));
});

test('a class that cannot be booked any more offers no booking or cancellation', async () => {
  stubApi({
    ...roster,
    bookable: false,
    bookableReason: 'EVENT_NOT_BOOKABLE',
    clientBookingReason: null,
    participants: [
      {
        ...ola,
        cancellation: { ...ola.cancellation, allowed: false, reason: 'EVENT_ALREADY_STARTED' },
      },
    ],
  });
  renderWithProviders(<EventReservationsSection event={event} />);

  expect(
    await screen.findByText(
      'Zapisywać i wypisywać można tylko na zaplanowane zajęcia, które jeszcze się nie zaczęły.',
    ),
  ).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Zapisz uczestnika' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Wypisz/ })).not.toBeInTheDocument();
});
