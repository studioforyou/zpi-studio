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
  const fetchMock = vi.fn().mockResolvedValue(
    jsonResponse(201, {
      invitationId: 'i1',
      accountId: 'a1',
      email: 'jan.testowy@gmail.com',
      expiresAt: '2026-10-30T12:00:00Z',
    }),
  );
  vi.stubGlobal('fetch', fetchMock);

  const dialog = await openAndFill(pl.accounts.roles.TRAINER);

  expect(await within(dialog).findByText(t.sentTitle)).toBeInTheDocument();
  expect(within(dialog).getByText(/Jan Testowy dostanie e-mail/)).toHaveTextContent('30.10.2026');
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
  expect(url).toBe('/api/v1/invitations');
  expect(init.method).toBe('POST');
  expect(JSON.parse(init.body as string)).toEqual({
    email: 'jan.testowy@gmail.com',
    firstName: 'Jan',
    lastName: 'Testowy',
    role: 'TRAINER',
  });
});

test('validates the form before sending', async () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  renderWithProviders(<AccountsPage />);
  await userEvent.click(screen.getByRole('button', { name: pl.accounts.inviteButton }));
  const dialog = await screen.findByRole('dialog');

  await userEvent.type(within(dialog).getByLabelText(t.email, { exact: false }), 'jan@');
  await userEvent.click(within(dialog).getByRole('button', { name: t.submit }));

  expect(within(dialog).getAllByText(pl.auth.fieldRequired)).toHaveLength(2);
  expect(within(dialog).getByText(t.invalidEmail)).toBeInTheDocument();
  expect(fetchMock).not.toHaveBeenCalled();
});

test('marks the e-mail field when the address already has an account', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problem(409, 'ACCOUNT_ALREADY_EXISTS')));

  const dialog = await openAndFill();

  expect(await within(dialog).findByText(t.errors.accountExists)).toBeInTheDocument();
  expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
});

test('explains that the e-mail could not be sent', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problem(502, 'EMAIL_NOT_SENT')));

  const dialog = await openAndFill();

  expect(await within(dialog).findByRole('alert')).toHaveTextContent(t.errors.emailNotSent);
});

test('starts a fresh form for the next person', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      jsonResponse(201, {
        invitationId: 'i1',
        accountId: 'a1',
        email: 'jan.testowy@gmail.com',
        expiresAt: '2026-10-30T12:00:00Z',
      }),
    ),
  );

  const dialog = await openAndFill();
  await userEvent.click(await within(dialog).findByRole('button', { name: t.inviteAnother }));

  expect(within(dialog).getByLabelText(t.firstName, { exact: false })).toHaveValue('');
  expect(within(dialog).getByLabelText(t.email, { exact: false })).toHaveValue('');
});
