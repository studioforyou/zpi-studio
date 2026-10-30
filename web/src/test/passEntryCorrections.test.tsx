import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { CorrectPassEntriesModal } from '@/features/passes/CorrectPassEntriesModal';
import type { Customer, CustomerPass } from '@/features/passes/customerPassesApi';
import { PassesPage } from '@/features/passes/PassesPage';
import { renderWithProviders } from './renderWithProviders';

const customer: Customer = {
  id: 'customer-1',
  kind: 'ADULT',
  firstName: 'Katarzyna',
  lastName: 'Zielińska',
  displayName: 'Katarzyna Zielińska',
  birthDate: null,
};

const pass: CustomerPass = {
  id: 'pass-1',
  customerId: customer.id,
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

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function api(initial: CustomerPass = pass) {
  let stored = { ...initial };
  const post = vi.fn(async (_url: string, body: { entriesDelta: number; version: number }) => {
    stored = {
      ...stored,
      entriesRemaining: stored.entriesRemaining! + body.entriesDelta,
      version: body.version + 1,
    };
    return json(stored);
  });
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') return post(url, JSON.parse(String(init.body)));
    if (url === '/api/v1/customers/customer-1/passes/pass-1') return json(stored);
    if (url === '/api/v1/customers/customer-1/passes') return json({ items: [stored] });
    if (url.startsWith('/api/v1/customers?'))
      return json({ items: [customer], page: 0, size: 20, totalItems: 1, totalPages: 1 });
    return json({ items: [] });
  });
  vi.stubGlobal('fetch', fetchMock);
  return {
    post,
    fetchMock,
    setStored: (updated: CustomerPass) => {
      stored = updated;
    },
  };
}

async function chooseParticipant() {
  renderWithProviders(<PassesPage />);
  await userEvent.click(screen.getByRole('tab', { name: 'Karnety uczestników' }));
  await userEvent.click(screen.getByRole('combobox', { name: 'Uczestnik' }));
  await userEvent.click(
    await screen.findByRole('option', { name: customer.displayName, hidden: true }),
  );
}

async function openCorrection() {
  await chooseParticipant();
  await userEvent.click(await screen.findByRole('button', { name: 'Koryguj wejścia' }));
  return screen.findByRole('dialog');
}

async function enterAmount(dialog: HTMLElement, value: string) {
  const input = within(dialog).getByLabelText(/^Liczba wejść/);
  await userEvent.clear(input);
  await userEvent.type(input, value);
}

test('previews and adds entries, records the optional reason and refreshes the selected participant', async () => {
  const { post } = api();
  const dialog = await openCorrection();
  expect(within(dialog).getByText('Obecne saldo: 5')).toBeInTheDocument();
  await enterAmount(dialog, '10');
  expect(within(dialog).getByText('Saldo po korekcie: 15')).toBeInTheDocument();
  await userEvent.type(
    within(dialog).getByLabelText('Powód (opcjonalnie)'),
    '  Uzgodniona korekta  ',
  );
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(post).toHaveBeenCalledExactlyOnceWith(
    '/api/v1/customers/customer-1/passes/pass-1/correct-entries',
    { entriesDelta: 10, reason: 'Uzgodniona korekta', version: 0 },
  );
  expect(screen.getByText('15 (pierwotnie 8)')).toBeInTheDocument();
});

test('subtracts entries and sends null when no reason is entered', async () => {
  const { post } = api();
  const dialog = await openCorrection();
  await userEvent.click(within(dialog).getByText('Odejmij wejścia'));
  await enterAmount(dialog, '2');
  expect(within(dialog).getByText('Saldo po korekcie: 3')).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  await vi.waitFor(() => expect(post).toHaveBeenCalledOnce());
  expect(post.mock.calls[0][1]).toEqual({ entriesDelta: -2, reason: null, version: 0 });
});

test('does not submit zero, negative resulting balances or values outside storage limits', async () => {
  const { post } = api();
  const dialog = await openCorrection();
  const save = within(dialog).getByRole('button', { name: 'Potwierdź korektę' });
  await enterAmount(dialog, '0');
  await userEvent.click(save);
  expect(
    within(dialog).getByText('Podaj całkowitą liczbę wejść od 1 do 32767.'),
  ).toBeInTheDocument();
  await userEvent.click(within(dialog).getByText('Odejmij wejścia'));
  await enterAmount(dialog, '6');
  await userEvent.click(save);
  expect(
    within(dialog).getByText('Saldo po korekcie musi mieścić się w przedziale od 0 do 32767.'),
  ).toBeInTheDocument();
  await userEvent.click(within(dialog).getByText('Dodaj wejścia'));
  await enterAmount(dialog, '32767');
  await userEvent.click(save);
  expect(post).not.toHaveBeenCalled();
});

