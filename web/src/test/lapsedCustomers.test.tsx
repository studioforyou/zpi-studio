import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import type { LapsedCustomer } from '@/features/statistics/lapsedCustomersApi';
import { matches } from '@/features/statistics/lapsedCustomersFilters';
import { StatisticsPage } from '@/features/statistics/StatisticsPage';
import { renderWithProviders } from './renderWithProviders';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function customer(values: Partial<LapsedCustomer>): LapsedCustomer {
  return {
    profileId: 'p',
    kind: 'ADULT',
    firstName: 'Jan',
    lastName: 'Testowy',
    contactAccountId: 'acc',
    guardianName: null,
    email: null,
    phone: null,
    lastVisit: '2026-10-01',
    daysSinceLastVisit: 56,
    recentVisits: 3,
    pass: null,
    marketingConsent: false,
    lastAnnouncement: null,
    ...values,
  };
}

const magdalena = customer({
  profileId: 'p-mw',
  firstName: 'Magdalena',
  lastName: 'Wróbel',
  contactAccountId: 'acc-mw',
  email: 'm.wrobel@example.com',
  phone: '+48 601 234 118',
  lastVisit: '2026-08-14',
  daysSinceLastVisit: 104,
  recentVisits: 11,
  pass: { productName: 'Karnet 8', state: 'VALID', validUntil: '2026-11-30', entriesRemaining: 2 },
  marketingConsent: true,
  lastAnnouncement: '2026-10-02',
});

const oliwer = customer({
  profileId: 'p-ok',
  kind: 'CHILD',
  firstName: 'Oliwer',
  lastName: 'Kubiak',
  contactAccountId: 'acc-mk',
  guardianName: 'Marta Kubiak',
  email: 'marta@example.com',
  phone: '+48 790 552 014',
  lastVisit: '2026-10-20',
  daysSinceLastVisit: 37,
  recentVisits: 6,
  pass: {
    productName: 'Karnet dziecięcy',
    state: 'ENDED',
    validUntil: '2026-10-30',
    entriesRemaining: 0,
  },
});

const joanna = customer({
  profileId: 'p-jp',
  firstName: 'Joanna',
  lastName: 'Pawlak',
  contactAccountId: 'acc-jp',
  email: 'joanna@example.com',
  lastVisit: '2026-10-24',
  daysSinceLastVisit: 33,
  recentVisits: 2,
});

