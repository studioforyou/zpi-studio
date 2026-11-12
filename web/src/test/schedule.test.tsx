import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import type { ScheduleEvent } from '@/features/schedule/scheduleApi';
import { SchedulePage } from '@/features/schedule/SchedulePage';
import { renderWithProviders } from './renderWithProviders';

const serviceTypes = [
  {
    id: 'pole',
    name: 'Pole dance - początkujący',
    category: 'GROUP_CLASS',
    level: 'BEGINNER',
    minAge: 16,
    maxAge: null,
    defaultDurationMinutes: 60,
    maxParticipants: 10,
    calendarColor: '#E91E63',
    isActive: true,
  },
  {
    id: 'pt',
    name: 'Trening personalny',
    category: 'PERSONAL_TRAINING',
    level: null,
    minAge: null,
    maxAge: null,
    defaultDurationMinutes: 60,
    maxParticipants: 1,
    calendarColor: '#FF9800',
    isActive: true,
  },
];

const staff = [
  {
    id: 'anna',
    firstName: 'Anna',
    lastName: 'Kowalska',
    displayName: 'Anna Kowalska',
    calendarColor: '#E91E63',
    isActive: true,
    serviceTypeIds: ['pole'],
  },
  {
    id: 'marek',
    firstName: 'Marek',
    lastName: 'Nowak',
    displayName: 'Marek Nowak',
    calendarColor: '#2196F3',
    isActive: true,
    serviceTypeIds: ['pt'],
  },
];

const resources = [
  {
    id: 'pole-room',
    name: 'Sala pole dance',
    kind: 'ROOM',
    parentResourceId: null,
    maxPeople: 10,
    description: null,
    isActive: true,
  },
  {
    id: 'gym',
    name: 'Sala treningowa',
    kind: 'ROOM',
    parentResourceId: null,
    maxPeople: 12,
    description: null,
    isActive: true,
  },
];

const roomRules: Record<string, unknown[]> = {
  'pole-room': [{ serviceTypeId: 'pole', serviceTypeName: 'Pole dance', isDefault: true }],
  gym: [{ serviceTypeId: 'pt', serviceTypeName: 'Trening personalny', isDefault: true }],
};

const baseEvent: ScheduleEvent = {
  id: 'pole-class',
  kind: 'CLASS',
  status: 'SCHEDULED',
  visibility: 'PUBLIC',
  title: 'Pole dance',
  serviceTypeId: 'pole',
  serviceTypeName: 'Pole dance - początkujący',
  seriesId: 'series-1',
  isSeriesException: false,
  seriesChange: null,
  trainingGroupId: null,
  // Tuesday 3 November 2026, 18:00–19:00 in Warsaw (CET).
  startsAt: '2026-11-03T17:00:00Z',
  endsAt: '2026-11-03T18:00:00Z',
  capacity: 10,
  staff: [{ staffId: 'anna', displayName: 'Anna Kowalska', role: 'LEAD' }],
  resources: [
    { resourceId: 'pole-room', name: 'Sala pole dance', kind: 'ROOM', blocksWholeRoom: true },
  ],
  cancelReason: null,
  blockReason: null,
  guestName: null,
  version: 0,
};

const events: ScheduleEvent[] = [
  baseEvent,
  {
    ...baseEvent,
    id: 'personal',
    kind: 'PERSONAL_TRAINING',
    visibility: 'PRIVATE',
    title: 'Trening personalny – Tomasz',
    serviceTypeId: 'pt',
    serviceTypeName: 'Trening personalny',
    seriesId: null,
    startsAt: '2026-11-04T06:00:00Z',
    endsAt: '2026-11-04T07:00:00Z',
    capacity: 1,
    staff: [{ staffId: 'marek', displayName: 'Marek Nowak', role: 'LEAD' }],
    resources: [
      { resourceId: 'gym', name: 'Sala treningowa', kind: 'ROOM', blocksWholeRoom: false },
    ],
  },
  {
    ...baseEvent,
    id: 'workshop',
    kind: 'WORKSHOP',
    title: 'Warsztaty stretchingu',
    seriesId: null,
    startsAt: '2026-11-07T11:00:00Z',
    endsAt: '2026-11-07T13:00:00Z',
    staff: [],
    resources: [],
  },
];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

type Route = (url: string, method: string, body: unknown) => Response | undefined;

