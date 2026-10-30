import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { appRoutes } from '@/app/router';
import { pl } from '@/shared/i18n/pl';
import { renderWithProviders } from './renderWithProviders';

test('opens without a session and hands the token to the app', async () => {
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/invite?token=Ab-c_9' });

  expect(await screen.findByText(pl.invite.title)).toBeInTheDocument();
  expect(screen.getByRole('link', { name: pl.invite.openInApp })).toHaveAttribute(
    'href',
    'studioforyou://app/invite?token=Ab-c_9',
  );
  expect(screen.queryByText(pl.auth.loginTitle)).not.toBeInTheDocument();
});

test('explains an invalid link without a token', async () => {
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/invite' });

  expect(await screen.findByText(pl.invite.invalidTitle)).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: pl.invite.openInApp })).not.toBeInTheDocument();
});
