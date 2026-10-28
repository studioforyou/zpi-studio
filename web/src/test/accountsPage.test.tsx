import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { AccountsPage } from '@/features/accounts/AccountsPage';
import { renderWithProviders } from './renderWithProviders';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Answers every request of the page; reports count `active` and `fresh` for any period. */
function stubApi(active = 12, fresh = 3) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url === '/api/v1/studio') {
      return json({ name: 'Studio', timeZone: 'Europe/Warsaw', currency: 'PLN' });
    }
    if (url.startsWith('/api/v1/reports/customer-activity?')) {
      const query = new URLSearchParams(url.split('?')[1]);
      return json({
        current: {
          from: query.get('from'),
          to: query.get('to'),
          activeCustomers: active,
          newCustomers: fresh,
        },
        previous: { from: '2026-08-27', to: '2026-09-25', activeCustomers: 8, newCustomers: 3 },
      });
    }
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function reportRequests(fetchMock: ReturnType<typeof stubApi>): string[] {
  return fetchMock.mock.calls
    .map(([url]) => url)
    .filter((url) => url.startsWith('/api/v1/reports/customer-activity'));
}

beforeEach(() => {
  // Only Date is faked, so user events and React Query keep their real timers.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-25T12:00:00Z'));
});

afterEach(() => {
  vi.useRealTimers();
});

test('shows active and new customers of the last 30 days against the 30 days before', async () => {
  const fetchMock = stubApi();
  renderWithProviders(<AccountsPage />);

  const active = await screen.findByRole('group', { name: 'Aktywni klienci' });
  expect(await within(active).findByText('12')).toBeInTheDocument();
  expect(within(active).getByText('▲ +4 (+50%)')).toBeInTheDocument();
  expect(within(active).getByText('poprzednio 8 (27.08.2026 – 25.09.2026)')).toBeInTheDocument();
  const fresh = screen.getByRole('group', { name: 'Nowi klienci' });
  expect(within(fresh).getByText('3')).toBeInTheDocument();
  expect(within(fresh).getByText('bez zmian')).toBeInTheDocument();
  expect(screen.getByText('26.09.2026 – 25.10.2026 (30 dni)')).toBeInTheDocument();
  expect(reportRequests(fetchMock)).toEqual([
    '/api/v1/reports/customer-activity?from=2026-09-26&to=2026-10-25',
  ]);
});

test('reports fewer customers as a decrease', async () => {
  stubApi(6);
  renderWithProviders(<AccountsPage />);

  const active = await screen.findByRole('group', { name: 'Aktywni klienci' });
  expect(await within(active).findByText('▼ −2 (−25%)')).toBeInTheDocument();
});

test('asks for the period of the chosen preset', async () => {
  const fetchMock = stubApi();
  renderWithProviders(<AccountsPage />);
  await screen.findByText('▲ +4 (+50%)');

  await userEvent.click(screen.getByText('Ten miesiąc'));

  await vi.waitFor(() =>
    expect(reportRequests(fetchMock)).toContain(
      '/api/v1/reports/customer-activity?from=2026-10-01&to=2026-10-25',
    ),
  );
  expect(await screen.findByText('01.10.2026 – 25.10.2026 (25 dni)')).toBeInTheDocument();
});

test('does not ask for a custom period that ends before it starts', async () => {
  const fetchMock = stubApi();
  renderWithProviders(<AccountsPage />);
  await screen.findByText('▲ +4 (+50%)');

  await userEvent.click(screen.getByText('Własny'));
  const from = screen.getByLabelText('Od');
  await userEvent.clear(from);
  await userEvent.type(from, '2026-11-01');

  expect(
    await screen.findByText('Data końcowa nie może być wcześniejsza niż początkowa.'),
  ).toBeInTheDocument();
  expect(reportRequests(fetchMock)).toHaveLength(1);
});
