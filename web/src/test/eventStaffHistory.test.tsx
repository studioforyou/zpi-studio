import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { EventStaffHistorySection } from '@/features/schedule/EventStaffHistorySection';
import { getEventStaffHistory, type StaffHistoryEntry } from '@/features/schedule/scheduleApi';
import { renderWithProviders } from './renderWithProviders';

vi.mock('@/features/schedule/scheduleApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/schedule/scheduleApi')>()),
  getEventStaffHistory: vi.fn(),
}));

const entry: StaffHistoryEntry = {
  id: 'audit-1',
  occurredAt: '2026-11-20T09:00:00Z',
  kind: 'SUBSTITUTION',
  actorAccountId: 'admin',
  actorName: 'Beata Lis',
  before: [{ staffId: 'ewa', displayName: 'Ewa Wiśniewska', role: 'LEAD' }],
  after: [{ staffId: 'marek', displayName: 'Marek Nowak', role: 'LEAD' }],
};

test('history loads on demand and explains the substitution, its author and both staff lists', async () => {
  const user = userEvent.setup();
  vi.mocked(getEventStaffHistory).mockResolvedValue([entry]);
  renderWithProviders(<EventStaffHistorySection eventId="session" timeZone="Europe/Warsaw" />);
  expect(getEventStaffHistory).not.toHaveBeenCalled();

  await user.click(screen.getByRole('button', { name: 'Historia prowadzących' }));
  await waitFor(() => expect(screen.getByText('Zastępstwo')).toBeVisible());
  expect(screen.getByText('Przed zmianą')).toBeVisible();
  expect(screen.getByText('Po zmianie')).toBeVisible();
  expect(screen.getByText('Ewa Wiśniewska')).toBeVisible();
  expect(screen.getByText('Marek Nowak')).toBeVisible();
  expect(screen.getByText(/Beata Lis/)).toBeVisible();
  expect(getEventStaffHistory).toHaveBeenCalledWith('session');
});

test('a failed history can be retried and an empty result does not claim there were no changes', async () => {
  const user = userEvent.setup();
  vi.mocked(getEventStaffHistory)
    .mockRejectedValueOnce(new Error('Connection lost'))
    .mockResolvedValueOnce([]);
  renderWithProviders(<EventStaffHistorySection eventId="session" timeZone="Europe/Warsaw" />);
  await user.click(screen.getByRole('button', { name: 'Historia prowadzących' }));
  await waitFor(() =>
    expect(screen.getByText('Nie udało się wczytać historii prowadzących.')).toBeVisible(),
  );
  await user.click(screen.getByRole('button', { name: 'Spróbuj ponownie' }));
  await waitFor(() => expect(screen.getByText('Brak zapisanych zmian obsady.')).toBeVisible());
});

test('only five changes are shown at first and older changes can be expanded', async () => {
  const user = userEvent.setup();
  vi.mocked(getEventStaffHistory).mockResolvedValue(
    Array.from({ length: 6 }, (_, index) => ({
      ...entry,
      id: `audit-${index}`,
      actorName: `Osoba ${index}`,
    })),
  );
  renderWithProviders(<EventStaffHistorySection eventId="session" timeZone="Europe/Warsaw" />);
  await user.click(screen.getByRole('button', { name: 'Historia prowadzących' }));
  await waitFor(() => expect(screen.getByText(/Osoba 4/)).toBeVisible());
  expect(screen.queryByText(/Osoba 5/)).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Pokaż starsze zmiany' }));
  expect(screen.getByText(/Osoba 5/)).toBeVisible();
  expect(screen.queryByRole('button', { name: 'Pokaż starsze zmiany' })).not.toBeInTheDocument();
});
