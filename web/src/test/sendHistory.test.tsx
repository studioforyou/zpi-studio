import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { CommunicationPage } from '@/features/communication/CommunicationPage';
import { periodRange } from '@/features/communication/history/historyPeriod';
import { renderWithProviders } from './renderWithProviders';

const reminder = {
  id: 'n-1',
  type: 'CLASS_REMINDER',
  recipientId: 'acc-k',
  recipientName: 'Katarzyna Wójcik',
  recipientRole: 'CLIENT',
  about: null,
  title: 'Jutro: Pole dance początkujący',
  body: 'czw 20.11, 18:00 · Sala 2',
  bodyHidden: false,
  marketing: false,
  announcementId: null,
  author: null,
  sentAt: '2026-11-20T17:02:00Z',
  readAt: null,
  status: 'DELIVERED',
};

const chat = {
  ...reminder,
  id: 'n-2',
  type: 'NEW_MESSAGE',
  recipientId: 'acc-t',
  recipientName: 'Tomasz Zieliński',
  title: 'Anna Nowak',
  body: null,
  bodyHidden: true,
  status: 'READ',
  readAt: '2026-11-20T17:30:00Z',
};

const expiring = {
  ...reminder,
  id: 'n-3',
  type: 'PASS_EXPIRING',
  recipientId: 'acc-m',
  recipientName: 'Monika Kowalska',
  about: 'Zosia Lis',
  title: 'Karnet wygasa za 3 dni',
  body: 'Pole dance — 8 wejść',
  status: 'PUSH_FAILED',
};

const announcement = {
  ...reminder,
  id: 'n-4',
  type: 'ANNOUNCEMENT',
  title: 'Promocja −20% na karnety',
  body: 'Tylko do niedzieli.',
  author: 'Beata Lis',
  marketing: true,
  status: 'READ',
};

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function stubApi() {
  const urls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      urls.push(url);
      if (url.startsWith('/api/v1/notification-history?')) {
        const params = new URL(url, 'http://x').searchParams;
        let items = [reminder, chat, expiring, announcement];
        if (params.get('type')) items = items.filter((item) => item.type === params.get('type'));
        if (params.get('status'))
          items = items.filter((item) => item.status === params.get('status'));
        if (params.get('accountId'))
          items = items.filter((item) => item.recipientId === params.get('accountId'));
        return json({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 });
      }
      if (url === '/api/v1/notification-history/n-3') {
        return json({
          notification: expiring,
          deliveries: [
            {
              attemptedAt: '2026-11-20T17:02:01Z',
              channel: 'PUSH',
              status: 'TOKEN_INVALID',
              error: 'UNREGISTERED',
            },
          ],
        });
      }
      if (url.startsWith('/api/v1/accounts')) {
        // A guardian comes back twice: as herself and as the row of her child.
        const items = [
          { id: 'acc-m', displayName: 'Monika Kowalska', roles: ['CLIENT'] },
          { id: 'acc-m', displayName: 'Zosia Lis', roles: ['CHILD'] },
          { id: 'acc-k', displayName: 'Katarzyna Wójcik', roles: ['CLIENT'] },
        ];
        return json({ items, page: 0, size: 20, totalItems: 3, totalPages: 1 });
      }
      return json({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
    }),
  );
  return urls;
}

async function openHistory() {
  renderWithProviders(<CommunicationPage />);
  await userEvent.click(screen.getByRole('tab', { name: 'Historia wysyłek' }));
}

async function pick(label: string, option: string) {
  const select = screen.getByRole('combobox', { name: label });
  await userEvent.click(select);
  await waitFor(() => expect(select.getAttribute('aria-controls')).toBeTruthy());
  const listbox = document.getElementById(select.getAttribute('aria-controls')!)!;
  await userEvent.click(within(listbox).getByRole('option', { name: option, hidden: true }));
}

test('periods start at midnight in the studio time zone', () => {
  const now = new Date('2026-11-23T18:00:00Z');
  expect(periodRange('TODAY', { from: '', to: '' }, now)).toEqual({
    from: '2026-11-22T23:00:00.000Z',
    to: null,
  });
  expect(periodRange('WEEK', { from: '', to: '' }, now).from).toBe('2026-11-16T23:00:00.000Z');
  expect(periodRange('THIS_MONTH', { from: '', to: '' }, now).from).toBe(
    '2026-10-31T23:00:00.000Z',
  );
  expect(periodRange('CUSTOM', { from: '2026-11-01', to: '2026-11-03' }, now)).toEqual({
    from: '2026-10-31T23:00:00.000Z',
    to: '2026-11-03T23:00:00.000Z',
  });
});

