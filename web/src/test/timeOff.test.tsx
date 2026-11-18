import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { TimeOffPage } from '@/features/staff/TimeOffPage';
import { formatPeriod, isCancellable } from '@/features/staff/timeOffFormat';
import type { TimeOff, TimeOffLesson, TimeOffPlan } from '@/features/staff/timeOffApi';
import { TimeOffSubstitutionsDrawer, TimeOffSessionPicker } from '@/features/staff/TimeOffCoverage';
import { renderWithProviders } from './renderWithProviders';

const ZONE = 'Europe/Warsaw';

test('unchecked sessions and consultations clearly require a manual decision', async () => {
  const consultation = {
    ...lesson('consultation', '2026-11-12T17:00:00Z'),
    title: 'Konsultacja',
    kind: 'CONSULTATION',
    status: 'MANUAL',
    selectable: false,
    selected: false,
  };
  renderWithProviders(
    <TimeOffSessionPicker
      plan={coverPlan([consultation, lesson('class', '2026-11-13T17:00:00Z')])}
      choices={{ class: false }}
      onChange={() => {}}
      zone={ZONE}
    />,
  );
  expect(screen.getByText('Do decyzji studia')).toBeInTheDocument();
  expect(
    screen.getByText(
      'Odznaczone zajęcia i konsultacje nie są automatycznie odwoływane. Administrator musi ustalić ich dalszy przebieg.',
    ),
  ).toBeInTheDocument();
  expect(screen.getAllByRole('checkbox')[0]).toBeDisabled();
});

const vacation: TimeOff = {
  id: 'off-1',
  staffId: 'staff-anna',
  staffDisplayName: 'Anna Kowalska',
  kind: 'VACATION',
  status: 'REQUESTED',
  // 10–14.11.2026, whole days in Warsaw (UTC+1).
  startsAt: '2026-11-09T23:00:00Z',
  endsAt: '2026-11-14T23:00:00Z',
  reason: 'Wyjazd rodzinny',
  requestedAt: '2026-11-01T09:30:00Z',
  decidedAt: null,
  decisionNote: null,
  decidedByName: null,
  recordedByStudio: false,
};

const approvedSick: TimeOff = {
  ...vacation,
  id: 'off-2',
  staffId: 'staff-piotr',
  staffDisplayName: 'Piotr Nowak',
  kind: 'SICK_LEAVE',
  status: 'APPROVED',
  startsAt: '2026-11-20T07:00:00Z',
  endsAt: '2026-11-20T11:00:00Z',
  reason: null,
  decidedAt: '2026-11-02T10:00:00Z',
  decisionNote: 'Zdrowia!',
  decidedByName: 'Beata Lis',
  recordedByStudio: true,
};

const rejected: TimeOff = {
  ...vacation,
  id: 'off-3',
  status: 'REJECTED',
  startsAt: '2026-10-19T23:00:00Z',
  endsAt: '2026-10-21T23:00:00Z',
  decidedAt: '2026-10-15T08:00:00Z',
  decisionNote: 'Brak zastępstwa',
  decidedByName: 'Beata Lis',
};

const staff = [
  { id: 'staff-anna', firstName: 'Anna', lastName: 'Kowalska', displayName: 'Anna Kowalska' },
  { id: 'staff-piotr', firstName: 'Piotr', lastName: 'Nowak', displayName: 'Piotr Nowak' },
].map((member) => ({ ...member, calendarColor: null, isActive: true, serviceTypeIds: [] }));

function lesson(id: string, startsAt: string, status = 'NOT_REQUESTED'): TimeOffLesson {
  return {
    eventId: id,
    eventVersion: 2,
    kind: 'CLASS',
    status,
    title: 'Pole dance – grupa',
    rooms: 'Sala A',
    startsAt,
    endsAt: startsAt.replace('T17', 'T18'),
    selectable: true,
    selected: true,
    requestId: status === 'NOT_REQUESTED' ? null : `request-${id}`,
    requestVersion: status === 'NOT_REQUESTED' ? null : 3,
    substituteStaffId: status === 'CLAIMED' ? 'staff-piotr' : null,
    substituteTrainer: status === 'CLAIMED' ? 'Piotr Nowak' : null,
  };
}

