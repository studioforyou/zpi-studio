import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import { PassesPage } from '@/features/passes/PassesPage';
import type { MakeupPolicy } from '@/features/passes/makeupPoliciesApi';
import type { PassProduct } from '@/features/passes/passesApi';
import { renderWithProviders } from './renderWithProviders';

const thirtyDays: MakeupPolicy = {
  id: 'policy-1',
  name: 'Odrabianie 30 dni',
  validityDays: 30,
  maxPerPass: 2,
  grantOnInTimeCancel: true,
  grantOnStudioCancel: true,
  expiryCheckedAgainst: 'EVENT_DATE',
  isActive: true,
  createdAt: '2026-10-01T10:00:00Z',
  productCount: 3,
};

const archived: MakeupPolicy = {
  ...thirtyDays,
  id: 'policy-2',
  name: 'Odrabianie warsztatów',
  validityDays: 7,
  maxPerPass: null,
  grantOnInTimeCancel: false,
  expiryCheckedAgainst: 'BOOK_DATE',
  isActive: false,
  productCount: 0,
};

const product: PassProduct = {
  id: 'product-1',
  name: 'Karnet Pole Dance',
  description: null,
  kind: 'ENTRY_PASS',
  audience: 'ADULT',
  suspensionAllowed: false,
  maxSuspensionDays: null,
  isActive: true,
  serviceTypeIds: [],
  makeupPolicyId: 'policy-1',
  makeupPolicyName: 'Odrabianie 30 dni',
  variants: [],
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

type Sent = { url: string; method: string; body: unknown };

function mockApi(policies: MakeupPolicy[], products: PassProduct[] = [product]) {
  const sent: Sent[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : null });
        if (url.startsWith('/api/v1/pass-products')) {
          return Promise.resolve(json(product));
        }
        return Promise.resolve(json(thirtyDays, method === 'POST' ? 201 : 200));
      }
      if (url.startsWith('/api/v1/makeup-policies')) {
        return Promise.resolve(json({ items: policies }));
      }
      if (url.startsWith('/api/v1/service-types')) {
        return Promise.resolve(json({ items: [] }));
      }
      return Promise.resolve(json({ items: products }));
    }),
  );
  return sent;
}

