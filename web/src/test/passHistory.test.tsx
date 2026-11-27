import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import type { CustomerPass, HeldPass } from '@/features/passes/customerPassesApi';
import { PassesPage } from '@/features/passes/PassesPage';
import type {
  PassHistory,
  PassHistoryEntry,
  PassHistorySession,
} from '@/features/passes/passHistoryApi';
import { renderWithProviders } from './renderWithProviders';

const pass: CustomerPass = {
  id: 'pass-1',
  customerId: 'customer-1',
  variantId: 'variant-1',
  productName: 'Karnet Pole Dance — 4 wejścia',
  entriesTotal: 4,
  entriesRemaining: 1,
  pricePaid: { amountMinor: 18000, currency: 'PLN' },
  status: 'ACTIVE',
  validFrom: '2026-10-27',
  validUntil: '2026-12-06',
  orderItemId: null,
  createdBy: 'admin-1',
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: '2026-10-27T08:00:00Z',
  version: 3,
  suspensions: [],
};

const held: HeldPass = {
  holder: {
    id: 'customer-1',
    kind: 'ADULT',
    firstName: 'Katarzyna',
    lastName: 'Zielińska',
    displayName: 'Katarzyna Zielińska',
    email: 'katarzyna@example.com',
    emailOfGuardian: false,
  },
  pass,
};

const katarzyna = { name: 'Katarzyna Zielińska', role: 'CLIENT' as const };
const beata = { name: 'Beata Lis', role: 'STAFF' as const };

function session(overrides: Partial<PassHistorySession>): PassHistorySession {
  return {
    reservationId: 'reservation-1',
    eventId: 'event-1',
    title: 'Pole dance — podstawy',
    serviceTypeName: 'Pole dance - początkujący',
    startsAt: '2026-11-10T17:00:00Z',
    endsAt: '2026-11-10T18:00:00Z',
    eventCancelled: false,
    source: 'SELF',
    bookedBy: katarzyna,
    status: 'ACTIVE',
    cancelKind: null,
    cancelledAt: null,
    cancelledBy: null,
    attendance: 'PRESENT',
    ...overrides,
  };
}

function entry(overrides: Partial<PassHistoryEntry> & Pick<PassHistoryEntry, 'id' | 'kind'>) {
  return {
    category: 'ENTRIES',
    occurredAt: '2026-11-10T18:00:00Z',
    actor: beata,
    reason: null,
    entriesChange: null,
    balanceAfter: null,
    validUntilBefore: null,
    validUntilAfter: null,
    suspension: null,
    session: null,
    makeupCredit: null,
    ...overrides,
  } satisfies PassHistoryEntry;
}

const entries: PassHistoryEntry[] = [
  entry({
    id: 'pass-1',
    kind: 'RENEWAL_DECLINED',
    category: 'PASS',
    occurredAt: '2026-11-25T19:15:00Z',
    actor: null,
  }),
  entry({
    id: 'ledger-6',
    kind: 'ENTRY_USED',
    occurredAt: '2026-11-23T14:00:00Z',
    actor: katarzyna,
    entriesChange: 0,
    balanceAfter: 1,
    session: session({
      reservationId: 'reservation-3',
      title: 'Pole dance — figury',
      startsAt: '2026-11-23T17:00:00Z',
      endsAt: '2026-11-23T18:00:00Z',
      status: 'CANCELLED',
      cancelKind: 'LATE',
      cancelledAt: '2026-11-23T14:00:00Z',
      cancelledBy: katarzyna,
      attendance: 'UNMARKED',
    }),
  }),
  entry({
    id: 'ledger-5',
    kind: 'SUSPENSION_CANCELLED',
    category: 'VALIDITY',
    occurredAt: '2026-11-20T10:00:00Z',
    validUntilBefore: '2026-12-03',
    validUntilAfter: '2026-11-30',
    suspension: { startsOn: '2026-11-20', endsOn: '2026-11-22' },
  }),
  entry({
    id: 'ledger-4',
    kind: 'SUSPENDED',
    category: 'VALIDITY',
    occurredAt: '2026-11-16T08:00:00Z',
    reason: 'Kontuzja kolana',
    validUntilBefore: '2026-11-26',
    validUntilAfter: '2026-12-03',
    suspension: { startsOn: '2026-11-16', endsOn: '2026-11-22' },
  }),
  entry({
    id: 'ledger-3',
    kind: 'CORRECTED',
    category: 'CORRECTIONS',
    occurredAt: '2026-11-14T09:00:00Z',
    reason: 'Zwrot uzgodniony z klientką',
    entriesChange: 1,
    balanceAfter: 2,
  }),
  entry({
    id: 'credit-1',
    kind: 'MAKEUP_GRANTED',
    category: 'MAKEUP',
    occurredAt: '2026-11-05T08:00:00Z',
    actor: katarzyna,
    session: session({
      reservationId: 'reservation-2',
      title: 'Pole dance — figury',
      startsAt: '2026-11-06T17:00:00Z',
      endsAt: '2026-11-06T18:00:00Z',
      status: 'CANCELLED',
      cancelKind: 'IN_TIME',
      cancelledAt: '2026-11-05T08:00:00Z',
      cancelledBy: katarzyna,
      attendance: 'UNMARKED',
    }),
    makeupCredit: {
      id: 'credit-1',
      expiresAt: '2026-12-05T08:00:00Z',
      state: 'USED',
      usedAt: '2026-11-08T11:00:00Z',
      usedFor: session({
        reservationId: 'reservation-4',
        title: 'Pole dance — odrabianie',
        startsAt: '2026-11-10T16:00:00Z',
        endsAt: '2026-11-10T17:00:00Z',
      }),
    },
  }),
  entry({
    id: 'ledger-2',
    kind: 'ENTRY_RESERVED',
    occurredAt: '2026-10-29T19:00:00Z',
    entriesChange: -1,
    balanceAfter: 3,
    session: session({ source: 'STAFF', bookedBy: beata, attendance: 'ABSENT' }),
  }),
  entry({
    id: 'ledger-1',
    kind: 'ACTIVATED',
    category: 'PASS',
    occurredAt: '2026-10-27T08:00:00Z',
    reason: 'Płatność kartą w recepcji',
    entriesChange: 4,
    balanceAfter: 4,
    validUntilAfter: '2026-11-26',
  }),
];

