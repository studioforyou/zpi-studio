import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import type { AccountHistoryEntry } from '@/features/accounts/accountHistoryApi';
import { AccountHistoryPage } from '@/features/accounts/AccountHistoryPage';
import { AccountHistoryPanel } from '@/features/accounts/AccountHistoryPanel';
import { describeEntry } from '@/features/accounts/accountHistoryText';
import { renderWithProviders } from './renderWithProviders';

const beata = { accountId: 'a-beata', displayName: 'Beata Lis' };
const julia = { accountId: 'a-julia', displayName: 'Julia Kamińska' };

function entry(fields: Partial<AccountHistoryEntry>): AccountHistoryEntry {
  return {
    id: 'audit-1',
    occurredAt: '2026-10-02T21:30:00Z',
    type: 'STATUS_CHANGED',
    account: julia,
    actor: beata,
    fromStatus: null,
    toStatus: null,
    role: null,
    permission: null,
    effect: null,
    reason: null,
    ...fields,
  };
}

const juliaHistory = [
  entry({
    id: 'status-3',
    occurredAt: '2026-10-05T08:00:00Z',
    fromStatus: 'ACTIVE',
    toStatus: 'SUSPENDED',
    reason: 'Zaległa płatność',
  }),
  entry({
    id: 'audit-2',
    occurredAt: '2026-10-04T08:00:00Z',
    type: 'PERMISSION_OVERRIDE_ADDED',
    permission: 'PASS_ADJUST',
    effect: 'GRANT',
  }),
  entry({
    id: 'status-2',
    occurredAt: '2026-10-02T21:30:00Z',
    fromStatus: 'INVITED',
    toStatus: 'ACTIVE',
    actor: julia,
  }),
  entry({
    id: 'audit-1',
    occurredAt: '2026-10-01T10:00:00Z',
    type: 'ROLE_GRANTED',
    role: 'CLIENT',
  }),
  entry({ id: 'status-1', occurredAt: '2026-10-01T10:00:00Z', toStatus: 'INVITED', actor: null }),
];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status < 300 ? 'application/json' : 'application/problem+json' },
  });
}

function page(items: AccountHistoryEntry[], totalItems = items.length) {
  return { items, page: 0, size: 20, totalItems, totalPages: Math.ceil(totalItems / 20) };
}

/** Answers the history with `history` for every search; records the searches. */
function stubApi(history: (params: URLSearchParams) => AccountHistoryEntry[]) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url === '/api/v1/studio') {
      return json({ name: 'Studio', timeZone: 'Europe/Warsaw', currency: 'PLN' });
    }
    if (url === '/api/v1/account-history/actors') {
      return json({ items: [beata, { accountId: 'a-anna', displayName: 'Anna Kowalska' }] });
    }
    if (url.startsWith('/api/v1/account-history?')) {
      return json(page(history(new URL(url, 'http://test').searchParams)));
    }
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function searches(fetchMock: ReturnType<typeof stubApi>): URLSearchParams[] {
  return fetchMock.mock.calls
    .map(([url]) => url)
    .filter((url) => url.startsWith('/api/v1/account-history?'))
    .map((url) => new URL(url, 'http://test').searchParams);
}

function renderHistoryPage() {
  return renderWithProviders(null, {
    routes: [{ path: '/accounts/history', element: <AccountHistoryPage /> }],
    initialEntry: '/accounts/history',
  });
}

function row(text: string) {
  return screen.getByText(text).closest('tr')!;
}

test('describes every kind of change', () => {
  expect(describeEntry(entry({ toStatus: 'INVITED' }))).toEqual({
    title: 'Utworzono konto i wysłano zaproszenie',
    detail: 'Status: Zaproszone',
  });
  expect(describeEntry(entry({ toStatus: 'ACTIVE' })).title).toBe('Utworzono konto');
  expect(describeEntry(entry({ fromStatus: 'INVITED', toStatus: 'ACTIVE' }))).toEqual({
    title: 'Aktywowano konto (przyjęto zaproszenie)',
    detail: 'Zaproszone → Aktywne',
  });
  expect(describeEntry(entry({ fromStatus: 'PENDING_APPROVAL', toStatus: 'ACTIVE' })).title).toBe(
    'Zatwierdzono rejestrację',
  );
  expect(describeEntry(entry({ fromStatus: 'SUSPENDED', toStatus: 'ACTIVE' })).title).toBe(
    'Przywrócono dostęp',
  );
  expect(describeEntry(entry({ fromStatus: 'ACTIVE', toStatus: 'SUSPENDED' })).title).toBe(
    'Zawieszono konto',
  );
  expect(describeEntry(entry({ fromStatus: 'SUSPENDED', toStatus: 'CLOSED' }))).toEqual({
    title: 'Usunięto konto',
    detail: 'Zawieszone → Usunięte',
  });
  expect(describeEntry(entry({ type: 'ROLE_REVOKED', role: 'TRAINER' }))).toEqual({
    title: 'Odebrano rolę',
    detail: 'Trener',
  });
  expect(
    describeEntry(
      entry({
        type: 'PERMISSION_OVERRIDE_ADDED',
        permission: 'ATTENDANCE_CORRECT',
        effect: 'REVOKE',
      }),
    ),
  ).toEqual({ title: 'Odebrano uprawnienie', detail: 'Korekta obecności po zajęciach' });
  expect(
    describeEntry(
      entry({ type: 'PERMISSION_OVERRIDE_REMOVED', permission: 'PASS_ADJUST', effect: 'GRANT' }),
    ),
  ).toEqual({
    title: 'Przywrócono domyślne ustawienie uprawnienia',
    detail: 'Ręczna korekta karnetów i wejść (wcześniej nadane indywidualnie)',
  });
});

