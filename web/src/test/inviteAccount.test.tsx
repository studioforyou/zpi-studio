import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { AccountsPage } from '@/features/accounts/AccountsPage';
import { pl } from '@/shared/i18n/pl';
import { renderWithProviders } from './renderWithProviders';

const t = pl.accounts.invite;

function jsonResponse(status: number, body: unknown, contentType = 'application/json') {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

function problem(status: number, code: string) {
  return jsonResponse(status, { status, code }, 'application/problem+json');
}

/**
 * Answers the invitation with `invitation` and the new administrator account with `adminAccount`;
 * the page's other requests get empty data.
 */
function stubFetch(invitation: () => Response, adminAccount?: () => Response) {
  const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
    if (url === '/api/v1/invitations') {
      return invitation();
    }
    if (url === '/api/v1/admin-accounts' && adminAccount) {
      return adminAccount();
    }
    if (url === '/api/v1/studio') {
      return jsonResponse(200, { name: 'Studio', timeZone: 'Europe/Warsaw', currency: 'PLN' });
    }
    if (url.startsWith('/api/v1/reports/customer-activity')) {
      const counts = { from: '2026-10-01', to: '2026-10-30', activeCustomers: 0, newCustomers: 0 };
      return jsonResponse(200, { current: counts, previous: counts });
    }
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function invitationRequests(fetchMock: ReturnType<typeof stubFetch>) {
  return fetchMock.mock.calls.filter(([url]) => url === '/api/v1/invitations');
}

async function openAndFill(role?: string) {
  renderWithProviders(<AccountsPage />);
  await userEvent.click(screen.getByRole('button', { name: pl.accounts.inviteButton }));
  const dialog = await screen.findByRole('dialog');
  if (role) {
    await userEvent.click(within(dialog).getByText(role));
  }
  await userEvent.type(within(dialog).getByLabelText(t.firstName, { exact: false }), ' Jan ');
  await userEvent.type(within(dialog).getByLabelText(t.lastName, { exact: false }), 'Testowy');
  await userEvent.type(
    within(dialog).getByLabelText(t.email, { exact: false }),
    'jan.testowy@gmail.com',
  );
  await userEvent.click(within(dialog).getByRole('button', { name: t.submit }));
  return dialog;
}

test('sends the invitation and confirms it', async () => {
  const fetchMock = stubFetch(() =>
    jsonResponse(201, {
      invitationId: 'i1',
      accountId: 'a1',
      email: 'jan.testowy@gmail.com',
      expiresAt: '2026-10-30T12:00:00Z',
    }),
  );

  const dialog = await openAndFill(pl.accounts.roles.TRAINER);

  expect(await within(dialog).findByText(t.sentTitle)).toBeInTheDocument();
  expect(within(dialog).getByText(/Jan Testowy dostanie e-mail/)).toHaveTextContent('30.10.2026');
  const [[, init]] = invitationRequests(fetchMock);
  expect(init?.method).toBe('POST');
  expect(JSON.parse(init?.body as string)).toEqual({
    email: 'jan.testowy@gmail.com',
    firstName: 'Jan',
    lastName: 'Testowy',
    role: 'TRAINER',
  });
});

test('validates the form before sending', async () => {
  const fetchMock = stubFetch(() => jsonResponse(201, {}));
  renderWithProviders(<AccountsPage />);
  await userEvent.click(screen.getByRole('button', { name: pl.accounts.inviteButton }));
  const dialog = await screen.findByRole('dialog');

  await userEvent.type(within(dialog).getByLabelText(t.email, { exact: false }), 'jan@');
  await userEvent.click(within(dialog).getByRole('button', { name: t.submit }));

  expect(within(dialog).getAllByText(pl.auth.fieldRequired)).toHaveLength(2);
  expect(within(dialog).getByText(t.invalidEmail)).toBeInTheDocument();
  expect(invitationRequests(fetchMock)).toHaveLength(0);
});

test('marks the e-mail field when the address already has an account', async () => {
  stubFetch(() => problem(409, 'ACCOUNT_ALREADY_EXISTS'));

  const dialog = await openAndFill();

  expect(await within(dialog).findByText(t.errors.accountExists)).toBeInTheDocument();
  expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
});

test('explains that the e-mail could not be sent', async () => {
  stubFetch(() => problem(502, 'EMAIL_NOT_SENT'));

  const dialog = await openAndFill();

  expect(await within(dialog).findByRole('alert')).toHaveTextContent(t.errors.emailNotSent);
});

test('starts a fresh form for the next person', async () => {
  stubFetch(() =>
    jsonResponse(201, {
      invitationId: 'i1',
      accountId: 'a1',
      email: 'jan.testowy@gmail.com',
      expiresAt: '2026-10-30T12:00:00Z',
    }),
  );

  const dialog = await openAndFill();
  await userEvent.click(await within(dialog).findByRole('button', { name: t.inviteAnother }));

  expect(within(dialog).getByLabelText(t.firstName, { exact: false })).toHaveValue('');
  expect(within(dialog).getByLabelText(t.email, { exact: false })).toHaveValue('');
});

async function fillAdmin() {
  renderWithProviders(<AccountsPage />);
  await userEvent.click(screen.getByRole('button', { name: pl.accounts.inviteButton }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByText(pl.accounts.roles.ADMIN));
  await userEvent.type(within(dialog).getByLabelText(t.firstName, { exact: false }), 'Ola');
  await userEvent.type(within(dialog).getByLabelText(t.lastName, { exact: false }), 'Nowak');
  await userEvent.type(within(dialog).getByLabelText(t.email, { exact: false }), 'ola@gmail.com');
  await userEvent.click(within(dialog).getByRole('button', { name: t.createAdmin }));
  return dialog;
}

function adminRequests(fetchMock: ReturnType<typeof stubFetch>) {
  return fetchMock.mock.calls.filter(([url]) => url === '/api/v1/admin-accounts');
}

test('creates an administrator account and shows the generated password once', async () => {
  const fetchMock = stubFetch(
    () => jsonResponse(201, {}),
    () =>
      jsonResponse(201, {
        accountId: 'a1',
        staffProfileId: 's1',
        email: 'ola@gmail.com',
        password: 'Xk7#pQ2m!Rt9vW4z',
      }),
  );

  const dialog = await fillAdmin();

  expect(await within(dialog).findByText(t.createdTitle)).toBeInTheDocument();
  expect(within(dialog).getByText(t.created('Ola Nowak', 'ola@gmail.com'))).toBeInTheDocument();
  expect(within(dialog).getByLabelText(t.generatedPassword)).toHaveTextContent('Xk7#pQ2m!Rt9vW4z');
  expect(within(dialog).getByRole('button', { name: t.copy })).toBeInTheDocument();
  const [[, init]] = adminRequests(fetchMock);
  expect(JSON.parse(init?.body as string)).toEqual({
    email: 'ola@gmail.com',
    firstName: 'Ola',
    lastName: 'Nowak',
  });
  expect(invitationRequests(fetchMock)).toHaveLength(0);
});

test('marks the e-mail field when an administrator account cannot use the address', async () => {
  stubFetch(
    () => jsonResponse(201, {}),
    () => problem(409, 'ACCOUNT_ALREADY_EXISTS'),
  );

  const dialog = await fillAdmin();

  expect(await within(dialog).findByText(t.errors.accountExists)).toBeInTheDocument();
});
