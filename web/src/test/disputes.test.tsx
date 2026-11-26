import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { DisputesPage } from '@/features/disputes/DisputesPage';
import type { Dispute, DisputeOptions } from '@/features/disputes/disputesApi';
import { formatSession, trainerNames } from '@/features/disputes/disputeFormat';
import { renderWithProviders } from './renderWithProviders';

const ZONE = 'Europe/Warsaw';
const BASE = '/api/v1/personal-training-disputes';

const passOptions: DisputeOptions = {
  funding: 'PASS',
  passName: 'Pakiet PT 8',
  entriesRemaining: 3,
  passValidUntil: '2026-12-31',
  makeupExpiresAt: null,
  upheld: { allowed: true, effect: 'ENTRY_RETURNED', notice: null },
  upheldMakeup: { allowed: true, effect: 'MAKEUP_GRANTED', notice: null },
  rejected: { allowed: true, effect: 'NO_CHANGE', notice: null },
  makeupValidityDays: 21,
};

const open: Dispute = {
  id: 'dispute-1',
  status: 'OPEN',
  reason: 'Trening trwał 30 minut zamiast 60.',
  raisedAt: '2026-11-10T19:30:00Z',
  raisedByName: 'Tomasz Lewandowski',
  reservationId: 'reservation-1',
  reservationVersion: 4,
  customerId: 'profile-tomasz',
  customerKind: 'ADULT',
  customerName: 'Tomasz Lewandowski',
  customerAccountId: null,
  eventId: 'event-1',
  title: 'Trening personalny',
  serviceTypeName: 'Trening personalny',
  // 10.11.2026 18:00–19:00 in Warsaw (UTC+1).
  startsAt: '2026-11-10T17:00:00Z',
  endsAt: '2026-11-10T18:00:00Z',
  eventStatus: 'COMPLETED',
  staff: [
    { staffId: 'staff-ewa', displayName: 'Ewa Wiśniewska', role: 'ASSISTANT' },
    { staffId: 'staff-marek', displayName: 'Marek Nowak', role: 'LEAD' },
  ],
  attendance: 'PRESENT',
  trainerConfirmedAt: '2026-11-10T18:10:00Z',
  resolution: null,
  resolutionEffect: null,
  resolutionNote: null,
  resolvedAt: null,
  resolvedByName: null,
  options: null,
};

