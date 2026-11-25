import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import { CommunicationPage } from '@/features/communication/CommunicationPage';
import { formatSentAt } from '@/features/communication/announcements/announcementFormat';
import { renderWithProviders } from './renderWithProviders';

afterEach(() => {
  vi.useRealTimers();
});

const sent = {
  id: 'a-1',
  title: 'Zmiana sali w czwartek',
  body: 'W czwartek ćwiczymy w Sali 2.',
  audience: 'GROUP',
  targetName: 'Pole dance początkujący',
  eventStartsAt: null,
  marketing: false,
  sentAt: '2026-11-15T08:30:00Z',
  authorName: 'Beata Lis',
  recipients: 12,
  read: 11,
  pushDelivered: 10,
  skippedWithoutConsent: 0,
};

const promo = {
  ...sent,
  id: 'a-2',
  title: 'Promocja −20% na karnety',
  audience: 'ALL',
  targetName: null,
  marketing: true,
  recipients: 61,
  read: 27,
  pushDelivered: 52,
  skippedWithoutConsent: 23,
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });
}

type Request = { url: string; method: string; body: Record<string, unknown> | null };

function stubApi(history: unknown[], sendStatus = 201) {
  const requests: Request[] = [];
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    const body = init.body ? JSON.parse(init.body as string) : null;
    requests.push({ url, method: init.method ?? 'GET', body });
    if (url.startsWith('/api/v1/announcements?')) {
      return json({ items: history, page: 0, size: 20, totalItems: history.length, totalPages: 1 });
    }
    if (url === '/api/v1/announcements/a-2') {
      return json({
        announcement: promo,
        recipients: [
          { accountId: 'k', name: 'Katarzyna Wójcik', about: null, status: 'READ' },
          { accountId: 'e', name: 'Ewa Lis', about: 'Zosia', status: 'APP_ONLY' },
        ],
      });
    }
    if (url === '/api/v1/training-groups') {
      return json({
        items: [
          { id: 'g-1', name: 'Pole dance początkujący', serviceTypeId: 's', isActive: true },
          { id: 'g-2', name: 'Stara grupa', serviceTypeId: 's', isActive: false },
        ],
      });
    }
    if (url === '/api/v1/announcements/preview') {
      if (body.audience === 'ALL') {
        return json({
          recipients: 61,
          guardians: 0,
          skippedWithoutConsent: body.marketing ? 23 : 0,
        });
      }
      return json({ recipients: 12, guardians: 2, skippedWithoutConsent: 0 });
    }
    if (url === '/api/v1/announcements' && init.method === 'POST') {
      if (sendStatus !== 201) {
        return json({ status: sendStatus, code: 'ANNOUNCEMENT_NO_RECIPIENTS' }, sendStatus);
      }
      return json({ ...sent, id: 'a-3', title: body.title }, 201);
    }
    throw new Error(`Unexpected request: ${init.method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return requests;
}

/** Picks a training group in the dialog; an inactive group is never offered. */
async function pickGroup(dialog: HTMLElement, name: string) {
  const select = within(dialog).getByRole('combobox', { name: 'Grupa treningowa' });
  await userEvent.click(select);
  await waitFor(() => expect(select.getAttribute('aria-controls')).toBeTruthy());
  const listbox = document.getElementById(select.getAttribute('aria-controls')!)!;
  await waitFor(() =>
    expect(within(listbox).getAllByRole('option', { hidden: true }).length).toBeGreaterThan(0),
  );
  expect(within(listbox).queryByRole('option', { name: 'Stara grupa', hidden: true })).toBeNull();
  await userEvent.click(within(listbox).getByRole('option', { name, hidden: true }));
}

test('formats when an announcement was sent', () => {
  const now = new Date('2026-11-18T17:00:00Z');
  expect(formatSentAt('2026-11-18T16:40:00Z', now)).toBe('dziś, 17:40');
  expect(formatSentAt('2026-11-17T11:05:00Z', now)).toBe('wczoraj, 12:05');
  expect(formatSentAt('2026-11-15T08:30:00Z', now)).toBe('15.11, 09:30');
  expect(formatSentAt('2025-12-31T08:30:00Z', now)).toBe('31.12.2025, 09:30');
});

test('lists sent announcements first and shows one with its recipients', async () => {
  stubApi([promo, sent]);
  renderWithProviders(<CommunicationPage />);

  expect(screen.getByRole('tab', { name: 'Komunikaty' })).toHaveAttribute('aria-selected', 'true');
  const row = (await screen.findByText('Promocja −20% na karnety')).closest('tr')!;
  expect(within(row).getByText('Marketing')).toBeInTheDocument();
  expect(within(row).getByText('Wszyscy klienci')).toBeInTheDocument();
  expect(within(row).getByText('27 z 61')).toBeInTheDocument();
  expect(screen.getByText('Grupa: Pole dance początkujący')).toBeInTheDocument();

  await userEvent.click(row);
  const drawer = await screen.findByRole('dialog');
  expect(within(drawer).getByText('pominięto (brak zgody)')).toBeInTheDocument();
  expect(within(drawer).getByText('23')).toBeInTheDocument();
  expect(await within(drawer).findByText('Katarzyna Wójcik')).toBeInTheDocument();
  expect(within(drawer).getByText('Przeczytane')).toBeInTheDocument();
  expect(within(drawer).getByText(/dotyczy dziecka: Zosia/)).toBeInTheDocument();
  expect(within(drawer).getByText('Tylko w aplikacji')).toBeInTheDocument();
});

test('shows an empty history with a way to write the first announcement', async () => {
  stubApi([]);
  renderWithProviders(<CommunicationPage />);
  expect(await screen.findByText('Nie wysłano jeszcze żadnego komunikatu')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '+ Nowy komunikat' })).toBeInTheDocument();
});

test('sends an announcement to a training group after counting its recipients', async () => {
  const requests = stubApi([sent]);
  renderWithProviders(<CommunicationPage />);
  await userEvent.click(await screen.findByRole('button', { name: '+ Nowy komunikat' }));
  const dialog = await screen.findByRole('dialog');

  expect(within(dialog).getByText(/Wybierz grupę\./)).toBeInTheDocument();
  const send = within(dialog).getByRole('button', { name: 'Wyślij' });
  expect(send).toBeDisabled();

  await pickGroup(dialog, 'Pole dance początkujący');
  expect(
    await within(dialog).findByText('Trafi do 12 osób, w tym 2 opiekunów dzieci.'),
  ).toBeInTheDocument();

  await userEvent.type(within(dialog).getByRole('textbox', { name: 'Tytuł' }), 'Zmiana sali');
  await userEvent.type(within(dialog).getByRole('textbox', { name: 'Treść' }), 'Sala 2.');
  expect(within(dialog).getByText('7 / 2000')).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Wyślij' }));

  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  const post = requests.find(
    (request) => request.url === '/api/v1/announcements' && request.method === 'POST',
  );
  expect(post?.body).toEqual({
    audience: 'GROUP',
    accountId: null,
    trainingGroupId: 'g-1',
    eventId: null,
    marketing: false,
    title: 'Zmiana sali',
    body: 'Sala 2.',
  });
});

test('asks before sending a marketing announcement to every client and shows who is left out', async () => {
  const requests = stubApi([sent]);
  renderWithProviders(<CommunicationPage />);
  await userEvent.click(await screen.findByRole('button', { name: '+ Nowy komunikat' }));
  const dialog = await screen.findByRole('dialog');

  await userEvent.click(within(dialog).getByText('Wszyscy klienci'));
  await userEvent.click(within(dialog).getByRole('checkbox', { name: /Treść marketingowa/ }));
  expect(
    await within(dialog).findByText(
      'Trafi do 61 osób. 23 osoby bez zgody na powiadomienia marketingowe zostanie pominiętych.',
    ),
  ).toBeInTheDocument();

  await userEvent.click(within(dialog).getByRole('button', { name: 'Wyślij' }));
  expect(within(dialog).getAllByText('To pole jest wymagane.')).toHaveLength(2);

  await userEvent.type(within(dialog).getByRole('textbox', { name: 'Tytuł' }), 'Promocja');
  await userEvent.type(within(dialog).getByRole('textbox', { name: 'Treść' }), '20% taniej.');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Wyślij' }));

  expect(within(dialog).getByText('Wysłać do wszystkich klientów?')).toBeInTheDocument();
  expect(
    requests.some(
      (request) => request.method === 'POST' && request.url === '/api/v1/announcements',
    ),
  ).toBe(false);
  await userEvent.click(within(dialog).getByRole('button', { name: 'Wyślij do 61 osób' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  const post = requests.find(
    (request) => request.url === '/api/v1/announcements' && request.method === 'POST',
  );
  expect(post?.body).toMatchObject({ audience: 'ALL', marketing: true, title: 'Promocja' });
});

test('explains a refused announcement', async () => {
  stubApi([sent], 422);
  renderWithProviders(<CommunicationPage />);
  await userEvent.click(await screen.findByRole('button', { name: '+ Nowy komunikat' }));
  const dialog = await screen.findByRole('dialog');
  await pickGroup(dialog, 'Pole dance początkujący');
  await userEvent.type(within(dialog).getByRole('textbox', { name: 'Tytuł' }), 'T');
  await userEvent.type(within(dialog).getByRole('textbox', { name: 'Treść' }), 'B');
  await within(dialog).findByText(/Trafi do 12 osób/);
  await userEvent.click(within(dialog).getByRole('button', { name: 'Wyślij' }));
  expect(
    await within(dialog).findByText('Nikt nie dostanie tego komunikatu. Zmień odbiorców.'),
  ).toBeInTheDocument();
});