const counts = { ENTRIES: 2, CORRECTIONS: 1, VALIDITY: 2, MAKEUP: 1, PASS: 2 };

function history(
  items: PassHistoryEntry[],
  ledgerBalance = 1,
  totalItems = items.length,
): PassHistory {
  return {
    pass: {
      id: pass.id,
      customerId: held.holder.id,
      productName: pass.productName,
      entriesTotal: 4,
      entriesRemaining: 1,
      ledgerBalance,
      purchased: false,
    },
    counts,
    entries: { items, page: 0, size: 20, totalItems, totalPages: Math.ceil(totalItems / 20) },
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function mockApi(respond: (url: URL) => Response) {
  const fetchMock = vi.fn((url: string) => {
    if (url.startsWith('/api/v1/studio')) {
      return Promise.resolve(json({ name: 'Studio', timeZone: 'Europe/Warsaw', currency: 'PLN' }));
    }
    if (url.includes('/history')) {
      return Promise.resolve(respond(new URL(url, 'http://localhost')));
    }
    if (url.startsWith('/api/v1/customer-passes')) {
      return Promise.resolve(
        json({ items: [held], page: 0, size: 20, totalItems: 1, totalPages: 1 }),
      );
    }
    return Promise.resolve(json({ items: [] }));
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

async function openHistory() {
  renderWithProviders(<PassesPage />);
  await userEvent.click(screen.getByRole('tab', { name: 'Karnety uczestników' }));
  await userEvent.type(screen.getByRole('textbox', { name: 'Szukaj uczestnika' }), 'Zielińska');
  const row = (await screen.findByText('Karnet Pole Dance — 4 wejścia')).closest('tr');
  await userEvent.click(within(row!).getByRole('button', { name: 'Historia' }));
  return screen.findByRole('dialog', { name: 'Historia karnetu' });
}

test('shows every change of the pass newest first with its class, author, note and balance', async () => {
  const fetchMock = mockApi(() => json(history(entries)));
  const drawer = await openHistory();

  expect(within(drawer).getByText('Katarzyna Zielińska')).toBeInTheDocument();
  expect(
    within(drawer).getByText('Saldo: 1 z 4 · Ważny 27.10.2026 – 06.12.2026'),
  ).toBeInTheDocument();
  const items = await within(drawer).findAllByRole('article');
  expect(items.map((item) => item.getAttribute('aria-label'))).toEqual([
    'Rezygnacja z odnowienia',
    'Wejście rozliczone jako wykorzystane',
    'Anulowanie zawieszenia',
    'Zawieszenie karnetu',
    'Korekta salda',
    'Przyznano odrabianie',
    'Rezerwacja zajęć',
    'Aktywacja karnetu',
  ]);
  expect(fetchMock).toHaveBeenCalledWith(
    '/api/v1/customers/customer-1/passes/pass-1/history?size=20',
    expect.anything(),
  );

  const [declined, used, cancelled, suspended, corrected, makeup, reserved, activated] = items;
  expect(within(declined).getByText(/Klient lub opiekun w aplikacji/)).toBeInTheDocument();
  expect(
    within(declined).getByText(/Przypomnienia o zakupie kolejnego są wyłączone/),
  ).toBeInTheDocument();

  expect(within(used).getByText('±0')).toBeInTheDocument();
  expect(within(used).getByText('saldo po: 1')).toBeInTheDocument();
  expect(
    within(used).getByText('Pole dance — figury · 23.11.2026 18:00–19:00'),
  ).toBeInTheDocument();
  expect(
    within(used).getByText(
      'Odwołane po terminie, przez: Katarzyna Zielińska · klient · 23.11.2026 15:00',
    ),
  ).toBeInTheDocument();
  expect(
    within(used).getByText('Wejście pobrano przy rezerwacji, saldo się nie zmienia.'),
  ).toBeInTheDocument();

  expect(
    within(cancelled).getByText('Anulowane dni zawieszenia: 20.11.2026 – 22.11.2026 (3 dni)'),
  ).toBeInTheDocument();
  expect(within(cancelled).getByText('Ważny do: 03.12.2026 → 30.11.2026')).toBeInTheDocument();
  expect(
    within(suspended).getByText('Zawieszony 16.11.2026 – 22.11.2026 (7 dni)'),
  ).toBeInTheDocument();
  expect(within(suspended).getByText('„Kontuzja kolana”')).toBeInTheDocument();

  expect(within(corrected).getByText('+1')).toBeInTheDocument();
  expect(within(corrected).getByText(/Beata Lis · studio/)).toBeInTheDocument();
  expect(within(corrected).getByText('„Zwrot uzgodniony z klientką”')).toBeInTheDocument();

  expect(
    within(makeup).getByText('Za zajęcia: Pole dance — figury · 06.11.2026 18:00–19:00'),
  ).toBeInTheDocument();
  expect(within(makeup).getByText('Wykorzystane')).toBeInTheDocument();
  expect(
    within(makeup).getByText('Wykorzystane na: Pole dance — odrabianie · 10.11.2026 17:00–18:00'),
  ).toBeInTheDocument();

  expect(within(reserved).getByText('−1')).toBeInTheDocument();
  expect(
    within(reserved).getByText('Zapis przez studio, przez: Beata Lis · studio'),
  ).toBeInTheDocument();
  expect(within(reserved).getByText('Nieobecność na zajęciach')).toBeInTheDocument();

  expect(
    within(activated).getByText('Przypisany przez studio, ważny do 26.11.2026'),
  ).toBeInTheDocument();
  expect(within(activated).getByText('saldo po: 4')).toBeInTheDocument();
  expect(within(drawer).queryByText(/nie zgadza|dają saldo/)).not.toBeInTheDocument();
});

test('filters the history by the kind of change and counts each kind', async () => {
  const fetchMock = mockApi((url) =>
    json(
      url.searchParams.get('category') === 'VALIDITY'
        ? history(entries.filter((item) => item.category === 'VALIDITY'))
        : history(entries),
    ),
  );
  const drawer = await openHistory();
  await within(drawer).findAllByRole('article');

  await userEvent.click(within(drawer).getByRole('radio', { name: 'Ważność i zawieszenia (2)' }));

  expect(fetchMock).toHaveBeenCalledWith(
    '/api/v1/customers/customer-1/passes/pass-1/history?size=20&category=VALIDITY',
    expect.anything(),
  );
  await vi.waitFor(() =>
    expect(
      within(drawer)
        .getAllByRole('article')
        .map((item) => item.getAttribute('aria-label')),
    ).toEqual(['Anulowanie zawieszenia', 'Zawieszenie karnetu']),
  );
  expect(within(drawer).getByRole('radio', { name: 'Wejścia na zajęcia (2)' })).not.toBeChecked();
});

test('warns when the changes do not add up to the balance and tells an empty history', async () => {
  mockApi(() => json(history([], 3)));
  const drawer = await openHistory();

  expect(
    await within(drawer).findByText(
      'Zmiany w historii dają saldo 3, a karnet ma 1. Część zmian mogła zostać wprowadzona, zanim historia była prowadzona.',
    ),
  ).toBeInTheDocument();
  expect(
    within(drawer).getByText('Ten karnet nie ma jeszcze zapisanych zmian.'),
  ).toBeInTheDocument();
});

test('pages through a long history', async () => {
  const fetchMock = mockApi((url) =>
    json(
      history(url.searchParams.get('page') === '1' ? entries.slice(7) : entries.slice(0, 7), 1, 27),
    ),
  );
  const drawer = await openHistory();
  await within(drawer).findAllByRole('article');
  expect(within(drawer).getByText('27 zmian')).toBeInTheDocument();

  await userEvent.click(within(drawer).getByRole('button', { name: '2' }));

  expect(fetchMock).toHaveBeenCalledWith(
    '/api/v1/customers/customer-1/passes/pass-1/history?size=20&page=1',
    expect.anything(),
  );
});

test('tells when the pass cannot be found', async () => {
  mockApi(() => json({ code: 'NOT_FOUND', title: 'Not found', status: 404 }, 404));
  const drawer = await openHistory();

  expect(
    await within(drawer).findByText(
      'Nie znaleziono tego karnetu uczestnika. Zamknij panel i odśwież listę.',
    ),
  ).toBeInTheDocument();
});
