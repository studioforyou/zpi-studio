import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import type { Customer, CustomerPass } from '@/features/passes/customerPassesApi';
import { todayIsoDate } from '@/features/passes/passFormat';
import { PassesPage } from '@/features/passes/PassesPage';
import type { PassProduct } from '@/features/passes/passesApi';
import { renderWithProviders } from './renderWithProviders';

const katarzyna: Customer = {
  id: 'customer-1',
  kind: 'ADULT',
  firstName: 'Katarzyna',
  lastName: 'Zielińska',
  displayName: 'Katarzyna Zielińska',
  birthDate: '1995-04-12',
};

const adultProduct: PassProduct = {
  id: 'product-1',
  name: 'Karnet Pole Dance',
  description: null,
  kind: 'ENTRY_PASS',
  audience: 'ADULT',
  suspensionAllowed: false,
  maxSuspensionDays: null,
  isActive: true,
  serviceTypeIds: [],
  variants: [
    {
      id: 'variant-1',
      entriesCount: 8,
      validityDays: 30,
      price: { amountMinor: 26000, currency: 'PLN' },
      isActive: true,
      validFrom: '2026-10-01T00:00:00Z',
      validTo: null,
    },
  ],
};

const childProduct: PassProduct = {
  ...adultProduct,
  id: 'product-2',
  name: 'Karnet dziecięcy',
  audience: 'CHILD',
  variants: [{ ...adultProduct.variants[0], id: 'variant-2' }],
};

const assignedPass: CustomerPass = {
  id: 'pass-1',
  customerId: 'customer-1',
  variantId: 'variant-1',
  productName: 'Karnet Pole Dance',
  entriesTotal: 8,
  entriesRemaining: 5,
  pricePaid: { amountMinor: 26000, currency: 'PLN' },
  status: 'ACTIVE',
  validFrom: '2026-10-01',
  validUntil: '2026-10-30',
  orderItemId: null,
  createdBy: 'admin-1',
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
  version: 0,
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function mockApi(onPost: (url: string, body: unknown) => Response = () => json(assignedPass, 201)) {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      return Promise.resolve(onPost(url, JSON.parse(String(init.body ?? 'null'))));
    }
    if (url.startsWith('/api/v1/customers/customer-1/passes')) {
      return Promise.resolve(json({ items: [assignedPass] }));
    }
    if (url.startsWith('/api/v1/customers')) {
      return Promise.resolve(
        json({ items: [katarzyna], page: 0, size: 20, totalItems: 1, totalPages: 1 }),
      );
    }
    if (url.startsWith('/api/v1/service-types')) {
      return Promise.resolve(json({ items: [] }));
    }
    return Promise.resolve(json({ items: [adultProduct, childProduct] }));
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

async function chooseKatarzyna() {
  renderWithProviders(<PassesPage />);
  await userEvent.click(screen.getByRole('tab', { name: 'Karnety uczestników' }));
  await userEvent.click(screen.getByRole('combobox', { name: 'Uczestnik' }));
  await userEvent.click(
    await screen.findByRole('option', { name: 'Katarzyna Zielińska', hidden: true }),
  );
}

test('shows the passes of the chosen participant', async () => {
  const fetchMock = mockApi();
  await chooseKatarzyna();

  const panel = screen.getByRole('tabpanel');
  const row = (await within(panel).findByText('Karnet Pole Dance')).closest('tr');
  expect(row).not.toBeNull();
  expect(within(row!).getByText('5 z 8')).toBeInTheDocument();
  expect(within(row!).getByText('01.10.2026 – 30.10.2026')).toBeInTheDocument();
  expect(within(row!).getByText('Aktywny')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledWith('/api/v1/customers/customer-1/passes', expect.anything());
});

test('assigns a pass suited to the participant with the variant price', async () => {
  let sent: unknown;
  let sentTo = '';
  mockApi((url, body) => {
    sentTo = url;
    sent = body;
    return json(assignedPass, 201);
  });
  await chooseKatarzyna();
  await userEvent.click(screen.getByRole('button', { name: 'Przypisz karnet' }));
  const dialog = await screen.findByRole('dialog');

  await userEvent.click(within(dialog).getByRole('combobox', { name: /^Karnet/ }));
  expect(
    screen.queryByRole('option', { name: 'Karnet dziecięcy', hidden: true }),
  ).not.toBeInTheDocument();
  await userEvent.click(
    await screen.findByRole('option', { name: 'Karnet Pole Dance', hidden: true }),
  );
  await userEvent.type(within(dialog).getByLabelText(/^Notatka/), 'Płatność gotówką');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Przypisz' }));

  await vi.waitFor(() =>
    expect(sent).toEqual({
      variantId: 'variant-1',
      validFrom: todayIsoDate(),
      pricePaid: { amountMinor: 26000, currency: 'PLN' },
      reason: 'Płatność gotówką',
    }),
  );
  expect(sentTo).toBe('/api/v1/customers/customer-1/passes');
});

test('shows why the server rejected the assignment', async () => {
  mockApi(() => json({ status: 422, code: 'PASS_AUDIENCE_MISMATCH', detail: 'mismatch' }, 422));
  await chooseKatarzyna();
  await userEvent.click(screen.getByRole('button', { name: 'Przypisz karnet' }));
  const dialog = await screen.findByRole('dialog');

  await userEvent.click(within(dialog).getByRole('combobox', { name: /^Karnet/ }));
  await userEvent.click(
    await screen.findByRole('option', { name: 'Karnet Pole Dance', hidden: true }),
  );
  await userEvent.click(within(dialog).getByRole('button', { name: 'Przypisz' }));

  expect(
    await within(dialog).findByText('Ten karnet nie jest przeznaczony dla tego uczestnika.'),
  ).toBeInTheDocument();
});
