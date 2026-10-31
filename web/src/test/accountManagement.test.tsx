import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { AccountDetailsPage } from '@/features/accounts/AccountDetailsPage';
import { AccountsList } from '@/features/accounts/AccountsList';
import type { AccountDetails } from '@/features/accounts/accountsApi';
import { renderWithProviders } from './renderWithProviders';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status < 300 ? 'application/json' : 'application/problem+json' },
  });
}

const kasia: AccountDetails = {
  id: 'a-kasia',
  email: 'kasia@example.com',
  status: 'ACTIVE',
  roles: ['CLIENT'],
  displayName: 'Katarzyna Zielińska',
  firstName: 'Katarzyna',
  lastName: 'Zielińska',
  phone: '+48600100101',
  createdAt: '2026-07-01T10:00:00Z',
  activatedAt: '2026-07-02T10:00:00Z',
  customerProfileId: 'p-kasia',
  staffProfileId: null,
  invitationExpiresAt: null,
  hasLogin: true,
};

const marek = {
  ...kasia,
  id: 'a-marek',
  email: 'marek@example.com',
  roles: ['TRAINER'],
  displayName: 'Marek Nowak',
  firstName: 'Marek',
  lastName: 'Nowak',
  phone: null,
};

type Handler = (url: string, init?: RequestInit) => Response | undefined;