test('lists sent notifications with their type, status and no chat text', async () => {
  const urls = stubApi();
  await openHistory();

  const rows = await screen.findAllByRole('row');
  expect(rows).toHaveLength(5);
  expect(within(rows[1]).getByText('Przypomnienie o zajęciach')).toBeInTheDocument();
  expect(within(rows[1]).getByText('Dostarczone')).toBeInTheDocument();
  expect(
    within(rows[2]).getByText('Treść wiadomości z czatu nie jest pokazywana'),
  ).toBeInTheDocument();
  expect(within(rows[3]).getByText('Błąd push')).toBeInTheDocument();
  expect(within(rows[3]).getByText('klient · dotyczy: Zosia Lis')).toBeInTheDocument();
  expect(within(rows[4]).getByText('Komunikat')).toBeInTheDocument();
  expect(screen.getByText('Pokazano 4 z 4')).toBeInTheDocument();
  // The last 7 days by default.
  const first = urls.find((url) => url.startsWith('/api/v1/notification-history?'))!;
  expect(first).toContain('from=');
  expect(first).not.toContain('to=');
});

test('filters by type and status, then clears the filters', async () => {
  const urls = stubApi();
  await openHistory();
  await screen.findByText('Pokazano 4 z 4');

  await pick('Rodzaj', 'Komunikat');
  await screen.findByText('Pokazano 1 z 1');
  expect(urls.at(-1)).toContain('type=ANNOUNCEMENT');

  await pick('Rodzaj', 'Karnet wkrótce wygaśnie');
  await pick('Status', 'Błąd push');
  await screen.findByText('Karnet wygasa za 3 dni');
  expect(urls.at(-1)).toContain('status=PUSH_FAILED');

  await userEvent.click(screen.getByRole('button', { name: 'Wyczyść filtry' }));
  await screen.findByText('Pokazano 4 z 4');
});

test('shows the delivery attempts and filters to the recipient', async () => {
  const urls = stubApi();
  await openHistory();

  await userEvent.click(await screen.findByText('Karnet wygasa za 3 dni'));
  const drawer = await screen.findByRole('dialog');
  expect(
    await within(drawer).findByText(/push nie dotarł \(telefon nie jest już zarejestrowany\)/),
  ).toBeInTheDocument();
  expect(within(drawer).getByText(/push na telefon nie dotarł/)).toBeInTheDocument();

  await userEvent.click(
    within(drawer).getByRole('button', { name: 'Pokaż wszystkie powiadomienia tej osoby' }),
  );
  await waitFor(() => expect(urls.at(-1)).toContain('accountId=acc-m'));
  await screen.findByText('Pokazano 1 z 1');
});

test('shows an empty state when nothing matches', async () => {
  stubApi();
  await openHistory();
  await screen.findByText('Pokazano 4 z 4');
  await pick('Rodzaj', 'Odwołanie zajęć');
  expect(await screen.findByText('Brak powiadomień dla tych filtrów')).toBeInTheDocument();
});

test('offers a guardian once in the person filter, without the rows of her children', async () => {
  const urls = stubApi();
  await openHistory();
  await screen.findByText('Pokazano 4 z 4');

  const select = screen.getByRole('combobox', { name: 'Odbiorca' });
  await userEvent.click(select);
  await waitFor(() => expect(select.getAttribute('aria-controls')).toBeTruthy());
  const listbox = document.getElementById(select.getAttribute('aria-controls')!)!;
  await waitFor(() =>
    expect(within(listbox).getAllByRole('option', { hidden: true })).toHaveLength(2),
  );
  expect(within(listbox).queryByRole('option', { name: 'Zosia Lis', hidden: true })).toBeNull();
  await userEvent.click(
    within(listbox).getByRole('option', { name: 'Monika Kowalska', hidden: true }),
  );
  await waitFor(() => expect(urls.at(-1)).toContain('accountId=acc-m'));
});