function coverPlan(items: TimeOffLesson[] = [], status = 'REQUESTED'): TimeOffPlan {
  return { timeOffId: vacation.id, version: 4, status, items };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

type Route = (url: string, init: RequestInit) => Response | undefined;

function stubApi(...routes: Route[]) {
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    for (const route of routes) {
      const response = route(url, init);
      if (response) {
        return response;
      }
    }
    throw new Error(`Unexpected request: ${init.method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const lookups: Route = (url) => {
  if (
    url.endsWith('/substitutions') ||
    url.startsWith('/api/v1/staff/time-off/substitution-options?')
  )
    return json(coverPlan());
  if (url.startsWith('/api/v1/schedule-events?')) {
    return json({ items: [] });
  }
  if (url === '/api/v1/studio') {
    return json({ name: 'Studio', timeZone: ZONE, currency: 'PLN' });
  }
  if (url === '/api/v1/staff') {
    return json({ items: staff });
  }
  return undefined;
};

function listing(items: TimeOff[]): Route {
  return (url, init) => {
    if (!url.startsWith('/api/v1/staff/time-off?') || init.method !== 'GET') {
      return undefined;
    }
    const statuses = new URLSearchParams(url.split('?')[1]).getAll('status');
    return json({
      items: statuses.length === 0 ? items : items.filter((item) => statuses.includes(item.status)),
    });
  };
}

/** Picks a trainer in the dialog's own select; the page's filter offers the same names. */
async function pickTrainer(dialog: HTMLElement, name: string) {
  const select = within(dialog).getByRole('combobox', { name: 'Trener' });
  await userEvent.click(select);
  await waitFor(() => expect(select.getAttribute('aria-controls')).toBeTruthy());
  const listbox = document.getElementById(select.getAttribute('aria-controls')!)!;
  await userEvent.click(within(listbox).getByRole('option', { name, hidden: true }));
}

function bodyOf(fetchMock: ReturnType<typeof stubApi>, method: string, url: string): unknown {
  const call = fetchMock.mock.calls.find(([u, init]) => u === url && init.method === method);
  return call ? JSON.parse(call[1].body as string) : undefined;
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-11-01T12:00:00Z') });
});

afterEach(() => {
  vi.useRealTimers();
});

test('formats whole days, part of a day and longer periods in the studio time zone', () => {
  expect(formatPeriod(vacation.startsAt, vacation.endsAt, ZONE)).toBe(
    '10.11.2026 – 14.11.2026 (5 dni)',
  );
  expect(formatPeriod('2026-11-09T23:00:00Z', '2026-11-10T23:00:00Z', ZONE)).toBe(
    '10.11.2026 (cały dzień)',
  );
  expect(formatPeriod(approvedSick.startsAt, approvedSick.endsAt, ZONE)).toBe(
    '20.11.2026, 08:00–12:00',
  );
  expect(formatPeriod('2026-11-20T07:00:00Z', '2026-11-21T11:00:00Z', ZONE)).toBe(
    '20.11.2026 08:00 – 21.11.2026 12:00',
  );
});

test('only active time off that has not ended can be cancelled', () => {
  const now = new Date('2026-11-12T00:00:00Z');
  expect(isCancellable(vacation, now)).toBe(true);
  expect(isCancellable({ ...vacation, status: 'REJECTED' }, now)).toBe(false);
  expect(isCancellable({ ...vacation, endsAt: '2026-11-11T00:00:00Z' }, now)).toBe(false);
});

test('lists pending requests first and shows how many wait for a decision', async () => {
  const fetchMock = stubApi(lookups, listing([vacation, approvedSick]));
  renderWithProviders(<TimeOffPage />);

  const row = (await screen.findByText('Wyjazd rodzinny')).closest('tr')!;
  expect(within(row).getByText('Anna Kowalska')).toBeInTheDocument();
  expect(within(row).getByText('Urlop')).toBeInTheDocument();
  expect(within(row).getByText('10.11.2026 – 14.11.2026 (5 dni)')).toBeInTheDocument();
  expect(await within(row).findByText('brak')).toBeInTheDocument();
  expect(within(screen.getByRole('table')).queryByText('Piotr Nowak')).not.toBeInTheDocument();
  const pendingTab = screen.getByRole('tab', { name: /Do decyzji/ });
  expect(within(pendingTab).getByText('1')).toBeInTheDocument();
  expect(
    fetchMock.mock.calls.some(([url]) =>
      url.startsWith('/api/v1/staff/time-off?status=REQUESTED&from=2026-11-01T12%3A00'),
    ),
  ).toBe(true);

  await userEvent.click(screen.getByRole('tab', { name: 'Nadchodzące' }));

  const sickRow = (await screen.findByText('Zwolnienie lekarskie')).closest('tr')!;
  expect(within(sickRow).getByText('Zatwierdzony')).toBeInTheDocument();
  expect(within(sickRow).getByText(/Beata Lis/)).toBeInTheDocument();
  expect(within(sickRow).getByText('dodane przez studio')).toBeInTheDocument();
  expect(within(sickRow).queryByRole('button', { name: 'Rozpatrz' })).not.toBeInTheDocument();
  expect(within(screen.getByRole('table')).queryByText('Anna Kowalska')).not.toBeInTheDocument();
});

test('history shows decided and past time off with the studio note', async () => {
  stubApi(lookups, listing([vacation, rejected]));
  renderWithProviders(<TimeOffPage />);

  await userEvent.click(await screen.findByRole('tab', { name: 'Historia' }));

  const row = (await screen.findByText('Brak zastępstwa')).closest('tr')!;
  expect(within(row).getByText('Odrzucony')).toBeInTheDocument();
  expect(within(row).getByText(/Beata Lis, 15.10.2026/)).toBeInTheDocument();
  expect(within(screen.getByRole('table')).queryByText('Wyjazd rodzinny')).not.toBeInTheDocument();
});

test('the pending count shows on the trainer’s events and in the table', async () => {
  stubApi(
    listing([vacation]),
    (url) =>
      url.endsWith('/off-1/substitutions')
        ? json(
            coverPlan([lesson('e1', '2026-11-12T17:00:00Z'), lesson('e2', '2026-11-13T17:00:00Z')]),
          )
        : undefined,
    lookups,
  );
  renderWithProviders(<TimeOffPage />);

  const row = (await screen.findByText('Wyjazd rodzinny')).closest('tr')!;
  expect(await within(row).findByText('2 zajęcia')).toBeInTheDocument();
});

test('review defaults to all sessions, supports all/none and approves only the chosen session', async () => {
  const fetchMock = stubApi(
    listing([vacation]),
    (url) =>
      url.endsWith('/off-1/substitutions')
        ? json(
            coverPlan([lesson('e1', '2026-11-12T17:00:00Z'), lesson('e2', '2026-11-13T17:00:00Z')]),
          )
        : undefined,
    (url, init) =>
      url === '/api/v1/staff/time-off/off-1/approve' && init.method === 'POST'
        ? json({ ...vacation, status: 'APPROVED' })
        : undefined,
    lookups,
  );
  renderWithProviders(<TimeOffPage />);

  await userEvent.click(await screen.findByRole('button', { name: 'Rozpatrz' }));

  const dialog = await screen.findByRole('dialog');
  expect(await within(dialog).findByText('12.11.2026 18:00–19:00')).toBeInTheDocument();
  const checks = within(dialog).getAllByRole('checkbox');
  checks.forEach((check) => expect(check).toBeChecked());
  await userEvent.click(within(dialog).getByRole('button', { name: 'Odznacz wszystkie' }));
  checks.forEach((check) => expect(check).not.toBeChecked());
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zaznacz wszystkie' }));
  await userEvent.click(checks[1]);

  await userEvent.type(
    within(dialog).getByLabelText('Notatka dla trenera (opcjonalnie)'),
    ' Zastępuje Piotr ',
  );
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zatwierdź' }));

  await waitFor(() =>
    expect(bodyOf(fetchMock, 'POST', '/api/v1/staff/time-off/off-1/approve')).toEqual({
      note: 'Zastępuje Piotr',
      version: 4,
      substitutionEventIds: ['e1'],
    }),
  );
  expect(
    await screen.findByRole('dialog', { name: 'Zastępstwa na czas nieobecności · Anna Kowalska' }),
  ).toBeInTheDocument();
});

test('a request someone else already decided shows a clear message', async () => {
  stubApi(
    lookups,
    listing([vacation]),
    (url) => (url.startsWith('/api/v1/schedule-events?') ? json({ items: [] }) : undefined),
    (url) =>
      url === '/api/v1/staff/time-off/off-1/reject'
        ? json({ code: 'TIME_OFF_ALREADY_DECIDED', status: 'APPROVED' }, 409)
        : undefined,
  );
  renderWithProviders(<TimeOffPage />);

  await userEvent.click(await screen.findByRole('button', { name: 'Rozpatrz' }));
  const dialog = await screen.findByRole('dialog');
  expect(
    await within(dialog).findByText('Brak zajęć do przekazania w tym okresie.'),
  ).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Odrzuć' }));

  expect(
    await within(dialog).findByText('Ktoś już rozpatrzył ten wniosek. Odśwież listę.'),
  ).toBeInTheDocument();
});

test('cancels approved time off after confirmation', async () => {
  const approved = { ...vacation, status: 'APPROVED' as const, decidedAt: '2026-11-01T10:00:00Z' };
  const fetchMock = stubApi(lookups, listing([approved]), (url, init) =>
    url === '/api/v1/staff/time-off/off-1/cancel' && init.method === 'POST'
      ? json({ ...approved, status: 'CANCELLED' })
      : undefined,
  );
  renderWithProviders(<TimeOffPage />);

  await userEvent.click(await screen.findByRole('tab', { name: 'Nadchodzące' }));
  await userEvent.click(await screen.findByRole('button', { name: 'Anuluj' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByText(/Anna Kowalska: 10.11.2026/)).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Anuluj nieobecność' }));

  await waitFor(() =>
    expect(
      fetchMock.mock.calls.some(([url]) => url === '/api/v1/staff/time-off/off-1/cancel'),
    ).toBe(true),
  );
});

test('records whole days of sick leave for a trainer', async () => {
  const fetchMock = stubApi(lookups, listing([]), (url, init) =>
    url === '/api/v1/staff/time-off' && init.method === 'POST'
      ? json({ ...approvedSick, status: 'APPROVED' }, 201)
      : undefined,
  );
  renderWithProviders(<TimeOffPage />);

  expect(await screen.findByText('Brak wniosków do decyzji.')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: '+ Dodaj nieobecność' }));
  const dialog = await screen.findByRole('dialog');

  await userEvent.click(within(dialog).getByRole('button', { name: 'Dodaj' }));
  expect(within(dialog).getByText('To pole jest wymagane.')).toBeInTheDocument();

  await pickTrainer(dialog, 'Piotr Nowak');
  const dateTo = within(dialog).getByLabelText(/Do dnia/);
  await userEvent.clear(dateTo);
  await userEvent.type(dateTo, '2026-11-03');
  await userEvent.type(within(dialog).getByLabelText('Powód'), 'Telefon rano');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dodaj' }));

  await waitFor(() =>
    expect(bodyOf(fetchMock, 'POST', '/api/v1/staff/time-off')).toEqual({
      staffId: 'staff-piotr',
      kind: 'SICK_LEAVE',
      startsAt: '2026-10-31T23:00:00.000Z',
      endsAt: '2026-11-03T23:00:00.000Z',
      reason: 'Telefon rano',
      substitutionEventIds: [],
    }),
  );
});

test('a time range that ends before it starts is not sent', async () => {
  const fetchMock = stubApi(lookups, listing([]));
  renderWithProviders(<TimeOffPage />);

  await userEvent.click(await screen.findByRole('button', { name: '+ Dodaj nieobecność' }));
  const dialog = await screen.findByRole('dialog');
  await pickTrainer(dialog, 'Anna Kowalska');
  await userEvent.click(within(dialog).getByLabelText('Część dnia'));
  const timeTo = within(dialog).getByLabelText(/Do godziny/);
  await userEvent.clear(timeTo);
  await userEvent.type(timeTo, '07:00');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dodaj' }));

  expect(within(dialog).getByText('Koniec musi być po początku.')).toBeInTheDocument();
  expect(fetchMock.mock.calls.some(([, init]) => init.method === 'POST')).toBe(false);
});

test('bulk approval confirms the selected claims with their original versions', async () => {
  const items = [
    lesson('e1', '2026-11-12T17:00:00Z', 'CLAIMED'),
    lesson('e2', '2026-11-13T17:00:00Z', 'CLAIMED'),
  ];
  const fetchMock = stubApi((url, init) => {
    if (url.endsWith('/substitutions') && init.method === 'GET')
      return json(coverPlan(items, 'APPROVED'));
    if (url.endsWith('/substitutions/approve')) return json(coverPlan([], 'APPROVED'));
    return undefined;
  });
  renderWithProviders(
    <TimeOffSubstitutionsDrawer
      timeOff={{ ...vacation, status: 'APPROVED' }}
      zone={ZONE}
      onClose={() => {}}
    />,
  );
  await userEvent.click(await screen.findByRole('button', { name: 'Zatwierdź zgłoszonych (2)' }));
  expect(fetchMock.mock.calls.some(([, init]) => init.method === 'POST')).toBe(false);
  const confirm = await screen.findByRole('dialog', { name: 'Zatwierdzić wybrane zastępstwa?' });
  expect(within(confirm).getAllByText(/Piotr Nowak/)).toHaveLength(2);
  await userEvent.click(within(confirm).getByRole('button', { name: 'Potwierdź' }));
  await waitFor(() =>
    expect(bodyOf(fetchMock, 'POST', '/api/v1/staff/time-off/off-1/substitutions/approve')).toEqual(
      {
        version: 4,
        note: '',
        items: [
          { eventId: 'e1', eventVersion: 2, requestId: 'request-e1', requestVersion: 3 },
          { eventId: 'e2', eventVersion: 2, requestId: 'request-e2', requestVersion: 3 },
        ],
      },
    ),
  );
});

test('batch assignment offers only trainers available for all checked sessions', async () => {
  const items = [
    lesson('e1', '2026-11-12T17:00:00Z', 'OPEN'),
    lesson('e2', '2026-11-13T17:00:00Z'),
  ];
  const fetchMock = stubApi((url, init) => {
    if (url.endsWith('/substitutions') && init.method === 'GET')
      return json(coverPlan(items, 'APPROVED'));
    if (url.endsWith('/candidates'))
      return json({
        items: [
          {
            staffId: 'good',
            displayName: 'Ewa Dostępna',
            available: true,
            reason: null,
            warnings: [],
          },
          {
            staffId: 'busy',
            displayName: 'Piotr Zajęty',
            available: false,
            reason: 'STAFF_BUSY',
            warnings: [],
          },
        ],
      });
    if (url.endsWith('/assign')) return json({ code: 'VERSION_CONFLICT' }, 409);
    return undefined;
  });
  renderWithProviders(
    <TimeOffSubstitutionsDrawer
      timeOff={{ ...vacation, status: 'APPROVED' }}
      zone={ZONE}
      onClose={() => {}}
    />,
  );
  const select = await screen.findByRole('combobox', {
    name: 'Wybierz zastępcę dla zaznaczonych terminów',
  });
  await waitFor(() => expect(select).not.toBeDisabled());
  await userEvent.click(select);
  expect(await screen.findByRole('option', { name: /Piotr Zajęty/, hidden: true })).toHaveAttribute(
    'data-combobox-disabled',
    'true',
  );
  await userEvent.click(screen.getByRole('option', { name: 'Ewa Dostępna', hidden: true }));
  await userEvent.click(screen.getByRole('button', { name: 'Przypisz zastępcę (2)' }));
  const confirm = await screen.findByRole('dialog', {
    name: 'Przypisać zastępcę do tych terminów?',
  });
  expect(within(confirm).getByText('Ewa Dostępna')).toBeInTheDocument();
  await userEvent.click(within(confirm).getByRole('button', { name: 'Potwierdź' }));
  expect(
    await screen.findByText(
      'Wniosek lub grafik się zmienił. Odśwież widok i sprawdź zaznaczone terminy.',
    ),
  ).toBeInTheDocument();
  expect(
    bodyOf(fetchMock, 'POST', '/api/v1/staff/time-off/off-1/substitutions/assign'),
  ).toMatchObject({
    version: 4,
    staffId: 'good',
    items: [
      { eventId: 'e1', requestVersion: 3 },
      { eventId: 'e2', requestId: null, requestVersion: null },
    ],
  });
});