/** Opens the policies tab and returns queries scoped to it (the offer tab stays mounted). */
async function openPoliciesTab() {
  renderWithProviders(<PassesPage />);
  await userEvent.click(await screen.findByRole('tab', { name: 'Zasady odrobień' }));
  return within(await screen.findByRole('tabpanel', { name: 'Zasady odrobień' }));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

test('lists the policies with their terms and the products using them', async () => {
  mockApi([thirtyDays, archived]);
  const panel = await openPoliciesTab();

  const row = (await panel.findByText('Odrabianie 30 dni')).closest('tr')!;
  expect(within(row).getByText('30 dni')).toBeInTheDocument();
  expect(within(row).getByText('maks. 2')).toBeInTheDocument();
  expect(within(row).getByText('odwołanie w terminie, odwołanie przez studio')).toBeInTheDocument();
  expect(within(row).getByText('w dniu zajęć')).toBeInTheDocument();
  expect(within(row).getByText('3 karnety')).toBeInTheDocument();
  expect(within(row).getByText('Aktywna')).toBeInTheDocument();

  const inactive = panel.getByText('Odrabianie warsztatów').closest('tr')!;
  expect(within(inactive).getByText('bez limitu')).toBeInTheDocument();
  expect(within(inactive).getByText('w chwili zapisu')).toBeInTheDocument();
  expect(within(inactive).getByText('nieużywana')).toBeInTheDocument();
  expect(within(inactive).getByRole('button', { name: 'Aktywuj' })).toBeInTheDocument();
});

test('shows an empty state that invites to add the first policy', async () => {
  mockApi([]);
  const panel = await openPoliciesTab();

  expect(await panel.findByText('Nie ma jeszcze żadnych zasad odrobień')).toBeInTheDocument();
  await userEvent.click(panel.getByRole('button', { name: 'Dodaj zasadę' }));
  expect(await screen.findByRole('dialog', { name: 'Nowa zasada odrobień' })).toBeInTheDocument();
});

test('creates a policy and marks missing values first', async () => {
  const sent = mockApi([thirtyDays]);
  const panel = await openPoliciesTab();
  await userEvent.click(await panel.findByRole('button', { name: 'Dodaj zasadę' }));
  const dialog = await screen.findByRole('dialog');

  await userEvent.clear(within(dialog).getByLabelText(/Ważność odróbki \(dni\)/));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  expect(within(dialog).getAllByText('To pole jest wymagane.')).toHaveLength(2);
  expect(sent).toHaveLength(0);

  await userEvent.type(within(dialog).getByLabelText(/Nazwa/), 'Odrabianie 14 dni');
  await userEvent.type(within(dialog).getByLabelText(/Ważność odróbki \(dni\)/), '14');
  await userEvent.click(within(dialog).getByLabelText('Bez limitu'));
  await userEvent.type(within(dialog).getByLabelText('Maksymalnie odróbek z jednego karnetu'), '1');
  await userEvent.click(within(dialog).getByLabelText('studio odwoła zajęcia'));
  await userEvent.click(within(dialog).getByText('w chwili zapisu'));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  await vi.waitFor(() =>
    expect(sent).toContainEqual({
      url: '/api/v1/makeup-policies',
      method: 'POST',
      body: {
        name: 'Odrabianie 14 dni',
        validityDays: 14,
        maxPerPass: 1,
        grantOnInTimeCancel: true,
        grantOnStudioCancel: false,
        expiryCheckedAgainst: 'BOOK_DATE',
      },
    }),
  );
});

test('edits a policy and explains that granted credits keep their expiry', async () => {
  const sent = mockApi([thirtyDays]);
  const panel = await openPoliciesTab();
  const row = (await panel.findByText('Odrabianie 30 dni')).closest('tr')!;
  await userEvent.click(within(row).getByRole('button', { name: 'Edytuj' }));
  const dialog = await screen.findByRole('dialog', { name: 'Edycja zasady odrobień' });

  expect(within(dialog).getByText(/zachowują swój termin ważności/)).toBeInTheDocument();
  const days = within(dialog).getByLabelText(/Ważność odróbki \(dni\)/);
  await userEvent.clear(days);
  await userEvent.type(days, '45');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  await vi.waitFor(() =>
    expect(sent).toContainEqual(
      expect.objectContaining({
        url: '/api/v1/makeup-policies/policy-1',
        method: 'PUT',
        body: expect.objectContaining({ validityDays: 45, maxPerPass: 2 }),
      }),
    ),
  );
});

test('asks before deactivating a policy used by products', async () => {
  const sent = mockApi([thirtyDays]);
  const panel = await openPoliciesTab();
  const row = (await panel.findByText('Odrabianie 30 dni')).closest('tr')!;
  await userEvent.click(within(row).getByRole('button', { name: 'Dezaktywuj' }));
  const dialog = await screen.findByRole('dialog', { name: 'Dezaktywować zasadę?' });

  expect(within(dialog).getByText(/Zasadę wybrano w: 3 karnety/)).toBeInTheDocument();
  expect(sent).toHaveLength(0);
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dezaktywuj' }));

  await vi.waitFor(() =>
    expect(sent).toContainEqual({
      url: '/api/v1/makeup-policies/policy-1/deactivate',
      method: 'POST',
      body: null,
    }),
  );
});

test('the product list and form show the chosen policy and can remove it', async () => {
  const sent = mockApi([thirtyDays, archived]);
  renderWithProviders(<PassesPage />);

  const row = (await screen.findByText('Karnet Pole Dance')).closest('tr')!;
  expect(within(row).getByText('Odrabianie 30 dni')).toBeInTheDocument();
  await userEvent.click(within(row).getByText('Karnet Pole Dance'));
  const dialog = await screen.findByRole('dialog');

  expect(
    await within(dialog).findByText(
      'Ważna 30 dni, maks. 2 na karnet, za: odwołanie w terminie, odwołanie przez studio.',
    ),
  ).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('combobox', { name: 'Zasady odrobień' }));
  // The inactive policy is not offered to a product that does not use it.
  expect(screen.queryByRole('option', { name: /Odrabianie warsztatów/, hidden: true })).toBeNull();
  await userEvent.click(await screen.findByRole('option', { name: 'Bez odrobień', hidden: true }));
  expect(
    within(dialog).getByText('Odwołane zajęcia z tego karnetu nie dają odróbek.'),
  ).toBeInTheDocument();
  await userEvent.click(within(dialog).getAllByRole('button', { name: 'Zapisz' })[0]);

  await vi.waitFor(() =>
    expect(sent).toContainEqual(
      expect.objectContaining({
        url: '/api/v1/pass-products/product-1',
        method: 'PATCH',
        body: expect.objectContaining({ removeMakeupPolicy: true }),
      }),
    ),
  );
});

test('the manage link in the product form opens the policies tab', async () => {
  mockApi([thirtyDays]);
  renderWithProviders(<PassesPage />);
  await userEvent.click(await screen.findByRole('button', { name: 'Dodaj karnet' }));
  const dialog = await screen.findByRole('dialog');

  await userEvent.click(within(dialog).getByRole('button', { name: 'Zarządzaj zasadami' }));

  expect(await screen.findByRole('tab', { name: 'Zasady odrobień', selected: true })).toBeVisible();
  const panel = within(await screen.findByRole('tabpanel', { name: 'Zasady odrobień' }));
  expect(await panel.findByText('Odrabianie 30 dni')).toBeInTheDocument();
});
