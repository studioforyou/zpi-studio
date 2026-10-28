import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { appRoutes } from '@/app/router';
import { PasswordResetFailure } from '@/features/auth/authFailure';
import { authService } from '@/features/auth/authService';
import { pl } from '@/shared/i18n/pl';
import { renderWithProviders } from './renderWithProviders';

const texts = pl.auth.reset;

test('opens from the login page with the e-mail typed there and sends the link', async () => {
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/login' });
  await userEvent.type(await screen.findByLabelText(pl.auth.email), 'admin@example.com');

  await userEvent.click(screen.getByRole('link', { name: pl.auth.forgotPassword }));

  expect(await screen.findByRole('heading', { name: texts.title })).toBeInTheDocument();
  expect(screen.getByLabelText(pl.auth.email)).toHaveValue('admin@example.com');
  await userEvent.click(screen.getByRole('button', { name: texts.send }));

  expect(await screen.findByRole('status')).toHaveTextContent(texts.sent);
  expect(authService.sendPasswordReset).toHaveBeenCalledWith('admin@example.com');
  expect(screen.getByRole('button', { name: texts.resend })).toBeInTheDocument();
});

test('checks the address before sending', async () => {
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/reset-password' });

  await userEvent.type(await screen.findByLabelText(pl.auth.email), 'admin@');
  await userEvent.click(screen.getByRole('button', { name: texts.send }));

  expect(screen.getByText(texts.errors.invalidEmail)).toBeInTheDocument();
  expect(authService.sendPasswordReset).not.toHaveBeenCalled();
});

test('explains why the link was not sent', async () => {
  vi.mocked(authService.sendPasswordReset).mockRejectedValue(
    new PasswordResetFailure('tooManyAttempts'),
  );
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/reset-password' });

  await userEvent.type(await screen.findByLabelText(pl.auth.email), 'admin@example.com');
  await userEvent.click(screen.getByRole('button', { name: texts.send }));

  expect(await screen.findByRole('alert')).toHaveTextContent(texts.errors.tooManyAttempts);
});

test('leads back to the login page', async () => {
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/reset-password' });

  await userEvent.click(await screen.findByRole('link', { name: texts.backToLogin }));

  expect(await screen.findByRole('heading', { name: pl.auth.loginTitle })).toBeInTheDocument();
});