test("shows an account's changes with who made them and when", async () => {
  const fetchMock = stubApi(() => juliaHistory);
  renderWithProviders(<AccountHistoryPanel accountId="a-julia" />);

  const suspended = (await screen.findByText('Zawieszono konto')).closest('tr')!;
  expect(within(suspended).getByText('05.10.2026 10:00')).toBeInTheDocument();
  expect(within(suspended).getByText('Beata Lis')).toBeInTheDocument();
  expect(within(suspended).getByText('Zaległa płatność')).toBeInTheDocument();
  // Studio time: 21:30 UTC is 23:30 in Warsaw.
  const activated = row('Aktywowano konto (przyjęto zaproszenie)');
  expect(within(activated).getByText('02.10.2026 23:30')).toBeInTheDocument();
  expect(within(activated).getByText('Julia Kamińska')).toBeInTheDocument();
  expect(within(activated).getByText('samodzielnie')).toBeInTheDocument();
  expect(
    within(row('Nadano uprawnienie')).getByText('Ręczna korekta karnetów i wejść'),
  ).toBeInTheDocument();
  expect(within(row('Nadano rolę')).getByText('Klient')).toBeInTheDocument();
  expect(
    within(row('Utworzono konto i wysłano zaproszenie')).getByText('System'),
  ).toBeInTheDocument();
  // The account's own tab does not repeat which account changed.
  expect(screen.queryByRole('columnheader', { name: 'Konto' })).not.toBeInTheDocument();
  expect(screen.getByText('Zmian: 5')).toBeInTheDocument();

  expect(searches(fetchMock)[0].get('accountId')).toBe('a-julia');
});

test('says when an account has no changes yet', async () => {
  stubApi(() => []);
  renderWithProviders(<AccountHistoryPanel accountId="a-julia" />);

  expect(await screen.findByText('Brak zapisanych zmian tego konta.')).toBeInTheDocument();
});

test('filters the changes of every account', async () => {
  const fetchMock = stubApi((params) =>
    params.getAll('type').includes('ROLE_GRANTED') ? [juliaHistory[3]] : juliaHistory,
  );
  renderHistoryPage();

  const suspended = (await screen.findByText('Zawieszono konto')).closest('tr')!;
  expect(within(suspended).getByRole('link', { name: 'Julia Kamińska' })).toHaveAttribute(
    'href',
    '/accounts/a-julia',
  );

  await userEvent.click(screen.getByRole('radio', { name: 'Role' }));
  await waitFor(() => expect(screen.queryByText('Zawieszono konto')).not.toBeInTheDocument());
  expect(searches(fetchMock).at(-1)!.getAll('type')).toEqual(['ROLE_GRANTED', 'ROLE_REVOKED']);

  await userEvent.click(screen.getByRole('combobox', { name: 'Kto zmienił' }));
  await userEvent.click(await screen.findByRole('option', { name: 'Beata Lis', hidden: true }));
  await waitFor(() => expect(searches(fetchMock).at(-1)!.get('actorId')).toBe('a-beata'));

  await userEvent.type(screen.getByRole('textbox', { name: 'Konto' }), 'Julia');
  await waitFor(() => expect(searches(fetchMock).at(-1)!.get('query')).toBe('Julia'));
  const last = searches(fetchMock).at(-1)!;
  expect(last.get('page')).toBe('0');
  expect(last.get('actorId')).toBe('a-beata');
});

test('does not search a period that ends before it starts', async () => {
  const fetchMock = stubApi(() => juliaHistory);
  renderHistoryPage();
  await screen.findByText('Zawieszono konto');
  const before = searches(fetchMock).length;

  const from = screen.getByLabelText('Od');
  const to = screen.getByLabelText('Do');
  await userEvent.type(from, '2026-10-05');
  await userEvent.type(to, '2026-10-01');

  expect(
    await screen.findByText('Data „do” nie może być wcześniejsza niż „od”.'),
  ).toBeInTheDocument();
  expect(screen.queryByText('Zawieszono konto')).not.toBeInTheDocument();
  const sent = searches(fetchMock).slice(before);
  expect(sent.every((params) => !(params.get('from') === '2026-10-05' && params.get('to')))).toBe(
    true,
  );
});
