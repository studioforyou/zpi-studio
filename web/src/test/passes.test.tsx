import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import { PassesPage } from '@/features/passes/PassesPage';
import type { PassProduct, ServiceType } from '@/features/passes/passesApi';
import { renderWithProviders } from './renderWithProviders';

const poleDance: ServiceType = {
  id: 'service-1',
  name: 'Pole dance',
  category: 'GROUP_CLASS',
  level: 'BEGINNER',
  isActive: true,
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
  serviceTypeIds: ['service-1'],
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

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function mockApi(onPost: (url: string, body: unknown) => Response = () => json(product, 201)) {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      return Promise.resolve(onPost(url, JSON.parse(String(init.body ?? 'null'))));
    }
    if (url.startsWith('/api/v1/service-types')) {
      return Promise.resolve(json({ items: [poleDance] }));
    }
    return Promise.resolve(json({ items: [product] }));
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

test('lists the active pass products', async () => {
  const fetchMock = mockApi();
  renderWithProviders(<PassesPage />);

  const row = (await screen.findByText('Karnet Pole Dance')).closest('tr');
  expect(row).not.toBeNull();
  expect(within(row!).getByText('Karnet na wejścia')).toBeInTheDocument();
  expect(within(row!).getByText('Dorośli')).toBeInTheDocument();
  expect(await within(row!).findByText('Pole dance')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledWith('/api/v1/pass-products?active=true', expect.anything());
});

test('creates a pass product with its first variant', async () => {
  let sent: unknown;
  mockApi((_url, body) => {
    sent = body;
    return json(product, 201);
  });
  renderWithProviders(<PassesPage />);
  await screen.findByText('Karnet Pole Dance');

  await userEvent.click(screen.getByRole('button', { name: 'Dodaj karnet' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/Nazwa/), 'Karnet Open');
  await userEvent.type(within(dialog).getByLabelText('Liczba wejść'), '4');
  await userEvent.type(within(dialog).getByLabelText('Cena (PLN)'), '180');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  await vi.waitFor(() =>
    expect(sent).toMatchObject({
      name: 'Karnet Open',
      kind: 'ENTRY_PASS',
      audience: 'ANY',
      variants: [
        { entriesCount: 4, validityDays: 30, price: { amountMinor: 18000, currency: 'PLN' } },
      ],
    }),
  );
});

test('shows why the server rejected a new variant', async () => {
  mockApi(() =>
    json({ status: 409, code: 'PASS_VARIANT_ALREADY_EXISTS', detail: 'duplicate' }, 409),
  );
  renderWithProviders(<PassesPage />);
  await userEvent.click(await screen.findByText('Karnet Pole Dance'));
  const dialog = await screen.findByRole('dialog');

  await userEvent.type(within(dialog).getByLabelText('Liczba wejść'), '8');
  await userEvent.type(within(dialog).getByLabelText('Cena (PLN)'), '100');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dodaj wariant' }));

  expect(
    await within(dialog).findByText(
      'Aktywny wariant z taką liczbą wejść i ważnością już istnieje.',
    ),
  ).toBeInTheDocument();
});