const resolved: Dispute = {
  ...open,
  id: 'dispute-2',
  status: 'RESOLVED',
  reason: 'Nie było mnie na tym treningu.',
  resolution: 'REJECTED',
  resolutionEffect: 'NO_CHANGE',
  resolutionNote: 'Obecność potwierdził trener.',
  resolvedAt: '2026-11-12T09:00:00Z',
  resolvedByName: 'Beata Lis',
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function page(items: Dispute[], totalItems = items.length) {
  return { items, page: 0, size: 20, totalItems, totalPages: Math.ceil(totalItems / 20) };
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

const studio: Route = (url) =>
  url === '/api/v1/studio' ? json({ name: 'Studio', timeZone: ZONE, currency: 'PLN' }) : undefined;

function listing(openItems: Dispute[], resolvedItems: Dispute[] = []): Route {
  return (url, init) => {
    if (!url.startsWith(`${BASE}?`) || init.method !== 'GET') return undefined;
    const params = new URLSearchParams(url.split('?')[1]);
    return json(page(params.get('status') === 'RESOLVED' ? resolvedItems : openItems));
  };
}

function detail(dispute: Dispute): Route {
  return (url, init) =>
    url === `${BASE}/${dispute.id}` && init.method === 'GET' ? json(dispute) : undefined;
}

function renderPage() {
  return renderWithProviders(null, {
    routes: [{ path: '/disputes', element: <DisputesPage /> }],
    initialEntry: '/disputes',
  });
}

function bodyOf(fetchMock: ReturnType<typeof stubApi>, url: string): unknown {
  const call = fetchMock.mock.calls.find(([u, init]) => u === url && init.method === 'POST');
  return call ? JSON.parse(call[1].body as string) : undefined;
}

async function openDrawer(): Promise<HTMLElement> {
  const row = (await screen.findByText(open.reason)).closest('tr')!;
  await userEvent.click(within(row).getByRole('button', { name: 'Rozstrzygnij' }));
  return screen.findByRole('dialog');
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-11-12T12:00:00Z') });
});

afterEach(() => {
  vi.useRealTimers();
});

test('formats the session in the studio time zone with the lead trainer first', () => {
  expect(formatSession(open, ZONE)).toBe('10.11.2026, 18:00–19:00');
  expect(trainerNames(open)).toBe('Marek Nowak, Ewa Wiśniewska');
  expect(trainerNames({ staff: [] })).toBe('—');
});

test('lists open disputes with the participant, the session and how many wait', async () => {
  const fetchMock = stubApi(studio, listing([open], [resolved]));
  renderPage();

  const row = (await screen.findByText(open.reason)).closest('tr')!;
  expect(within(row).getByText('Tomasz Lewandowski')).toBeInTheDocument();
  expect(within(row).getByText('10.11.2026, 18:00–19:00')).toBeInTheDocument();
  expect(within(row).getByText('Marek Nowak, Ewa Wiśniewska')).toBeInTheDocument();
  expect(within(row).getByText('10.11.2026 20:30')).toBeInTheDocument();
  const openTab = screen.getByRole('tab', { name: /Do rozstrzygnięcia/ });
  expect(await within(openTab).findByText('1')).toBeInTheDocument();
  expect(fetchMock.mock.calls.some(([url]) => url === `${BASE}?status=OPEN&page=0&size=20`)).toBe(
    true,
  );
});

test('the history shows each decision, what it did and who took it', async () => {
  stubApi(studio, listing([open], [resolved]));
  renderPage();

  await userEvent.click(await screen.findByRole('tab', { name: 'Rozstrzygnięte' }));

  const row = (await screen.findByText(resolved.reason)).closest('tr')!;
  expect(within(row).getByText('Odrzucone')).toBeInTheDocument();
  expect(within(row).getByText('Bez zmian w rozliczeniu')).toBeInTheDocument();
  expect(within(row).getByText('Beata Lis, 12.11.2026 10:00')).toBeInTheDocument();
  expect(within(screen.getByRole('table')).queryByText(open.reason)).not.toBeInTheDocument();
});

test('upholding returns the entry after the administrator explains the decision', async () => {
  const fetchMock = stubApi(
    studio,
    listing([open]),
    detail({ ...open, customerAccountId: 'account-tomasz', options: passOptions }),
    (url, init) =>
      url === `${BASE}/${open.id}/resolve` && init.method === 'POST'
        ? json({ ...open, status: 'RESOLVED', resolution: 'UPHELD' })
        : undefined,
  );
  renderPage();
  const drawer = await openDrawer();

  expect(
    await within(drawer).findByText('Pakiet PT 8 · pozostało wejść: 3 · ważny do 31.12.2026'),
  ).toBeInTheDocument();
  expect(within(drawer).getByRole('link', { name: 'Konto klienta' })).toHaveAttribute(
    'href',
    '/accounts/account-tomasz?profile=profile-tomasz',
  );
  expect(within(drawer).getByText('Wejście wróci do pakietu klienta.')).toBeInTheDocument();
  const save = within(drawer).getByRole('button', { name: 'Zapisz decyzję' });
  expect(save).toBeDisabled();

  await userEvent.click(within(drawer).getByRole('radio', { name: /Uznaj zgłoszenie/ }));
  expect(save).toBeDisabled();
  await userEvent.type(
    within(drawer).getByRole('textbox', { name: /Uzasadnienie dla klienta/ }),
    '  Trener potwierdził skrócenie zajęć.  ',
  );
  await userEvent.click(save);

  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(bodyOf(fetchMock, `${BASE}/${open.id}/resolve`)).toEqual({
    resolution: 'UPHELD',
    note: 'Trener potwierdził skrócenie zajęć.',
    makeupValidityDays: null,
    version: 4,
  });
});

test('a make-up is granted for the suggested number of days, which can be changed', async () => {
  const fetchMock = stubApi(
    studio,
    listing([open]),
    detail({ ...open, options: passOptions }),
    (url, init) =>
      url === `${BASE}/${open.id}/resolve` && init.method === 'POST' ? json(open) : undefined,
  );
  renderPage();
  const drawer = await openDrawer();

  await userEvent.click(
    await within(drawer).findByRole('radio', { name: /Uznaj i przyznaj odróbkę/ }),
  );
  const days = within(drawer).getByRole('textbox', { name: 'Ważność odróbki (dni)' });
  expect(days).toHaveValue('21');
  expect(within(drawer).getByText('Odróbka będzie ważna do 03.12.2026 13:00.')).toBeInTheDocument();
  await userEvent.clear(days);
  await userEvent.type(days, '14');
  await userEvent.type(within(drawer).getByRole('textbox', { name: /Uzasadnienie/ }), 'Odróbka');
  await userEvent.click(within(drawer).getByRole('button', { name: 'Zapisz decyzję' }));

  await waitFor(() =>
    expect(bodyOf(fetchMock, `${BASE}/${open.id}/resolve`)).toEqual({
      resolution: 'UPHELD_MAKEUP',
      note: 'Odróbka',
      makeupValidityDays: 14,
      version: 4,
    }),
  );
});

test('decisions that do not fit the funding are disabled and explained', async () => {
  stubApi(
    studio,
    listing([open]),
    detail({
      ...open,
      options: {
        ...passOptions,
        funding: 'UNLIMITED_PASS',
        passName: 'Pakiet PT bez limitu',
        entriesRemaining: null,
        upheld: { allowed: true, effect: 'NO_CHANGE', notice: 'UNLIMITED_PASS' },
        upheldMakeup: { allowed: false, effect: 'MAKEUP_GRANTED', notice: 'UNLIMITED_PASS' },
        makeupValidityDays: null,
      },
    }),
  );
  renderPage();
  const drawer = await openDrawer();

  expect(
    await within(drawer).findByText('Pakiet PT bez limitu (bez limitu wejść)'),
  ).toBeInTheDocument();
  expect(within(drawer).getByRole('radio', { name: /Uznaj i przyznaj odróbkę/ })).toBeDisabled();
  expect(within(drawer).getByRole('radio', { name: /Uznaj zgłoszenie/ })).toBeEnabled();
  expect(
    within(drawer).getAllByText(
      'Pakiet bez limitu wejść: saldo się nie zmienia, a odróbki z niego nie przysługują.',
    ),
  ).toHaveLength(2);
});

test('an expired pass still gets the entry back, with a warning', async () => {
  stubApi(
    studio,
    listing([open]),
    detail({
      ...open,
      options: {
        ...passOptions,
        passValidUntil: '2026-11-01',
        upheld: { allowed: true, effect: 'ENTRY_RETURNED', notice: 'PASS_EXPIRED' },
      },
    }),
  );
  renderPage();
  const drawer = await openDrawer();

  expect(
    await within(drawer).findByText(
      'Pakiet jest już nieważny. Wejście wróci do salda, ale klient skorzysta z niego dopiero po przedłużeniu pakietu.',
    ),
  ).toBeInTheDocument();
  expect(within(drawer).getByRole('radio', { name: /Uznaj zgłoszenie/ })).toBeEnabled();
});

test('a decision another administrator took first is reported and the dispute reloaded', async () => {
  let resolvedMeanwhile = false;
  stubApi(
    studio,
    listing([open]),
    (url, init) =>
      url === `${BASE}/${open.id}` && init.method === 'GET'
        ? json(
            resolvedMeanwhile
              ? { ...resolved, id: open.id, reason: open.reason }
              : { ...open, options: passOptions },
          )
        : undefined,
    (url, init) => {
      if (url !== `${BASE}/${open.id}/resolve` || init.method !== 'POST') return undefined;
      resolvedMeanwhile = true;
      return json({ code: 'DISPUTE_ALREADY_RESOLVED', status: 409, detail: 'Resolved.' }, 409);
    },
  );
  renderPage();
  const drawer = await openDrawer();

  await userEvent.click(await within(drawer).findByRole('radio', { name: /Odrzuć zgłoszenie/ }));
  await userEvent.type(within(drawer).getByRole('textbox', { name: /Uzasadnienie/ }), 'Odrzucam');
  await userEvent.click(within(drawer).getByRole('button', { name: 'Zapisz decyzję' }));

  expect(await within(drawer).findByText('Rozstrzygnięcie')).toBeInTheDocument();
  expect(within(drawer).getByText('Obecność potwierdził trener.')).toBeInTheDocument();
  expect(within(drawer).queryByRole('button', { name: 'Zapisz decyzję' })).not.toBeInTheDocument();
});

test('a resolved dispute opens read-only with the explanation shown to the client', async () => {
  stubApi(studio, listing([], [resolved]), detail(resolved));
  renderPage();

  await userEvent.click(await screen.findByRole('tab', { name: 'Rozstrzygnięte' }));
  const row = (await screen.findByText(resolved.reason)).closest('tr')!;
  await userEvent.click(within(row).getByRole('button', { name: 'Szczegóły' }));
  const drawer = await screen.findByRole('dialog');

  expect(await within(drawer).findByText('Obecność potwierdził trener.')).toBeInTheDocument();
  expect(within(drawer).getByText('Beata Lis, 12.11.2026 10:00')).toBeInTheDocument();
  expect(within(drawer).queryByRole('radio')).not.toBeInTheDocument();
});