test('does not offer an entry correction for unlimited passes', async () => {
  api({ ...pass, entriesTotal: null, entriesRemaining: null });
  await chooseParticipant();
  expect(await screen.findByText('bez limitu')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Koryguj wejścia' })).not.toBeInTheDocument();
});

test('requires a fresh balance and a new deliberate correction after a version conflict', async () => {
  const mocked = api();
  mocked.post.mockImplementationOnce(async () => {
    mocked.setStored({ ...pass, entriesRemaining: 4, version: 1 });
    return json({ code: 'VERSION_CONFLICT', status: 409 }, 409);
  });
  const dialog = await openCorrection();
  await enterAmount(dialog, '2');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  const refresh = await within(dialog).findByRole('button', { name: 'Odśwież saldo' });
  expect(mocked.post).toHaveBeenCalledOnce();
  expect(
    within(dialog).queryByRole('button', { name: 'Potwierdź korektę' }),
  ).not.toBeInTheDocument();
  await userEvent.click(refresh);
  expect(await within(dialog).findByText('Obecne saldo: 4')).toBeInTheDocument();
  expect(within(dialog).getByLabelText(/^Liczba wejść/)).toHaveValue('');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  expect(mocked.post).toHaveBeenCalledOnce();
  await enterAmount(dialog, '1');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  await vi.waitFor(() => expect(mocked.post).toHaveBeenCalledTimes(2));
  expect(mocked.post.mock.calls[1][1]).toEqual({ entriesDelta: 1, reason: null, version: 1 });
});

test('does not repeat a request whose response was lost and lets the administrator check the saved balance', async () => {
  const mocked = api();
  mocked.post.mockImplementationOnce(async () => {
    mocked.setStored({ ...pass, entriesRemaining: 7, version: 1 });
    throw new TypeError('connection lost after commit');
  });
  const dialog = await openCorrection();
  await enterAmount(dialog, '2');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  expect(await within(dialog).findByText(/Korekta mogła zostać zapisana/)).toBeInTheDocument();
  expect(mocked.post).toHaveBeenCalledOnce();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Odśwież saldo' }));
  expect(await within(dialog).findByText('Obecne saldo: 7')).toBeInTheDocument();
  expect(mocked.post).toHaveBeenCalledOnce();
});

test('blocks duplicate confirmation and closing while a correction is being saved', async () => {
  const mocked = api();
  let finish!: (response: Response) => void;
  mocked.post.mockImplementationOnce(
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
  );
  const dialog = await openCorrection();
  await enterAmount(dialog, '2');
  await userEvent.dblClick(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  expect(mocked.post).toHaveBeenCalledOnce();
  expect(within(dialog).getByRole('button', { name: 'Anuluj' })).toBeDisabled();
  finish(json({ ...pass, entriesRemaining: 7, version: 1 }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});

test('a late result stays bound to its original participant after the view switches context', async () => {
  const mocked = api();
  let finish!: (response: Response) => void;
  mocked.post.mockImplementationOnce(
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
  );
  const closed = vi.fn();
  const otherCustomer = { ...customer, id: 'customer-2', displayName: 'Anna Nowak' };
  const view = renderWithProviders(
    <CorrectPassEntriesModal key={customer.id} customer={customer} pass={pass} onClose={closed} />,
  );
  const dialog = await screen.findByRole('dialog');
  await enterAmount(dialog, '2');
  fireEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  await vi.waitFor(() => expect(mocked.post).toHaveBeenCalledOnce());
  view.unmount();
  renderWithProviders(
    <CorrectPassEntriesModal
      key={otherCustomer.id}
      customer={otherCustomer}
      pass={{ ...pass, customerId: otherCustomer.id }}
      onClose={vi.fn()}
    />,
  );
  finish(json({ ...pass, entriesRemaining: 7, version: 1 }));
  expect(await screen.findByText('Anna Nowak')).toBeInTheDocument();
  expect(mocked.post.mock.calls[0][0]).toContain('/customers/customer-1/');
  expect(mocked.post).toHaveBeenCalledOnce();
});
