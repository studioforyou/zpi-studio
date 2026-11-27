import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { EventHistorySection } from '@/features/schedule/EventHistorySection';
import { getEventHistory, type EventHistoryEntry } from '@/features/schedule/scheduleApi';
import { renderWithProviders } from './renderWithProviders';

vi.mock('@/features/schedule/scheduleApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/schedule/scheduleApi')>()),
  getEventHistory: vi.fn(),
}));

const entry: EventHistoryEntry = {
  id: 'audit-1',
  occurredAt: '2026-11-20T09:00:00Z',
  kind: 'SUBSTITUTION',
  actorAccountId: 'admin',
  actorName: 'Beata Lis',
  changes: {
    staff: {
      old: [{ staffId: 'ewa', displayName: 'Ewa Wiśniewska', role: 'LEAD' }],
      new: [{ staffId: 'marek', displayName: 'Marek Nowak', role: 'LEAD' }],
    },
  },
  reason: null,
};

test('history loads on demand and explains the substitution, its author and both staff lists', async () => {
  const user = userEvent.setup();
  vi.mocked(getEventHistory).mockResolvedValue([entry]);
  renderWithProviders(<EventHistorySection eventId="session" timeZone="Europe/Warsaw" />);
  expect(getEventHistory).not.toHaveBeenCalled();

  await user.click(screen.getByRole('button', { name: 'Historia zajęć' }));
  await waitFor(() => expect(screen.getByText('Zastępstwo')).toBeVisible());
  expect(screen.getByText('Przed zmianą')).toBeVisible();
  expect(screen.getByText('Po zmianie')).toBeVisible();
  expect(screen.getByText('Ewa Wiśniewska')).toBeVisible();
  expect(screen.getByText('Marek Nowak')).toBeVisible();
  expect(screen.getByText(/Beata Lis/)).toBeVisible();
  expect(getEventHistory).toHaveBeenCalledWith('session');
});

test('a failed history can be retried and an empty result does not claim there were no changes', async () => {
  const user = userEvent.setup();
  vi.mocked(getEventHistory)
    .mockRejectedValueOnce(new Error('Connection lost'))
    .mockResolvedValueOnce([]);
  renderWithProviders(<EventHistorySection eventId="session" timeZone="Europe/Warsaw" />);
  await user.click(screen.getByRole('button', { name: 'Historia zajęć' }));
  await waitFor(() =>
    expect(screen.getByText('Nie udało się wczytać historii zajęć.')).toBeVisible(),
  );
  await user.click(screen.getByRole('button', { name: 'Spróbuj ponownie' }));
  await waitFor(() => expect(screen.getByText('Brak zapisanych zmian.')).toBeVisible());
});

test('only five changes are shown at first and older changes can be expanded', async () => {
  const user = userEvent.setup();
  vi.mocked(getEventHistory).mockResolvedValue(
    Array.from({ length: 6 }, (_, index) => ({
      ...entry,
      id: `audit-${index}`,
      actorName: `Osoba ${index}`,
    })),
  );
  renderWithProviders(<EventHistorySection eventId="session" timeZone="Europe/Warsaw" />);
  await user.click(screen.getByRole('button', { name: 'Historia zajęć' }));
  await waitFor(() => expect(screen.getByText(/Osoba 4/)).toBeVisible());
  expect(screen.queryByText(/Osoba 5/)).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Pokaż starsze zmiany' }));
  expect(screen.getByText(/Osoba 5/)).toBeVisible();
  expect(screen.queryByRole('button', { name: 'Pokaż starsze zmiany' })).not.toBeInTheDocument();
});

test('one edit shows time, room, name and capacity together without unchanged fields', async () => {
  const user = userEvent.setup();
  vi.mocked(getEventHistory).mockResolvedValue([
    {
      ...entry,
      kind: 'MANUAL',
      changes: {
        time: {
          old: { startsAt: '2026-11-26T17:00:00Z', endsAt: '2026-11-26T18:00:00Z' },
          new: { startsAt: '2026-11-27T18:00:00Z', endsAt: '2026-11-27T19:30:00Z' },
        },
        resources: {
          old: [{ resourceId: 'one', name: 'Sala pierwsza', kind: 'ROOM', blocksWholeRoom: true }],
          new: [{ resourceId: 'two', name: 'Sala druga', kind: 'ROOM', blocksWholeRoom: true }],
        },
        title: { old: 'Poprzednia nazwa', new: 'Nowa nazwa' },
        capacity: { old: 10, new: 8 },
      },
    },
  ]);
  renderWithProviders(<EventHistorySection eventId="session" timeZone="Europe/Warsaw" />);
  await user.click(screen.getByRole('button', { name: 'Historia zajęć' }));
  await waitFor(() => expect(screen.getByText('Zmieniono zajęcia')).toBeVisible());
  const table = within(screen.getByRole('table', { name: 'Zmienione wartości' }));
  expect(table.getByText('26.11.2026')).toBeVisible();
  expect(table.getByText('27.11.2026')).toBeVisible();
  expect(table.getByText('18:00–19:00')).toBeVisible();
  expect(table.getByText('19:00–20:30')).toBeVisible();
  expect(table.getByText(/Sala pierwsza/)).toBeVisible();
  expect(table.getByText(/Sala druga/)).toBeVisible();
  expect(table.getByText('Nowa nazwa')).toBeVisible();
  expect(table.getByText('8')).toBeVisible();
  expect(table.queryByText('Obsada')).not.toBeInTheDocument();
});

test('system cancellation explains the status and reason in the same panel', async () => {
  const user = userEvent.setup();
  vi.mocked(getEventHistory).mockResolvedValue([
    {
      ...entry,
      kind: 'CANCELLED',
      actorAccountId: null,
      actorName: null,
      changes: { status: { old: 'SCHEDULED', new: 'CANCELLED' } },
      reason: 'Za mało uczestników',
    },
  ]);
  renderWithProviders(<EventHistorySection eventId="session" timeZone="Europe/Warsaw" />);
  await user.click(screen.getByRole('button', { name: 'Historia zajęć' }));
  await waitFor(() => expect(screen.getByText('Odwołano zajęcia')).toBeVisible());
  expect(screen.getByText(/System/)).toBeVisible();
  expect(screen.getByText('Za mało uczestników')).toBeVisible();
  expect(screen.getByText('Odwołane')).toBeVisible();
});

test('creation shows its recorded author and date without inventing original settings', async () => {
  const user = userEvent.setup();
  vi.mocked(getEventHistory).mockResolvedValue([{ ...entry, kind: 'CREATED', changes: {} }]);
  renderWithProviders(<EventHistorySection eventId="session" timeZone="Europe/Warsaw" />);
  await user.click(screen.getByRole('button', { name: 'Historia zajęć' }));
  await waitFor(() => expect(screen.getByText('Utworzono zajęcia')).toBeVisible());
  expect(screen.getByText(/Beata Lis/)).toBeVisible();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
});
