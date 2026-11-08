import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import type { AttendanceRecord, EventAttendance } from '@/features/schedule/attendanceApi';
import { EventAttendanceSection } from '@/features/schedule/EventAttendanceSection';
import type { ScheduleEvent } from '@/features/schedule/scheduleApi';
import { renderWithProviders } from './renderWithProviders';

const event: ScheduleEvent = {
  id: 'pole-class',
  kind: 'CLASS',
  status: 'COMPLETED',
  visibility: 'PUBLIC',
  title: 'Pole dance',
  serviceTypeId: 'pole',
  serviceTypeName: 'Pole dance - początkujący',
  seriesId: null,
  isSeriesException: false,
  trainingGroupId: null,
  startsAt: '2026-11-03T17:00:00Z',
  endsAt: '2026-11-03T18:00:00Z',
  capacity: 10,
  staff: [],
  resources: [],
  cancelReason: null,
  blockReason: null,
  guestName: null,
  version: 1,
};

const ola: AttendanceRecord = {
  reservationId: 'reservation-ola',
  customerId: 'ola',
  kind: 'ADULT',
  firstName: 'Ola',
  lastName: 'Nowak',
  displayName: 'Ola Nowak',
  bookedAt: '2026-10-30T10:00:00Z',
  attendance: 'PRESENT',
  attendanceMarkedAt: '2026-11-03T17:10:00Z',
  attendanceMarkedBy: 'Anna Kowalska',
  version: 1,
  history: [
    {
      changedAt: '2026-11-03T17:10:00Z',
      kind: 'MARKED',
      previousAttendance: 'UNMARKED',
      newAttendance: 'PRESENT',
      changedBy: 'Anna Kowalska',
      reason: null,
    },
  ],
};

const zosia: AttendanceRecord = {
  ...ola,
  reservationId: 'reservation-zosia',
  customerId: 'zosia',
  kind: 'CHILD',
  firstName: 'Zosia',
  lastName: 'Żak',
  displayName: 'Zosia Żak',
  attendance: 'UNMARKED',
  attendanceMarkedAt: null,
  attendanceMarkedBy: null,
  version: 0,
  history: [],
};