function stubApi(customers: LapsedCustomer[]) {
  const requests: string[] = [];
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url === '/api/v1/studio') {
      return json({ name: 'Studio', timeZone: 'Europe/Warsaw', currency: 'PLN' });
    }
    if (url.startsWith('/api/v1/reports/lapsed-customers?')) {
      const days = Number(new URLSearchParams(url.split('?')[1]).get('inactiveDays'));
      return json({
        asOf: '2026-11-26',
        inactiveDays: days,
        everVisited: 171,
        customers: customers.filter((row) => row.daysSinceLastVisit >= days),
      });
    }
    if (url.startsWith('/api/v1/accounts?')) {
      return json({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
    }
    if (url === '/api/v1/announcement-templates') {
      return json({ items: [] });
    }
    if (url === '/api/v1/announcements/preview') {
      return json({ recipients: 1, guardians: 0, skippedWithoutConsent: 0 });
    }
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return requests;
}

function renderLapsedTab() {
  return renderWithProviders(null, {
    routes: [
      { path: '/statistics', element: <StatisticsPage /> },
      { path: '/accounts/:id', element: <p>Konto klienta</p> },
    ],
    initialEntry: '/statistics?tab=lapsed',
  });
}

test('lists customers who stopped coming with their contact, pass and last announcement', async () => {
  const requests = stubApi([magdalena, oliwer, joanna]);
  renderLapsedTab();

  expect(screen.getByRole('tab', { name: 'Nieaktywni klienci' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  const row = (await screen.findByText('Magdalena Wróbel')).closest('tr')!;
  expect(within(row).getByText('+48 601 234 118')).toBeInTheDocument();
  expect(within(row).getByText('m.wrobel@example.com')).toBeInTheDocument();
  expect(within(row).getByText('14.08.2026')).toBeInTheDocument();
  expect(within(row).getByText('104 dni temu')).toBeInTheDocument();
  expect(within(row).getByText('Ważny do 30.11.2026 · 2 wejścia')).toBeInTheDocument();
  expect(within(row).getByText('Tak')).toBeInTheDocument();
  expect(within(row).getByText('02.10.2026')).toBeInTheDocument();

  const child = screen.getByText('Oliwer Kubiak').closest('tr')!;
  expect(within(child).getByText('dziecko · opiekun: Marta Kubiak')).toBeInTheDocument();
  expect(within(child).getByText('Wejścia wykorzystane')).toBeInTheDocument();
  expect(
    within(screen.getByText('Joanna Pawlak').closest('tr')!).getByText('Brak karnetu'),
  ).toBeInTheDocument();

  const tiles = screen.getAllByRole('group');
  expect(within(tiles[0]).getByText('Nieaktywni od 30+ dni')).toBeInTheDocument();
  expect(within(tiles[0]).getByText('3')).toBeInTheDocument();
  expect(
    within(tiles[0]).getByText('spośród 171 klientów z co najmniej jedną wizytą'),
  ).toBeInTheDocument();
  expect(within(tiles[1]).getByText('1')).toBeInTheDocument();
  expect(within(tiles[2]).getByText('2')).toBeInTheDocument();
  expect(screen.getByText('Od najdłużej nieobecnych · 3 z 3')).toBeInTheDocument();

  // The list is as of today: the period and event filters are not shown and not sent.
  expect(screen.queryByText('Okres')).toBeNull();
  expect(requests).toContain('GET /api/v1/reports/lapsed-customers?inactiveDays=30');
});

test('changes the threshold and narrows the list by search and valid pass', async () => {
  const requests = stubApi([magdalena, oliwer, joanna]);
  renderLapsedTab();
  await screen.findByText('Magdalena Wróbel');

  await userEvent.click(screen.getByText('60 dni'));
  await waitFor(() => expect(screen.queryByText('Oliwer Kubiak')).toBeNull());
  expect(requests).toContain('GET /api/v1/reports/lapsed-customers?inactiveDays=60');
  expect(screen.getByText('Nieaktywni od 60+ dni')).toBeInTheDocument();

  await userEvent.click(screen.getByText('14 dni'));
  await screen.findByText('Oliwer Kubiak');
  await userEvent.click(screen.getByLabelText('Tylko z ważnym karnetem'));
  expect(screen.queryByText('Oliwer Kubiak')).toBeNull();
  expect(screen.getByText('Magdalena Wróbel')).toBeInTheDocument();
  expect(screen.getByText('Od najdłużej nieobecnych · 1 z 3')).toBeInTheDocument();

  await userEvent.click(screen.getByLabelText('Tylko z ważnym karnetem'));
  await userEvent.type(screen.getByLabelText('Szukaj'), 'marta');
  expect(screen.getByText('Oliwer Kubiak')).toBeInTheDocument();
  expect(screen.queryByText('Magdalena Wróbel')).toBeNull();

  await userEvent.clear(screen.getByLabelText('Szukaj'));
  await userEvent.type(screen.getByLabelText('Szukaj'), 'nikt taki');
  expect(screen.getByText('Nikt z tej listy nie pasuje do wyszukiwania.')).toBeInTheDocument();
});

test('opens a new announcement addressed to the guardian of a child', async () => {
  stubApi([magdalena, oliwer]);
  renderLapsedTab();
  await screen.findByText('Oliwer Kubiak');

  await userEvent.click(screen.getByRole('button', { name: 'Wyślij komunikat: Oliwer Kubiak' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByRole('radio', { name: 'Jedna osoba' })).toBeChecked();
  expect(within(dialog).getByRole('combobox', { name: 'Osoba' })).toHaveValue(
    'Marta Kubiak · klient',
  );
});

test('links a child to its guardian account with the child profile open', async () => {
  stubApi([oliwer]);
  renderLapsedTab();
  await userEvent.click(await screen.findByRole('link', { name: 'Konto: Oliwer Kubiak' }));
  expect(await screen.findByText('Konto klienta')).toBeInTheDocument();
});

test('says when nobody stopped coming', async () => {
  stubApi([]);
  renderLapsedTab();
  expect(await screen.findByText('Nikt nie przestał przychodzić')).toBeInTheDocument();
  expect(
    screen.getByText(
      'Każdy klient był w studiu w ciągu ostatnich 30 dni albo ma zaplanowaną rezerwację.',
    ),
  ).toBeInTheDocument();
});

test('matches a search in names either way round, e-mails and phone numbers without spaces', () => {
  expect(matches(magdalena, 'wróbel magd')).toBe(true);
  expect(matches(magdalena, 'WROBEL@')).toBe(true);
  expect(matches(magdalena, '601234')).toBe(true);
  expect(matches(oliwer, 'marta kub')).toBe(true);
  expect(matches(joanna, '601')).toBe(false);
  expect(matches(joanna, '  ')).toBe(true);
});