/** Answers the schedule's reference data; extra routes answer the rest. */
function stubApi(...routes: Route[]) {
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    const method = init.method ?? 'GET';
    const body = init.body ? JSON.parse(init.body as string) : undefined;
    for (const route of routes) {
      const response = route(url, method, body);
      if (response) {
        return response;
      }
    }
    if (method === 'GET') {
      if (url === '/api/v1/studio') {
        return json({ name: 'studio4U', timeZone: 'Europe/Warsaw', currency: 'PLN' });
      }
      if (url.startsWith('/api/v1/schedule-events?')) {
        return json({ items: events });
      }
      if (url === '/api/v1/staff') {
        return json({ items: staff });
      }
      if (url === '/api/v1/service-types') {
        return json({ items: serviceTypes });
      }
      if (url === '/api/v1/resources') {
        return json({ items: resources });
      }
      if (url === '/api/v1/training-groups') {
        return json({ items: [] });
      }
      const attendance = /^\/api\/v1\/schedule-events\/([^/]+)\/attendance$/.exec(url);
      if (attendance) {
        return json({
          eventId: attendance[1],
          eventKind: 'CLASS',
          eventStatus: 'SCHEDULED',
          startsAt: '2026-11-03T17:00:00Z',
          endsAt: '2026-11-03T18:00:00Z',
          correctable: false,
          trainerMarkingClosesAt: '2026-11-04T06:00:00Z',
          trainerMarkingOpen: false,
          participants: [],
        });
      }
      const reservations = /^\/api\/v1\/schedule-events\/([^/]+)\/reservations$/.exec(url);
      if (reservations) {
        return json({
          eventId: reservations[1],
          eventStatus: 'SCHEDULED',
          startsAt: '2026-11-03T17:00:00Z',
          capacity: 10,
          activeReservations: 0,
          heldOffers: 0,
          availablePlaces: 10,
          waitingCount: 0,
          bookable: true,
          bookableReason: null,
          clientBookingReason: null,
          participants: [],
        });
      }
      const rules = /^\/api\/v1\/resources\/([^/]+)\/service-types$/.exec(url);
      if (rules) {
        return json({ items: roomRules[rules[1]] ?? [] });
      }
      if (url === '/api/v1/recurrence-series/series-1') {
        return json({
          id: 'series-1',
          serviceTypeId: 'pole',
          title: 'Pole dance',
          weekday: 2,
          localStartTime: '18:00',
          durationMinutes: 60,
          intervalWeeks: 1,
          startsOn: '2026-11-03',
          endsOn: '2027-01-26',
          capacity: 10,
          status: 'ACTIVE',
          defaultStaffId: 'anna',
          defaultResourceId: 'pole-room',
          occurrenceCount: 13,
        });
      }
    }
    throw new Error(`Unexpected request: ${method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function requestBody(fetchMock: ReturnType<typeof stubApi>, method: string, url: string): unknown {
  const call = fetchMock.mock.calls.find(
    ([callUrl, init]) => callUrl === url && init.method === method,
  );
  return call ? JSON.parse(call[1].body as string) : undefined;
}

/**
 * Picks an option of a Mantine Select or MultiSelect. Several pickers offer the same options, so
 * the option is looked up in the listbox the input controls; it counts as hidden in jsdom.
 */
async function choose(input: HTMLElement, option: string) {
  await userEvent.click(input);
  const listbox = document.getElementById(input.getAttribute('aria-controls') ?? '');
  expect(listbox).not.toBeNull();
  await userEvent.click(within(listbox!).getByRole('option', { name: option, hidden: true }));
}

beforeEach(() => {
  // Only Date is faked, so the dates "today" and the default form dates are fixed.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-01T09:00:00Z'));
});

afterEach(() => {
  vi.useRealTimers();
});

test('shows every event of the week and narrows them with filters', async () => {
  stubApi();
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);

  expect(
    await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: 'Trening personalny – Tomasz, 07:00–08:00' }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: 'Warsztaty stretchingu, 12:00–14:00' }),
  ).toBeInTheDocument();
  expect(screen.getByText('Wyświetlono 3 z 3 wydarzeń')).toBeInTheDocument();

  await choose(screen.getByRole('combobox', { name: 'Prowadzący' }), 'Marek Nowak');
  expect(screen.getByText('Wyświetlono 1 z 3 wydarzeń')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Pole dance, 18:00–19:00' })).not.toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Wyczyść filtry' }));
  await userEvent.click(screen.getByRole('button', { name: /Więcej filtrów/ }));
  // The panel's opening transition never finishes in jsdom, so its fields count as hidden.
  const noStaff = screen.getByRole('checkbox', { name: 'Bez prowadzącego', hidden: true });
  await userEvent.click(noStaff);
  expect(screen.getByText('Wyświetlono 1 z 3 wydarzeń')).toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: 'Warsztaty stretchingu, 12:00–14:00' }),
  ).toBeInTheDocument();

  await userEvent.click(noStaff);
  await userEvent.type(screen.getByLabelText('Szukaj'), 'sala pole');
  expect(screen.getByText('Wyświetlono 1 z 3 wydarzeń')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Pole dance, 18:00–19:00' })).toBeInTheDocument();
});

test('lists the month with who runs each event and where', async () => {
  const fetchMock = stubApi();
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' });

  await userEvent.click(screen.getByRole('radio', { name: 'Lista' }));

  const row = (await screen.findByText('Trening personalny – Tomasz')).closest('tr')!;
  expect(within(row).getByText('Marek Nowak')).toBeInTheDocument();
  expect(within(row).getByText('Sala treningowa')).toBeInTheDocument();
  const workshopRow = screen.getByText('Warsztaty stretchingu').closest('tr')!;
  expect(within(workshopRow).getByText('Brak prowadzącego')).toBeInTheDocument();
  expect(within(workshopRow).getByText('Brak sali')).toBeInTheDocument();
  // The list asks for the whole month in the studio's time zone.
  expect(
    fetchMock.mock.calls.some(
      ([url]) =>
        url ===
        '/api/v1/schedule-events?from=2026-10-31T23%3A00%3A00.000Z&to=2026-11-30T23%3A00%3A00.000Z',
    ),
  ).toBe(true);
});

test('creates a one-off event after checking its date', async () => {
  const fetchMock = stubApi(
    (url, method) =>
      url === '/api/v1/schedule-events/preview' && method === 'POST'
        ? json({
            occurrences: [
              {
                date: '2026-11-10',
                startsAt: '2026-11-10T16:30:00Z',
                endsAt: '2026-11-10T17:30:00Z',
                excluded: false,
                issues: [
                  {
                    type: 'STAFF_LEAVE_REQUESTED',
                    blocking: false,
                    staffId: 'anna',
                    resourceId: null,
                    eventId: null,
                    eventTitle: null,
                    startsAt: null,
                    endsAt: null,
                  },
                ],
              },
            ],
            warnings: [],
            conflictCount: 0,
          })
        : undefined,
    (url, method) =>
      url === '/api/v1/schedule-events' && method === 'POST' ? json(baseEvent, 201) : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' });

  await userEvent.click(screen.getByRole('button', { name: 'Dodaj wydarzenie' }));
  const dialog = await screen.findByRole('dialog');
  await choose(
    within(dialog).getByRole('combobox', { name: /Usługa/ }),
    'Pole dance - początkujący',
  );
  // The service's default room is chosen for it.
  expect(await within(dialog).findByText('Sala pole dance')).toBeInTheDocument();
  await choose(within(dialog).getByRole('combobox', { name: 'Prowadzący' }), 'Anna Kowalska');
  fireEvent.change(within(dialog).getByLabelText(/^Data/), { target: { value: '2026-11-10' } });
  fireEvent.change(within(dialog).getByLabelText(/Godzina rozpoczęcia/), {
    target: { value: '17:30' },
  });
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dalej: sprawdź terminy' }));

  expect(
    await within(dialog).findByText(/Anna Kowalska złożył\(a\) wniosek o urlop/),
  ).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  const expected = {
    kind: 'CLASS',
    serviceTypeId: 'pole',
    title: null,
    capacity: 10,
    visibility: 'PUBLIC',
    staff: [{ staffId: 'anna', role: 'LEAD' }],
    resources: [{ resourceId: 'pole-room', blocksWholeRoom: true }],
    startsAt: '2026-11-10T16:30:00.000Z',
    endsAt: '2026-11-10T17:30:00.000Z',
  };
  await vi.waitFor(() =>
    expect(requestBody(fetchMock, 'POST', '/api/v1/schedule-events')).toEqual(expected),
  );
  expect(requestBody(fetchMock, 'POST', '/api/v1/schedule-events/preview')).toEqual(expected);
});

test('uses the configured personal training capacity when selecting a service', async () => {
  stubApi((url, method) =>
    url === '/api/v1/service-types' && method === 'GET'
      ? json({
          items: serviceTypes.map((service) =>
            service.id === 'pt' ? { ...service, maxParticipants: 3 } : service,
          ),
        })
      : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' });
  await userEvent.click(screen.getByRole('button', { name: 'Dodaj wydarzenie' }));
  const dialog = await screen.findByRole('dialog');
  await choose(within(dialog).getByRole('combobox', { name: /Usługa/ }), 'Trening personalny');
  expect(within(dialog).getByLabelText('Limit miejsc')).toHaveValue('3');
});

test('saves a recurring event after leaving out its conflicting date', async () => {
  const occurrence = (date: string, startsAt: string, blocking: boolean) => ({
    date,
    startsAt,
    endsAt: startsAt.replace('T17:00', 'T18:00'),
    excluded: false,
    issues: blocking
      ? [
          {
            type: 'RESOURCE_BUSY',
            blocking: true,
            staffId: null,
            resourceId: 'pole-room',
            eventId: 'other',
            eventTitle: 'Warsztaty',
            startsAt: '2026-11-10T16:30:00Z',
            endsAt: '2026-11-10T18:30:00Z',
          },
        ]
      : [],
  });
  const fetchMock = stubApi(
    (url, method) =>
      url === '/api/v1/recurrence-series/preview' && method === 'POST'
        ? json({
            occurrences: [
              occurrence('2026-11-03', '2026-11-03T17:00:00Z', false),
              occurrence('2026-11-10', '2026-11-10T17:00:00Z', true),
              occurrence('2026-11-17', '2026-11-17T17:00:00Z', false),
            ],
            warnings: [],
            conflictCount: 1,
          })
        : undefined,
    (url, method) =>
      url === '/api/v1/recurrence-series' && method === 'POST'
        ? json({ items: [], occurrenceCount: 2 }, 201)
        : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' });

  await userEvent.click(screen.getByRole('button', { name: 'Dodaj wydarzenie' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('radio', { name: 'Cykliczne' }));
  await choose(
    within(dialog).getByRole('combobox', { name: /Usługa/ }),
    'Pole dance - początkujący',
  );
  await within(dialog).findByText('Sala pole dance');
  fireEvent.change(within(dialog).getByLabelText(/Do dnia/), { target: { value: '2026-11-17' } });
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dalej: sprawdź terminy' }));

  expect(
    await within(dialog).findByText('Sala pole dance jest zajęta: Warsztaty (17:30–19:30)', {
      exact: false,
    }),
  ).toBeInTheDocument();
  expect(within(dialog).getByRole('button', { name: 'Zapisz (3)' })).toBeDisabled();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Pomiń terminy z konfliktem' }));
  expect(within(dialog).getByRole('checkbox', { name: /10\.11\.2026/ })).not.toBeChecked();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz (2)' }));

  await vi.waitFor(() =>
    expect(requestBody(fetchMock, 'POST', '/api/v1/recurrence-series')).toEqual({
      kind: 'CLASS',
      serviceTypeId: 'pole',
      title: null,
      capacity: 10,
      visibility: 'PUBLIC',
      staff: [],
      resources: [{ resourceId: 'pole-room', blocksWholeRoom: true }],
      weekdays: [2],
      localStartTime: '18:00',
      durationMinutes: 60,
      intervalWeeks: 1,
      startsOn: '2026-11-03',
      endsOn: '2026-11-17',
      excludedDates: ['2026-11-10'],
    }),
  );
});

test('changes who runs the following occurrences of a series', async () => {
  const fetchMock = stubApi((url, method) =>
    url === '/api/v1/schedule-events/pole-class/assignments' && method === 'PUT'
      ? json(baseEvent)
      : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await userEvent.click(await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' }));

  const drawer = await screen.findByRole('dialog');
  expect(within(drawer).getByText('Anna Kowalska — Prowadzący')).toBeInTheDocument();
  expect(await within(drawer).findByText('co tydzień, wtorek, 18:00')).toBeInTheDocument();
  // Before the class starts, its places are booked here; attendance follows once it has started.
  expect(
    await within(drawer).findByRole('button', { name: 'Zapisz uczestnika' }),
  ).toBeInTheDocument();
  expect(within(drawer).queryByText('Uczestnicy i obecność')).not.toBeInTheDocument();
  await userEvent.click(within(drawer).getByRole('button', { name: 'Zmień obsadę i salę' }));

  const dialog = await screen.findByRole('dialog', { name: /Obsada i sala/ });
  await choose(
    within(dialog).getByRole('combobox', { name: 'Asystenci' }),
    'Marek Nowak (bez kwalifikacji do tej usługi)',
  );
  await userEvent.click(within(dialog).getByRole('radio', { name: /Tego i kolejnych/ }));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  await vi.waitFor(() =>
    expect(requestBody(fetchMock, 'PUT', '/api/v1/schedule-events/pole-class/assignments')).toEqual(
      {
        version: 0,
        scope: 'THIS_AND_FOLLOWING',
        staff: [
          { staffId: 'marek', role: 'ASSISTANT' },
          { staffId: 'anna', role: 'LEAD' },
        ],
        resources: [{ resourceId: 'pole-room', blocksWholeRoom: true }],
      },
    ),
  );
});

test('shows the conflicts that rejected a change', async () => {
  stubApi((url, method) =>
    url === '/api/v1/schedule-events/pole-class/assignments' && method === 'PUT'
      ? json(
          {
            status: 409,
            code: 'STAFF_SCHEDULE_CONFLICT',
            conflicts: [
              {
                date: '2026-11-03',
                startsAt: '2026-11-03T17:00:00Z',
                endsAt: '2026-11-03T18:00:00Z',
                excluded: false,
                issues: [
                  {
                    type: 'STAFF_BUSY',
                    blocking: true,
                    staffId: 'marek',
                    resourceId: null,
                    eventId: 'personal',
                    eventTitle: 'Trening personalny – Tomasz',
                    startsAt: '2026-11-03T17:00:00Z',
                    endsAt: '2026-11-03T18:00:00Z',
                  },
                ],
              },
            ],
          },
          409,
        )
      : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await userEvent.click(await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' }));
  await userEvent.click(
    within(await screen.findByRole('dialog')).getByRole('button', {
      name: 'Zmień obsadę i salę',
    }),
  );
  const dialog = await screen.findByRole('dialog', { name: /Obsada i sala/ });
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  expect(
    await within(dialog).findByText(/Prowadzący ma w tym czasie inne zajęcia albo urlop/),
  ).toBeInTheDocument();
  expect(
    within(dialog).getByText(
      /Marek Nowak prowadzi wtedy: Trening personalny – Tomasz \(18:00–19:00\)/,
    ),
  ).toBeInTheDocument();
});

test('plans a block of a room like other events and checks it for conflicts', async () => {
  const conflict = {
    date: '2026-11-03',
    startsAt: '2026-11-03T16:00:00Z',
    endsAt: '2026-11-03T18:00:00Z',
    excluded: false,
    issues: [
      {
        type: 'RESOURCE_BUSY',
        blocking: true,
        staffId: null,
        resourceId: 'pole-room',
        eventId: 'pole-class',
        eventTitle: 'Pole dance',
        startsAt: '2026-11-03T17:00:00Z',
        endsAt: '2026-11-03T18:00:00Z',
      },
    ],
  };
  const fetchMock = stubApi((url, method) =>
    url === '/api/v1/schedule-events/preview' && method === 'POST'
      ? json({ occurrences: [conflict], warnings: [], conflictCount: 1 })
      : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' });

  await userEvent.click(screen.getByRole('button', { name: 'Dodaj wydarzenie' }));
  const dialog = await screen.findByRole('dialog');
  await choose(within(dialog).getByRole('combobox', { name: /Rodzaj/ }), 'Blokada');
  // A block has no service, capacity, visibility or staff.
  expect(within(dialog).queryByRole('combobox', { name: /Usługa/ })).not.toBeInTheDocument();
  expect(within(dialog).queryByLabelText(/Limit miejsc/)).not.toBeInTheDocument();
  expect(within(dialog).queryByRole('combobox', { name: 'Prowadzący' })).not.toBeInTheDocument();
  expect(within(dialog).queryByRole('combobox', { name: 'Asystenci' })).not.toBeInTheDocument();

  await userEvent.click(within(dialog).getByRole('button', { name: 'Dalej: sprawdź terminy' }));
  expect(
    within(dialog).getByText('Wybierz co najmniej jedną salę do zablokowania.'),
  ).toBeInTheDocument();

  await userEvent.type(within(dialog).getByLabelText(/Tytuł/), 'Serwis klimatyzacji');
  fireEvent.change(within(dialog).getByLabelText(/Godzina rozpoczęcia/), {
    target: { value: '17:00' },
  });
  fireEvent.change(within(dialog).getByLabelText(/Czas trwania/), { target: { value: '120' } });
  await choose(within(dialog).getByRole('combobox', { name: 'Sale' }), 'Sala pole dance');
  // A block always takes its rooms whole.
  expect(within(dialog).queryByText(/zajmuje całą salę/)).not.toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dalej: sprawdź terminy' }));

  expect(
    await within(dialog).findByText(/Sala pole dance jest zajęta: Pole dance \(18:00–19:00\)/),
  ).toBeInTheDocument();
  expect(within(dialog).getByRole('button', { name: 'Zapisz' })).toBeDisabled();
  expect(requestBody(fetchMock, 'POST', '/api/v1/schedule-events/preview')).toEqual({
    kind: 'BLOCK',
    serviceTypeId: null,
    title: 'Serwis klimatyzacji',
    capacity: null,
    visibility: 'PRIVATE',
    staff: [],
    resources: [{ resourceId: 'pole-room', blocksWholeRoom: true }],
    startsAt: '2026-11-03T16:00:00.000Z',
    endsAt: '2026-11-03T18:00:00.000Z',
  });
});

test('plans a recurring block of a room', async () => {
  const fetchMock = stubApi(
    (url, method) =>
      url === '/api/v1/recurrence-series/preview' && method === 'POST'
        ? json({
            occurrences: ['2026-11-02', '2026-11-09'].map((date) => ({
              date,
              startsAt: `${date}T07:00:00Z`,
              endsAt: `${date}T08:00:00Z`,
              excluded: false,
              issues: [],
            })),
            warnings: [],
            conflictCount: 0,
          })
        : undefined,
    (url, method) =>
      url === '/api/v1/recurrence-series' && method === 'POST'
        ? json({ items: [], occurrenceCount: 2 }, 201)
        : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-02" />);
  await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' });

  await userEvent.click(screen.getByRole('button', { name: 'Dodaj wydarzenie' }));
  const dialog = await screen.findByRole('dialog');
  await choose(within(dialog).getByRole('combobox', { name: /Rodzaj/ }), 'Blokada');
  await userEvent.click(within(dialog).getByRole('radio', { name: 'Cykliczne' }));
  await userEvent.type(within(dialog).getByLabelText(/Tytuł/), 'Sprzątanie');
  fireEvent.change(within(dialog).getByLabelText(/Do dnia/), { target: { value: '2026-11-09' } });
  fireEvent.change(within(dialog).getByLabelText(/Godzina rozpoczęcia/), {
    target: { value: '08:00' },
  });
  await choose(within(dialog).getByRole('combobox', { name: 'Sale' }), 'Sala treningowa');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dalej: sprawdź terminy' }));

  await userEvent.click(await within(dialog).findByRole('button', { name: 'Zapisz (2)' }));
  const expected = {
    kind: 'BLOCK',
    serviceTypeId: null,
    title: 'Sprzątanie',
    capacity: null,
    visibility: 'PRIVATE',
    staff: [],
    resources: [{ resourceId: 'gym', blocksWholeRoom: true }],
    weekdays: [1],
    localStartTime: '08:00',
    durationMinutes: 60,
    intervalWeeks: 1,
    startsOn: '2026-11-02',
    endsOn: '2026-11-09',
    excludedDates: [],
  };
  await vi.waitFor(() =>
    expect(requestBody(fetchMock, 'POST', '/api/v1/recurrence-series')).toEqual(expected),
  );
  expect(requestBody(fetchMock, 'POST', '/api/v1/recurrence-series/preview')).toEqual(expected);
});

test('changes only one occurrence of a series after checking its new time', async () => {
  const fetchMock = stubApi(
    (url, method) =>
      url === '/api/v1/schedule-events/pole-class/change-preview' && method === 'POST'
        ? json({
            occurrences: [
              {
                date: '2026-11-03',
                startsAt: '2026-11-03T18:00:00Z',
                endsAt: '2026-11-03T19:30:00Z',
                excluded: false,
                issues: [],
              },
            ],
            warnings: [],
            conflictCount: 0,
          })
        : undefined,
    (url, method) =>
      url === '/api/v1/schedule-events/pole-class' && method === 'PUT'
        ? json({ ...baseEvent, isSeriesException: true })
        : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await userEvent.click(await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' }));
  const drawer = await screen.findByRole('dialog');
  await userEvent.click(within(drawer).getByRole('button', { name: 'Zmień to wystąpienie' }));

  const dialog = await screen.findByRole('dialog', { name: /Zmiana wystąpienia/ });
  expect(
    within(dialog).getByText(
      'Zmieniasz tylko wystąpienie z 03.11.2026. Cykl i jego pozostałe terminy zostaną bez zmian.',
    ),
  ).toBeInTheDocument();
  fireEvent.change(within(dialog).getByLabelText(/Początek/), { target: { value: '19:00' } });
  const duration = within(dialog).getByRole('textbox', { name: /Czas trwania/ });
  await userEvent.clear(duration);
  await userEvent.type(duration, '90');
  const capacity = within(dialog).getByRole('textbox', { name: /Limit miejsc/ });
  await userEvent.clear(capacity);
  await userEvent.type(capacity, '8');
  expect(within(dialog).getByText('Koniec o 20:30')).toBeInTheDocument();

  await userEvent.click(within(dialog).getByRole('button', { name: 'Sprawdź termin' }));
  expect(
    await within(dialog).findByText('Termin jest wolny: sale i prowadzący są dostępni.'),
  ).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz zmiany' }));

  const expected = {
    version: 0,
    startsAt: '2026-11-03T18:00:00.000Z',
    endsAt: '2026-11-03T19:30:00.000Z',
    title: 'Pole dance',
    capacity: 8,
    staff: [{ staffId: 'anna', role: 'LEAD' }],
    resources: [{ resourceId: 'pole-room', blocksWholeRoom: true }],
  };
  expect(
    requestBody(fetchMock, 'POST', '/api/v1/schedule-events/pole-class/change-preview'),
  ).toEqual(expected);
  await vi.waitFor(() =>
    expect(requestBody(fetchMock, 'PUT', '/api/v1/schedule-events/pole-class')).toEqual(expected),
  );
  await vi.waitFor(() =>
    expect(screen.queryByRole('dialog', { name: /Zmiana wystąpienia/ })).not.toBeInTheDocument(),
  );
});

test('explains why a change of one occurrence was rejected', async () => {
  stubApi(
    (url, method) =>
      url === '/api/v1/schedule-events/pole-class/change-preview' && method === 'POST'
        ? json({
            occurrences: [
              {
                date: '2026-11-03',
                startsAt: '2026-11-03T17:00:00Z',
                endsAt: '2026-11-03T18:00:00Z',
                excluded: false,
                issues: [
                  {
                    type: 'STAFF_ON_LEAVE',
                    blocking: true,
                    staffId: 'anna',
                    resourceId: null,
                    eventId: null,
                    eventTitle: null,
                    startsAt: '2026-11-03T16:00:00Z',
                    endsAt: '2026-11-03T20:00:00Z',
                  },
                ],
              },
            ],
            warnings: [
              {
                type: 'STAFF_NOT_QUALIFIED',
                blocking: false,
                staffId: 'marek',
                resourceId: null,
                eventId: null,
                eventTitle: null,
                startsAt: null,
                endsAt: null,
              },
            ],
            conflictCount: 1,
          })
        : undefined,
    (url, method) =>
      url === '/api/v1/schedule-events/pole-class' && method === 'PUT'
        ? json(
            {
              status: 400,
              code: 'VALIDATION_FAILED',
              errors: [{ field: 'capacity', code: 'OUT_OF_RANGE', message: 'too low' }],
            },
            400,
          )
        : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await userEvent.click(await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' }));
  await userEvent.click(
    within(await screen.findByRole('dialog')).getByRole('button', {
      name: 'Zmień to wystąpienie',
    }),
  );
  const dialog = await screen.findByRole('dialog', { name: /Zmiana wystąpienia/ });

  await userEvent.click(within(dialog).getByRole('button', { name: 'Sprawdź termin' }));
  expect(
    await within(dialog).findByText('W tym terminie są konflikty, których nie da się zapisać:'),
  ).toBeInTheDocument();
  expect(within(dialog).getByText(/Anna Kowalska ma zatwierdzony urlop/)).toBeInTheDocument();
  expect(
    within(dialog).getByText(/Marek Nowak nie ma kwalifikacji do tej usługi/),
  ).toBeInTheDocument();

  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz zmiany' }));
  expect(await within(dialog).findByText(/Limit miejsc jest za mały/)).toBeInTheDocument();
});

test('brings a moved occurrence back to its series', async () => {
  const moved: ScheduleEvent = {
    ...baseEvent,
    isSeriesException: true,
    seriesChange: {
      usualStartsAt: '2026-11-03T17:00:00Z',
      usualEndsAt: '2026-11-03T18:00:00Z',
      usualInstructors: null,
      usualRooms: null,
    },
    startsAt: '2026-11-03T19:00:00Z',
    endsAt: '2026-11-03T20:00:00Z',
    version: 3,
  };
  const fetchMock = stubApi(
    (url, method) =>
      url.startsWith('/api/v1/schedule-events?') && method === 'GET'
        ? json({ items: [moved] })
        : undefined,
    (url, method) =>
      url === '/api/v1/schedule-events/pole-class/restore-series' && method === 'POST'
        ? json(baseEvent)
        : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await userEvent.click(await screen.findByRole('button', { name: 'Pole dance, 20:00–21:00' }));

  const drawer = await screen.findByRole('dialog');
  expect(within(drawer).getByText('Zmienione w cyklu')).toBeInTheDocument();
  expect(within(drawer).getByText('Termin według cyklu')).toBeInTheDocument();
  expect(within(drawer).getByText('03.11.2026 18:00')).toBeInTheDocument();
  await userEvent.click(within(drawer).getByRole('button', { name: 'Przywróć ustawienia cyklu' }));

  const confirm = await screen.findByRole('dialog', { name: 'Przywrócić ustawienia cyklu?' });
  expect(within(confirm).getByText(/wróci do terminu 03.11.2026 18:00/)).toBeInTheDocument();
  await userEvent.click(within(confirm).getByRole('button', { name: 'Przywróć' }));

  await vi.waitFor(() =>
    expect(
      requestBody(fetchMock, 'POST', '/api/v1/schedule-events/pole-class/restore-series'),
    ).toEqual({ version: 3 }),
  );
});

test('an event that has started can no longer be moved', async () => {
  vi.setSystemTime(new Date('2026-11-03T17:30:00Z'));
  stubApi();
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await userEvent.click(await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' }));

  const drawer = await screen.findByRole('dialog');
  expect(
    within(drawer).getByText('Wydarzenie już się rozpoczęło, więc nie można go zmieniać.'),
  ).toBeInTheDocument();
  expect(
    within(drawer).queryByRole('button', { name: 'Zmień to wystąpienie' }),
  ).not.toBeInTheDocument();
  expect(within(drawer).getByRole('button', { name: 'Zmień obsadę i salę' })).toBeInTheDocument();
});

test('shows the usual lead and room of an occurrence changed this once', async () => {
  const substituted: ScheduleEvent = {
    ...baseEvent,
    isSeriesException: true,
    seriesChange: {
      usualStartsAt: null,
      usualEndsAt: null,
      usualInstructors: ['Beata Lis'],
      usualRooms: ['Sala B'],
    },
  };
  stubApi((url, method) =>
    url.startsWith('/api/v1/schedule-events?') && method === 'GET'
      ? json({ items: [substituted] })
      : undefined,
  );
  renderWithProviders(<SchedulePage initialDate="2026-11-03" />);
  await userEvent.click(await screen.findByRole('button', { name: 'Pole dance, 18:00–19:00' }));

  const drawer = await screen.findByRole('dialog');
  expect(within(drawer).getByText('Prowadzący według cyklu')).toBeInTheDocument();
  expect(within(drawer).getByText('Beata Lis')).toHaveStyle({ textDecoration: 'line-through' });
  expect(within(drawer).getByText('Sala według cyklu')).toBeInTheDocument();
  expect(within(drawer).getByText('Sala B')).toBeInTheDocument();
  expect(within(drawer).queryByText('Termin według cyklu')).not.toBeInTheDocument();
});