/** Answers the account requests; `handler` may answer first, e.g. with an error. */
function stubApi(handler: Handler = () => undefined) {
  let current: AccountDetails = { ...kasia };
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const handled = handler(url, init);
    if (handled) {
      return handled;
    }
    const method = init?.method ?? 'GET';
    if (url.startsWith('/api/v1/accounts?')) {
      return json({ items: [kasia, marek], page: 0, size: 20, totalItems: 2, totalPages: 1 });
    }
    if (url === '/api/v1/accounts/a-kasia' && method === 'GET') {
      return json(current);
    }
    if (url === '/api/v1/accounts/a-kasia' && method === 'PUT') {
      const body = JSON.parse(String(init?.body));
      current = { ...current, ...body, displayName: `${body.firstName} ${body.lastName}` };
      return json(current);
    }
    if (url === '/api/v1/accounts/a-kasia/suspend') {
      current = { ...current, status: 'SUSPENDED' };
      return json(current);
    }
    if (url.startsWith('/api/v1/accounts/a-kasia') && method === 'DELETE') {
      return new Response(null, { status: 204 });
    }
    throw new Error(`Unexpected request: ${method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function calls(fetchMock: ReturnType<typeof stubApi>, method: string) {
  return fetchMock.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === method);
}

const routes = [
  { path: '/accounts', element: <AccountsList /> },
  { path: '/accounts/:accountId', element: <AccountDetailsPage /> },
];

function renderAt(path: string) {
  return renderWithProviders(null, { routes, initialEntry: path });
}

test('lists accounts with their roles and statuses and opens one', async () => {
  stubApi();
  renderAt('/accounts');

  const row = (await screen.findByText('Katarzyna Zielińska')).closest('tr')!;
  expect(within(row).getByText('kasia@example.com')).toBeInTheDocument();
  expect(within(row).getByText('Klient')).toBeInTheDocument();
  expect(within(row).getByText('Aktywne')).toBeInTheDocument();
  expect(screen.getByText('Znaleziono: 2')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Otwórz konto: Katarzyna Zielińska' }));

  expect(await screen.findByRole('heading', { name: 'Katarzyna Zielińska' })).toBeInTheDocument();
});

test('asks for the chosen role only', async () => {
  const fetchMock = stubApi();
  renderAt('/accounts');
  await screen.findByText('Marek Nowak');

  await userEvent.click(screen.getByText('Trenerzy'));

  await vi.waitFor(() =>
    expect(fetchMock.mock.calls.map(([url]) => url)).toContain(
      '/api/v1/accounts?page=0&size=20&role=TRAINER',
    ),
  );
});

test('saves changed personal details', async () => {
  const fetchMock = stubApi();
  renderAt('/accounts/a-kasia');
  const lastName = await screen.findByRole('textbox', { name: /^Nazwisko/ });
  const save = screen.getByRole('button', { name: 'Zapisz zmiany' });
  expect(save).toBeDisabled();

  await userEvent.clear(lastName);
  await userEvent.type(lastName, ' Nowa ');
  await userEvent.clear(screen.getByLabelText('Telefon'));
  await userEvent.click(save);

  expect(await screen.findByText('Zapisano zmiany.')).toBeInTheDocument();
  const [, init] = calls(fetchMock, 'PUT')[0];
  expect(JSON.parse(String(init?.body))).toEqual({
    email: 'kasia@example.com',
    firstName: 'Katarzyna',
    lastName: 'Nowa',
    phone: null,
  });
  expect(await screen.findByRole('heading', { name: 'Katarzyna Nowa' })).toBeInTheDocument();
});

test('marks an e-mail that belongs to another account', async () => {
  stubApi((_url, init) =>
    init?.method === 'PUT' ? json({ code: 'ACCOUNT_ALREADY_EXISTS', status: 409 }, 409) : undefined,
  );
  renderAt('/accounts/a-kasia');
  const email = await screen.findByLabelText('Adres e-mail', { exact: false });

  await userEvent.clear(email);
  await userEvent.type(email, 'marek@example.com');
  await userEvent.click(screen.getByRole('button', { name: 'Zapisz zmiany' }));

  expect(await screen.findByText('Ten adres ma już inne konto w studiu.')).toBeInTheDocument();
});

test('does not send an invalid phone number', async () => {
  const fetchMock = stubApi();
  renderAt('/accounts/a-kasia');
  const phone = await screen.findByLabelText('Telefon');

  await userEvent.type(phone, ' wew. 12');
  await userEvent.click(screen.getByRole('button', { name: 'Zapisz zmiany' }));

  expect(
    await screen.findByText('Telefon może zawierać tylko cyfry, spacje, +, - i nawiasy.'),
  ).toBeInTheDocument();
  expect(calls(fetchMock, 'PUT')).toHaveLength(0);
});

test('suspends an account after confirmation, with the reason', async () => {
  const fetchMock = stubApi();
  renderAt('/accounts/a-kasia');

  await userEvent.click(await screen.findByRole('button', { name: 'Zawieś konto' }));
  const dialog = await screen.findByRole('dialog', { name: 'Zawiesić konto?' });
  await userEvent.type(within(dialog).getByLabelText('Powód (opcjonalnie)'), 'Zaległe płatności');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zawieś konto' }));

  expect(await screen.findByRole('button', { name: 'Przywróć dostęp' })).toBeInTheDocument();
  expect(screen.getByText('Zawieszone')).toBeInTheDocument();
  const [url, init] = calls(fetchMock, 'POST')[0];
  expect(url).toBe('/api/v1/accounts/a-kasia/suspend');
  expect(JSON.parse(String(init?.body))).toEqual({ reason: 'Zaległe płatności' });
});

test('closes an account only after its name is typed in', async () => {
  const fetchMock = stubApi();
  renderAt('/accounts/a-kasia');

  await userEvent.click(await screen.findByRole('button', { name: 'Usuń konto' }));
  const dialog = await screen.findByRole('dialog', { name: 'Usunąć konto?' });
  const submit = within(dialog).getByRole('button', { name: 'Usuń na stałe' });
  expect(submit).toBeDisabled();

  await userEvent.type(
    within(dialog).getByLabelText('Aby potwierdzić, wpisz imię i nazwisko'),
    'katarzyna zielińska',
  );
  await userEvent.click(submit);

  // Back on the list.
  expect(await screen.findByText('Znaleziono: 2')).toBeInTheDocument();
  expect(calls(fetchMock, 'DELETE').map(([url]) => url)).toEqual(['/api/v1/accounts/a-kasia']);
});

test('explains that the account does not exist', async () => {
  stubApi((url) =>
    url === '/api/v1/accounts/missing' ? json({ code: 'NOT_FOUND', status: 404 }, 404) : undefined,
  );
  renderAt('/accounts/missing');

  expect(await screen.findByText('To konto nie istnieje.')).toBeInTheDocument();
});
