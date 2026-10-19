import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { SystemStatusPage } from '@/features/system/SystemStatusPage';
import { renderWithProviders } from './renderWithProviders';

test('shows a successful backend connection check', async () => {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response('Backend is running', {
      status: 200,
      headers: { 'Content-Type': 'text/plain' },
    }),
  );
  vi.stubGlobal('fetch', fetchMock);
  renderWithProviders(<SystemStatusPage />);

  await userEvent.click(screen.getByRole('button', { name: 'Sprawdź połączenie' }));

  expect(await screen.findByText('Połączono z backendem.')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/\/api\/ping$/), expect.anything());
});
