import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { AccountDetailsPage } from '@/features/accounts/AccountDetailsPage';
import { AccountsList } from '@/features/accounts/AccountsList';
import type { AccountDetails, AccountProfile } from '@/features/accounts/accountsApi';
import { renderWithProviders } from './renderWithProviders';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status < 300 ? 'application/json' : 'application/problem+json' },
  });
}

const kasia: AccountDetails = {
  id: 'a-kasia',
  profileId: 'p-kasia',
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
  profiles: [profile('p-kasia', 'CLIENT', 'Katarzyna', 'Zielińska', { phone: '+48600100101' })],
};

const marek = {
  ...kasia,
  id: 'a-marek',
  profileId: 'p-marek',
  email: 'marek@example.com',
  roles: ['TRAINER'],
  displayName: 'Marek Nowak',
  firstName: 'Marek',
  lastName: 'Nowak',
  phone: null,
};

/** An account whose owner is a client and a trainer, with a child. */
const ola: AccountDetails = {
  ...kasia,
  id: 'a-ola',
  profileId: 'p-ola',
  email: 'ola@example.com',
  roles: ['CLIENT', 'TRAINER'],
  displayName: 'Ola Wiśniewska',
  firstName: 'Ola',
  lastName: 'Wiśniewska',
  phone: '+48500200200',
  profiles: [
    profile('p-ola', 'CLIENT', 'Ola', 'Wiśniewska', { phone: '+48500200200' }),
    profile('s-ola', 'STAFF', 'Ola', 'Wiśniewska', { phone: '+48500100100', bio: 'Joga' }),
    profile('p-stas', 'CHILD', 'Staś', 'Wiśniewski', { birthDate: '2016-04-01' }),
  ],
};

const stas = {
  ...ola,
  profileId: 'p-stas',
  roles: ['CHILD'],
  displayName: 'Staś Wiśniewski',
  firstName: 'Staś',
  lastName: 'Wiśniewski',
  phone: null,
};

function profile(
  id: string,
  kind: AccountProfile['kind'],
  firstName: string,
  lastName: string,
  details: Partial<AccountProfile> = {},
): AccountProfile {
  return {
    id,
    kind,
    firstName,
    lastName,
    phone: null,
    bio: null,
    birthDate: null,
    emergencyContactName: null,
    emergencyPhone: null,
    ...details,
  };
}

type Handler = (url: string, init?: RequestInit) => Response | undefined;

/** Answers the account requests; `handler` may answer first, e.g. with an error. */
function stubApi(handler: Handler = () => undefined) {
  let current: AccountDetails = { ...kasia };
  let currentOla: AccountDetails = { ...ola };
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const handled = handler(url, init);
    if (handled) {
      return handled;
    }
    const method = init?.method ?? 'GET';
    if (url.startsWith('/api/v1/accounts?')) {
      return json({ items: [kasia, marek], page: 0, size: 20, totalItems: 2, totalPages: 1 });
    }
    if (url === '/api/v1/accounts/a-ola' && method === 'GET') {
      return json(currentOla);
    }
    if (url.startsWith('/api/v1/accounts/a-ola/') && method === 'PUT') {
      const body = JSON.parse(String(init?.body));
      const id = url.endsWith('/staff-profile') ? 's-ola' : url.split('/').pop();
      currentOla = {
        ...currentOla,
        profiles: currentOla.profiles.map((other) =>
          other.id === id ? { ...other, ...body } : other,
        ),
      };
      return json(currentOla);
    }
    if (url.startsWith('/api/v1/accounts/a-ola') && method !== 'GET') {
      return json(ola);
    }
    if (url === '/api/v1/accounts/a-kasia' && method === 'GET') {
      return json(current);
    }
    if (url === '/api/v1/accounts/a-kasia' && method === 'PUT') {
      const body = JSON.parse(String(init?.body));
      current = {
        ...current,
        ...body,
        displayName: `${body.firstName} ${body.lastName}`,
        profiles: [{ ...current.profiles[0], ...body }],
      };
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

test('lists each person and opens the account at their profile', async () => {
  stubApi((url) =>
    url.startsWith('/api/v1/accounts?')
      ? json({ items: [ola, stas], page: 0, size: 20, totalItems: 2, totalPages: 1 })
      : undefined,
  );
  renderAt('/accounts');

  const childRow = (await screen.findByText('Staś Wiśniewski')).closest('tr')!;
  expect(within(childRow).getByText('Dziecko')).toBeInTheDocument();
  expect(within(childRow).getByText('ola@example.com')).toBeInTheDocument();
  expect(within(childRow).getByText('—')).toBeInTheDocument();
  const ownerRow = screen.getByText('Ola Wiśniewska').closest('tr')!;
  expect(within(ownerRow).getByText('Klient')).toBeInTheDocument();
  expect(within(ownerRow).getByText('Trener')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Otwórz konto: Staś Wiśniewski' }));

  expect(await screen.findByRole('heading', { name: 'Staś Wiśniewski' })).toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: 'Kontakt awaryjny' })).toBeInTheDocument();
});

test('shows no profile bar for an account with one profile', async () => {
  stubApi();
  renderAt('/accounts/a-kasia');

  await screen.findByRole('heading', { name: 'Katarzyna Zielińska' });
  expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument();
});

