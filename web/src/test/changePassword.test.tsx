import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { appRoutes } from '@/app/router';
import { PasswordChangeFailure } from '@/features/auth/authFailure';
import { authService } from '@/features/auth/authService';
import type { Session } from '@/features/auth/types';
import { pl } from '@/shared/i18n/pl';
import { renderWithProviders } from './renderWithProviders';

const texts = pl.auth.changePassword;

const admin: Session = {
  accountId: 'a1',
  email: 'admin@example.com',
  displayName: 'Beata Lis',
  roles: ['ADMIN'],
};

async function openChangePassword() {
  vi.mocked(authService.restore).mockResolvedValue(admin);
  // The schedule page behind the layout is not under test.
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('{}', { status: 500 })),
  );
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/rooms' });

  await userEvent.click(await screen.findByRole('button', { name: /Beata Lis/ }));
  await userEvent.click(await screen.findByRole('menuitem', { name: texts.menuItem }));
  return screen.findByRole('dialog');
}

async function fill(dialog: HTMLElement, current: string, next: string, repeat = next) {
  if (current) {
    await userEvent.type(within(dialog).getByLabelText(texts.current), current);
  }
  await userEvent.type(within(dialog).getByLabelText(texts.new), next);
  await userEvent.type(within(dialog).getByLabelText(texts.repeat), repeat);
  await userEvent.click(within(dialog).getByRole('button', { name: texts.submit }));
}

test('shows the e-mail and the logout in the account menu', async () => {
  vi.mocked(authService.restore).mockResolvedValue(admin);
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('{}', { status: 500 })),
  );
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/rooms' });

  await userEvent.click(await screen.findByRole('button', { name: /Beata Lis/ }));
  await userEvent.click(await screen.findByRole('menuitem', { name: pl.auth.logout }));

  expect(await screen.findByRole('heading', { name: pl.auth.loginTitle })).toBeInTheDocument();
  expect(authService.logout).toHaveBeenCalled();
});

test('changes the password from the account menu', async () => {
  const dialog = await openChangePassword();

  await fill(dialog, 'stare-haslo', 'nowe-haslo-123');

  expect(await within(dialog).findByRole('status')).toHaveTextContent(texts.done);
  expect(authService.changePassword).toHaveBeenCalledWith('stare-haslo', 'nowe-haslo-123');
});

test('checks the new password before changing it', async () => {
  const dialog = await openChangePassword();

  await fill(dialog, '', 'krotkie', 'inne');

  expect(within(dialog).getByText(pl.auth.fieldRequired)).toBeInTheDocument();
  expect(within(dialog).getByText(texts.tooShort(8))).toBeInTheDocument();
  expect(within(dialog).getByText(texts.mismatch)).toBeInTheDocument();
  expect(authService.changePassword).not.toHaveBeenCalled();
});

test('marks a wrong current password', async () => {
  vi.mocked(authService.changePassword).mockRejectedValue(
    new PasswordChangeFailure('wrongPassword'),
  );
  const dialog = await openChangePassword();

  await fill(dialog, 'zle-haslo', 'nowe-haslo-123');

  expect(await within(dialog).findByText(texts.errors.wrongPassword)).toBeInTheDocument();
  expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
});
