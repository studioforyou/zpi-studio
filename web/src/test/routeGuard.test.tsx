import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { appRoutes } from '@/app/router';
import { pl } from '@/shared/i18n/pl';
import { renderWithProviders } from './renderWithProviders';

test('redirects to the login page without a session', async () => {
  renderWithProviders(null, { routes: appRoutes, initialEntry: '/schedule' });

  expect(await screen.findByText('Logowanie')).toBeInTheDocument();
  expect(screen.queryByText(pl.common.placeholder)).not.toBeInTheDocument();
});
