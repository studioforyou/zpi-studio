import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import type { Customer, CustomerPass } from '@/features/passes/customerPassesApi';
import { addDays, formatDate, todayIsoDate } from '@/features/passes/passFormat';
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
  suspensions: [],
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function mockApi(
  onPost: (url: string, body: unknown) => Response = () => json(assignedPass, 201),
  passes: CustomerPass[] = [assignedPass],
  products: PassProduct[] = [adultProduct, childProduct],
) {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      return Promise.resolve(onPost(url, JSON.parse(String(init.body ?? 'null'))));
    }
    if (url.startsWith('/api/v1/customers/customer-1/passes')) {
      return Promise.resolve(json({ items: passes }));
    }
    if (url.startsWith('/api/v1/customers')) {
      return Promise.resolve(
        json({ items: [katarzyna], page: 0, size: 20, totalItems: 1, totalPages: 1 }),
      );
    }
    if (url.startsWith('/api/v1/service-types')) {
      return Promise.resolve(json({ items: [] }));
    }
    return Promise.resolve(json({ items: products }));
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

/** A pass valid from today for 30 days, so its dates are never in the past. */
function currentPass(overrides: Partial<CustomerPass> = {}): CustomerPass {
  return {
    ...assignedPass,
    validFrom: todayIsoDate(),
    validUntil: addDays(todayIsoDate(), 29),
    ...overrides,
  };
}

async function openPassAction(name: string) {
  await chooseKatarzyna();
  const panel = screen.getByRole('tabpanel');
  const row = (await within(panel).findByText('Karnet Pole Dance')).closest('tr');
  await userEvent.click(within(row!).getByRole('button', { name }));
  return screen.findByRole('dialog');
}

test('extends a pass to a later day', async () => {
  const pass = currentPass({ version: 3 });
  let sent: unknown;
  let sentTo = '';
  mockApi(
    (url, body) => {
      sentTo = url;
      sent = body;
      return json(pass);
    },
    [pass],
  );
  const dialog = await openPassAction('Przedłuż');
  const newEnd = addDays(todayIsoDate(), 44);

  await userEvent.type(within(dialog).getByLabelText(/^Nowa data końca/), newEnd);
  expect(within(dialog).getByText('Karnet będzie dłuższy o 15 dni.')).toBeInTheDocument();
  await userEvent.type(within(dialog).getByLabelText(/^Notatka/), 'Kontuzja');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  await vi.waitFor(() =>
    expect(sent).toEqual({ validUntil: newEnd, reason: 'Kontuzja', version: 3 }),
  );
  expect(sentTo).toBe('/api/v1/customers/customer-1/passes/pass-1/extend');
});

test('suspends a pass when its product allows it', async () => {
  const pass = currentPass();
  let sent: unknown;
  let sentTo = '';
  mockApi(
    (url, body) => {
      sentTo = url;
      sent = body;
      return json(pass);
    },
    [pass],
    [{ ...adultProduct, suspensionAllowed: true, maxSuspensionDays: 14 }],
  );
  const dialog = await openPassAction('Zawieś');
  const end = addDays(todayIsoDate(), 6);

  expect(
    within(dialog).getByText('Karnet można zawiesić łącznie na 14 dni, wykorzystano 0.'),
  ).toBeInTheDocument();
  await userEvent.type(within(dialog).getByLabelText(/^Do/), end);
  expect(
    within(dialog).getByText(
      `Zawieszenie na 7 dni przesunie koniec karnetu na ${formatDate(addDays(todayIsoDate(), 36))}.`,
    ),
  ).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  await vi.waitFor(() =>
    expect(sent).toEqual({ startsOn: todayIsoDate(), endsOn: end, reason: null, version: 0 }),
  );
  expect(sentTo).toBe('/api/v1/customers/customer-1/passes/pass-1/suspend');
});

test('does not offer a suspension the product does not allow', async () => {
  mockApi(undefined, [currentPass()]);
  const dialog = await openPassAction('Zawieś');

  expect(
    await within(dialog).findByText('Zasady tego karnetu nie pozwalają na zawieszenie.'),
  ).toBeInTheDocument();
  expect(within(dialog).queryByRole('button', { name: 'Zapisz' })).not.toBeInTheDocument();
});

test('cancels a suspension that has not ended', async () => {
  const suspension = {
    id: 'suspension-1',
    startsOn: addDays(todayIsoDate(), 3),
    endsOn: addDays(todayIsoDate(), 5),
    reason: 'Wyjazd',
    createdBy: 'admin-1',
    createdAt: '2026-10-01T10:00:00Z',
  };
  const pass = currentPass({ version: 1, suspensions: [suspension] });
  let sent: unknown;
  let sentTo = '';
  mockApi(
    (url, body) => {
      sentTo = url;
      sent = body;
      return json(pass);
    },
    [pass],
    [{ ...adultProduct, suspensionAllowed: true }],
  );
  const dialog = await openPassAction('Zawieś');

  const row = within(dialog).getByText('Wyjazd').closest('tr');
  await userEvent.click(within(row!).getByRole('button', { name: 'Anuluj' }));

  await vi.waitFor(() => expect(sent).toEqual({ version: 1 }));
  expect(sentTo).toBe('/api/v1/customers/customer-1/passes/pass-1/suspensions/suspension-1/cancel');
});

test('shows why the server rejected the suspension', async () => {
  mockApi(
    () => json({ status: 422, code: 'PASS_SUSPENSION_NOT_ALLOWED', detail: 'limit' }, 422),
    [currentPass()],
    [{ ...adultProduct, suspensionAllowed: true, maxSuspensionDays: 5 }],
  );
  const dialog = await openPassAction('Zawieś');
  await userEvent.type(within(dialog).getByLabelText(/^Do/), addDays(todayIsoDate(), 9));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  expect(await within(dialog).findByText(/niezgodne z zasadami karnetu/)).toBeInTheDocument();
});