const sheet: EventAttendance = {
  eventId: event.id,
  eventKind: 'CLASS',
  eventStatus: 'COMPLETED',
  startsAt: event.startsAt,
  endsAt: event.endsAt,
  correctable: true,
  trainerMarkingClosesAt: '2026-11-04T06:00:00Z',
  trainerMarkingOpen: false,
  participants: [ola, zosia],
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

const sheetUrl = '/api/v1/schedule-events/pole-class/attendance';
const correctUrl = (reservationId: string) =>
  `/api/v1/schedule-events/pole-class/reservations/${reservationId}/correct-attendance`;

/** Serves the sheet; a correction answers with `answer` and, when it succeeds, updates the sheet. */
function stubApi(initial: EventAttendance, answer?: (body: unknown) => Response) {
  let stored = initial;
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    if (method === 'GET' && url === sheetUrl) return json(stored);
    if (method === 'POST' && url.endsWith('/correct-attendance')) {
      const body = JSON.parse(String(init!.body)) as { attendance: 'PRESENT' | 'ABSENT' };
      const response = answer?.(body);
      if (response) return response;
      stored = {
        ...stored,
        participants: stored.participants.map((participant) =>
          url === correctUrl(participant.reservationId)
            ? {
                ...participant,
                attendance: body.attendance,
                attendanceMarkedBy: 'Beata Wrona',
                version: participant.version + 1,
              }
            : participant,
        ),
      };
      return json(
        stored.participants.find((participant) => url === correctUrl(participant.reservationId)),
      );
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

test('lists the participants with their attendance, who recorded it and its history', async () => {
  stubApi(sheet);
  renderWithProviders(<EventAttendanceSection event={event} timeZone="Europe/Warsaw" />);

  const olaRow = await screen.findByLabelText('Ola Nowak');
  expect(screen.getByText('Obecni: 1 · Nieobecni: 0 · Bez oznaczenia: 1')).toBeInTheDocument();
  expect(
    screen.getByText(
      'Czas prowadzących na oznaczenie obecności minął 04.11.2026 07:00. Zmiany wprowadza administrator.',
    ),
  ).toBeInTheDocument();
  expect(within(olaRow).getByText('Obecność')).toBeInTheDocument();
  expect(within(olaRow).getByText('Anna Kowalska, 03.11.2026 18:10')).toBeInTheDocument();
  expect(within(olaRow).getByText('Historia zmian (1)')).toBeInTheDocument();
  expect(
    within(olaRow).getByText(/Prowadzący \(Anna Kowalska\): Bez oznaczenia → Obecność/),
  ).toBeInTheDocument();
  const zosiaRow = screen.getByLabelText('Zosia Żak');
  expect(within(zosiaRow).getByText('Dziecko')).toBeInTheDocument();
  expect(within(zosiaRow).getByText('Bez oznaczenia')).toBeInTheDocument();
  expect(within(zosiaRow).queryByText(/Historia zmian/)).not.toBeInTheDocument();
});

test('corrects attendance with an optional reason bound to the shown version', async () => {
  const fetchMock = stubApi(sheet);
  renderWithProviders(<EventAttendanceSection event={event} timeZone="Europe/Warsaw" />);

  await userEvent.click(await screen.findByRole('button', { name: 'Koryguj obecność: Ola Nowak' }));
  const dialog = await screen.findByRole('dialog', { name: 'Korekta obecności' });
  expect(within(dialog).getByText('Obecnie: Obecność')).toBeInTheDocument();
  const save = within(dialog).getByRole('button', { name: 'Zapisz korektę' });
  expect(save).toBeDisabled();
  await userEvent.click(within(dialog).getByRole('radio', { name: 'Obecność' }));
  expect(within(dialog).getByText('Wybierz inną wartość niż obecna.')).toBeInTheDocument();
  expect(save).toBeDisabled();
  await userEvent.click(within(dialog).getByRole('radio', { name: 'Nieobecność' }));
  await userEvent.type(within(dialog).getByLabelText('Powód (opcjonalnie)'), '  Nie przyszła  ');
  await userEvent.click(save);

  await vi.waitFor(() =>
    expect(screen.queryByRole('dialog', { name: 'Korekta obecności' })).not.toBeInTheDocument(),
  );
  const [[, init]] = requests(fetchMock, 'POST', correctUrl('reservation-ola'));
  expect(JSON.parse(String(init!.body))).toEqual({
    attendance: 'ABSENT',
    reason: 'Nie przyszła',
    version: 1,
  });
  expect(
    await within(screen.getByLabelText('Ola Nowak')).findByText('Nieobecność'),
  ).toBeInTheDocument();
  expect(screen.getByText('Obecni: 0 · Nieobecni: 1 · Bez oznaczenia: 1')).toBeInTheDocument();
});

test('a conflicting correction reloads the attendance and explains it', async () => {
  const fetchMock = stubApi(sheet, () =>
    json({ status: 409, code: 'VERSION_CONFLICT', detail: 'Changed' }, 409),
  );
  renderWithProviders(<EventAttendanceSection event={event} timeZone="Europe/Warsaw" />);

  await userEvent.click(await screen.findByRole('button', { name: 'Koryguj obecność: Zosia Żak' }));
  const dialog = await screen.findByRole('dialog', { name: 'Korekta obecności' });
  await userEvent.click(within(dialog).getByRole('radio', { name: 'Obecność' }));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz korektę' }));

  expect(
    await within(dialog).findByText(
      'Obecność została w międzyczasie zmieniona. Lista została odświeżona — sprawdź stan i zapisz ponownie.',
    ),
  ).toBeInTheDocument();
  await vi.waitFor(() => expect(requests(fetchMock, 'GET', sheetUrl)).toHaveLength(2));
});

test('attendance of an event that has not started cannot be corrected yet', async () => {
  stubApi({
    ...sheet,
    eventStatus: 'SCHEDULED',
    correctable: false,
    participants: [zosia],
  });
  renderWithProviders(
    <EventAttendanceSection event={{ ...event, status: 'SCHEDULED' }} timeZone="Europe/Warsaw" />,
  );

  expect(
    await screen.findByText('Obecność można korygować po rozpoczęciu wydarzenia.'),
  ).toBeInTheDocument();
  expect(screen.getByText('Zosia Żak')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Koryguj/ })).not.toBeInTheDocument();
  expect(screen.queryByText(/Obecni:/)).not.toBeInTheDocument();
});
