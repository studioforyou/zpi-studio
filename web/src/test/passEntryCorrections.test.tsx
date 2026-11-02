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

async function enterBalance(dialog: HTMLElement, value: string) {
  const input = within(dialog).getByLabelText(/^Nowa liczba pozostałych wejść/);
  await userEvent.clear(input);
  await userEvent.type(input, value);
}

test('sets a target balance, calculates the increase and records the optional reason', async () => {
  const { post } = api();
  const dialog = await openCorrection();
  const summary = within(dialog).getByRole('group', { name: 'Podsumowanie korekty' });
  expect(within(summary).getByText('Obecnie')).toBeInTheDocument();
  expect(within(summary).getByText('5')).toBeInTheDocument();
  expect(within(dialog).queryByText('Dodaj wejścia')).not.toBeInTheDocument();
  expect(within(dialog).queryByText('Odejmij wejścia')).not.toBeInTheDocument();
  await enterBalance(dialog, '15');
  expect(within(summary).getByText('Po korekcie')).toBeInTheDocument();
  expect(within(summary).getByText('15')).toBeInTheDocument();
  expect(within(summary).getByRole('status')).toHaveTextContent('+10 wejść');
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

test('calculates a decrease from the target balance and sends null without a reason', async () => {
  const { post } = api();
  const dialog = await openCorrection();
  await enterBalance(dialog, '3');
  expect(within(dialog).getByRole('status')).toHaveTextContent('−2 wejścia');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  await vi.waitFor(() => expect(post).toHaveBeenCalledOnce());
  expect(post.mock.calls[0][1]).toEqual({ entriesDelta: -2, reason: null, version: 0 });
});

test('rejects an empty or excessive balance and does not submit an unchanged balance', async () => {
  const { post } = api();
  const dialog = await openCorrection();
  const save = within(dialog).getByRole('button', { name: 'Potwierdź korektę' });
  await userEvent.click(save);
  expect(
    within(dialog).getByText('Podaj całkowitą liczbę pozostałych wejść od 0 do 32767.'),
  ).toBeInTheDocument();
  await enterBalance(dialog, '5');
  expect(save).toBeDisabled();
  expect(within(dialog).getByRole('status')).toHaveTextContent('Bez zmian');
  await enterBalance(dialog, '32768');
  await userEvent.click(save);
  expect(
    within(dialog).getByText('Podaj całkowitą liczbę pozostałych wejść od 0 do 32767.'),
  ).toBeInTheDocument();
  expect(post).not.toHaveBeenCalled();
});

test('allows setting the remaining balance to zero', async () => {
  const { post } = api();
  const dialog = await openCorrection();
  await enterBalance(dialog, '0');
  expect(within(dialog).getByRole('status')).toHaveTextContent('−5 wejść');
  expect(within(dialog).getByRole('group', { name: 'Podsumowanie korekty' })).toHaveTextContent(
    '0',
  );
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  await vi.waitFor(() => expect(post).toHaveBeenCalledOnce());
  expect(post.mock.calls[0][1]).toEqual({ entriesDelta: -5, reason: null, version: 0 });
});

test('allows the maximum target balance without adding it to the existing balance', async () => {
  const { post } = api();
  const dialog = await openCorrection();
  await enterBalance(dialog, '32767');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  await vi.waitFor(() => expect(post).toHaveBeenCalledOnce());
  expect(post.mock.calls[0][1]).toEqual({ entriesDelta: 32762, reason: null, version: 0 });
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
  await enterBalance(dialog, '7');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  const refresh = await within(dialog).findByRole('button', { name: 'Odśwież liczbę wejść' });
  expect(mocked.post).toHaveBeenCalledOnce();
  expect(
    within(dialog).queryByRole('button', { name: 'Potwierdź korektę' }),
  ).not.toBeInTheDocument();
  await userEvent.click(refresh);
  await vi.waitFor(() =>
    expect(
      within(within(dialog).getByRole('group', { name: 'Podsumowanie korekty' })).getByText('4'),
    ).toBeInTheDocument(),
  );
  expect(within(dialog).getByLabelText(/^Nowa liczba pozostałych wejść/)).toHaveValue('');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  expect(mocked.post).toHaveBeenCalledOnce();
  await enterBalance(dialog, '5');
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
  await enterBalance(dialog, '7');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź korektę' }));
  expect(await within(dialog).findByText(/Korekta mogła zostać zapisana/)).toBeInTheDocument();
  expect(mocked.post).toHaveBeenCalledOnce();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Odśwież liczbę wejść' }));
  await vi.waitFor(() =>
    expect(
      within(within(dialog).getByRole('group', { name: 'Podsumowanie korekty' })).getByText('7'),
    ).toBeInTheDocument(),
  );
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
  await enterBalance(dialog, '7');
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
  await enterBalance(dialog, '7');
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
