import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { appRoutes } from '@/app/router';
import { AuthFailure } from '@/features/auth/authFailure';
import { authService } from '@/features/auth/authService';
import type { Session } from '@/features/auth/types';
import { pl } from '@/shared/i18n/pl';
import { renderWithProviders } from './renderWithProviders';

const admin: Session = {
  accountId: 'a1',
  email: 'admin@example.com',
  displayName: 'Beata Lis',
  roles: ['ADMIN'],
};

async function fillAndSubmit() {
  await userEvent.type(await screen.findByLabelText(pl.auth.email), admin.email);
  await userEvent.type(screen.getByLabelText(pl.auth.password), 'secret');
  await userEvent.click(screen.getByRole('button', { name: pl.auth.submit }));
}

test('opens the panel after a successful login', async () => {
  vi.mocked(authService.login).mockResolvedValue(admin);
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/login' });

  await fillAndSubmit();

  expect(await screen.findByText(admin.displayName)).toBeInTheDocument();
  expect(authService.login).toHaveBeenCalledWith(admin.email, 'secret');
});

test('explains why the login failed', async () => {
  vi.mocked(authService.login).mockRejectedValue(new AuthFailure('notAdmin'));
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/login' });

  await fillAndSubmit();

  expect(await screen.findByRole('alert')).toHaveTextContent(pl.auth.errors.notAdmin);
});

test('restores the session saved by Firebase', async () => {
  vi.mocked(authService.restore).mockResolvedValue(admin);
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/schedule' });

  expect(await screen.findByText(admin.displayName)).toBeInTheDocument();
  expect(screen.queryByText(pl.auth.loginTitle)).not.toBeInTheDocument();
});