test('switches between the profiles of an account', async () => {
  stubApi();
  renderAt('/accounts/a-ola');

  // The client profile by default, with the login e-mail.
  expect(await screen.findByLabelText('Adres e-mail', { exact: false })).toHaveValue(
    'ola@example.com',
  );
  expect(screen.getByText('Login')).toBeInTheDocument();

  await userEvent.click(screen.getByText('Trener', { selector: 'p' }));
  expect(await screen.findByLabelText('Opis widoczny dla klientów')).toHaveValue('Joga');
  expect(screen.queryByLabelText('Adres e-mail', { exact: false })).not.toBeInTheDocument();

  await userEvent.click(screen.getByText('Dziecko', { selector: 'p' }));
  expect(await screen.findByRole('heading', { name: 'Staś Wiśniewski' })).toBeInTheDocument();
  expect(screen.getByLabelText('Data urodzenia')).toHaveValue('2016-04-01');
  expect(screen.getByRole('tab', { name: 'Role i uprawnienia' })).toBeDisabled();
});

test('saves the details of a child', async () => {
  const fetchMock = stubApi();
  renderAt('/accounts/a-ola?profile=p-stas');
  const firstName = await screen.findByRole('textbox', { name: /^Imię/ });

  await userEvent.clear(firstName);
  await userEvent.type(firstName, 'Stanisław');
  await userEvent.type(screen.getByRole('textbox', { name: 'Kontakt awaryjny' }), 'Jan Wiśniewski');
  await userEvent.type(
    screen.getByRole('textbox', { name: 'Telefon awaryjny' }),
    '+48 500 400 400',
  );
  await userEvent.click(screen.getByRole('button', { name: 'Zapisz zmiany' }));

  expect(await screen.findByText('Zapisano zmiany.')).toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  const [url, init] = calls(fetchMock, 'PUT')[0];
  expect(url).toBe('/api/v1/accounts/a-ola/children/p-stas');
  expect(JSON.parse(String(init?.body))).toEqual({
    firstName: 'Stanisław',
    lastName: 'Wiśniewski',
    birthDate: '2016-04-01',
    emergencyContactName: 'Jan Wiśniewski',
    emergencyPhone: '+48 500 400 400',
  });
});

test('asks before a new name goes to both profiles of a person', async () => {
  const fetchMock = stubApi();
  renderAt('/accounts/a-ola?profile=s-ola');
  const lastName = await screen.findByRole('textbox', { name: /^Nazwisko/ });

  await userEvent.clear(lastName);
  await userEvent.type(lastName, 'Nowak');
  await userEvent.click(screen.getByRole('button', { name: 'Zapisz zmiany' }));
  const dialog = await screen.findByRole('dialog', { name: 'Zmiana na obu profilach' });
  expect(
    within(dialog).getByText('Nowe nazwisko zostanie zapisane również w profilu klienta.'),
  ).toBeInTheDocument();

  // Closing the dialog cancels the save.
  await userEvent.keyboard('{Escape}');
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(calls(fetchMock, 'PUT')).toHaveLength(0);

  await userEvent.click(screen.getByRole('button', { name: 'Zapisz zmiany' }));
  await userEvent.click(
    within(await screen.findByRole('dialog', { name: 'Zmiana na obu profilach' })).getByRole(
      'button',
      { name: 'OK' },
    ),
  );

  await vi.waitFor(() => expect(calls(fetchMock, 'PUT')).toHaveLength(1));
  const [url, init] = calls(fetchMock, 'PUT')[0];
  expect(url).toBe('/api/v1/accounts/a-ola/staff-profile');
  expect(JSON.parse(String(init?.body))).toEqual({
    firstName: 'Ola',
    lastName: 'Nowak',
    phone: '+48500100100',
    bio: 'Joga',
  });
});

test('names the other profiles a suspension affects', async () => {
  stubApi();
  renderAt('/accounts/a-ola?profile=p-stas');

  await userEvent.click(await screen.findByRole('button', { name: 'Zawieś konto' }));
  const dialog = await screen.findByRole('dialog', { name: 'Zawiesić konto?' });

  expect(
    within(dialog).getByText('Z tym kontem powiązane są też inne profile.'),
  ).toBeInTheDocument();
  expect(within(dialog).getByText('Ola Wiśniewska (klient)')).toBeInTheDocument();
  expect(within(dialog).getByText('Ola Wiśniewska (trener)')).toBeInTheDocument();
  expect(within(dialog).queryByText('Staś Wiśniewski (dziecko)')).not.toBeInTheDocument();
});

test('shows the roles and permissions of the account in its tab', async () => {
  stubApi((url) => {
    if (url === '/api/v1/roles') {
      return json({
        items: [
          { code: 'CLIENT', displayName: 'Klient', permissions: [] },
          { code: 'TRAINER', displayName: 'Trener', permissions: [] },
          { code: 'ADMIN', displayName: 'Administrator', permissions: [] },
        ],
      });
    }
    if (url === '/api/v1/permissions') {
      return json({ items: [] });
    }
    if (url === '/api/v1/accounts/a-kasia/roles') {
      return json({
        items: [
          {
            code: 'CLIENT',
            displayName: 'Klient',
            grantedBy: null,
            grantedAt: '2026-07-01T10:00:00Z',
          },
        ],
      });
    }
    if (url === '/api/v1/accounts/a-kasia/permission-overrides') {
      return json({ items: [] });
    }
    return undefined;
  });
  renderAt('/accounts/a-kasia');

  await userEvent.click(await screen.findByRole('tab', { name: 'Role i uprawnienia' }));

  expect(await screen.findByRole('switch', { name: 'Klient' })).toBeChecked();
  expect(screen.getByRole('switch', { name: 'Trener' })).not.toBeChecked();
});
