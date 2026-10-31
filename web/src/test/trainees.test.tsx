import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import type { Customer } from '@/features/passes/customerPassesApi';
import type { AssignableTrainer, CustomerTrainer } from '@/features/trainings/trainees/traineesApi';
import { TrainingsPage } from '@/features/trainings/TrainingsPage';
import { renderWithProviders } from './renderWithProviders';

const katarzyna: Customer = {
  id: 'customer-1',
  kind: 'ADULT',
  firstName: 'Katarzyna',
  lastName: 'Zielińska',
  displayName: 'Katarzyna Zielińska',
  birthDate: '1995-04-12',
};

const zosia: Customer = {
  id: 'customer-2',
  kind: 'CHILD',
  firstName: 'Zosia',
  lastName: 'Dąbrowska',
  displayName: 'Zosia Dąbrowska',
  birthDate: null,
};

const marek: AssignableTrainer = {
  staffId: 'staff-1',
  firstName: 'Marek',
  lastName: 'Nowak',
  displayName: 'Marek Nowak',
};

const ewa: AssignableTrainer = {
  staffId: 'staff-2',
  firstName: 'Ewa',
  lastName: 'Wiśniewska',
  displayName: 'Ewa Wiśniewska',
};

const marekAssigned: CustomerTrainer = {
  ...marek,
  isActive: true,
  assignedBy: 'admin-1',
  assignedAt: '2026-10-01T10:00:00Z',
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

type Overrides = {
  onPost?: (url: string, body: unknown) => Response;
  onDelete?: (url: string) => Response;
};

function mockApi({
  onPost = () => json({ ...marekAssigned, ...ewa }, 201),
  onDelete = () => new Response(null, { status: 204 }),
}: Overrides = {}) {
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      return Promise.resolve(onPost(url, JSON.parse(String(init.body ?? 'null'))));
    }
    if (init?.method === 'DELETE') {
      return Promise.resolve(onDelete(url));
    }
    if (url === '/api/v1/customers/assignable-trainers') {
      return Promise.resolve(json({ items: [marek, ewa] }));
    }
    if (url === '/api/v1/customers/customer-1/trainers') {
      return Promise.resolve(json({ items: [marekAssigned] }));
    }
    if (url.startsWith('/api/v1/customers')) {
      return Promise.resolve(
        json({ items: [katarzyna, zosia], page: 0, size: 20, totalItems: 2, totalPages: 1 }),
      );
    }
    return Promise.resolve(json({ items: [] }));
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

async function openKatarzynasTrainers() {
  renderWithProviders(<TrainingsPage />);
  await userEvent.click(screen.getByRole('tab', { name: 'Trenujący' }));
  const row = (await screen.findByText('Katarzyna Zielińska')).closest('tr')!;
  await userEvent.click(within(row).getByRole('button', { name: 'Przypisz trenera' }));
  return screen.findByRole('dialog', { name: 'Trenerzy: Katarzyna Zielińska' });
}

test('lists the studio participants with a button to assign a trainer', async () => {
  mockApi();
  renderWithProviders(<TrainingsPage />);
  await userEvent.click(screen.getByRole('tab', { name: 'Trenujący' }));

  const zosiaRow = (await screen.findByText('Zosia Dąbrowska')).closest('tr')!;
  expect(within(zosiaRow).getByText('dziecko')).toBeInTheDocument();
  expect(within(zosiaRow).getByRole('button', { name: 'Przypisz trenera' })).toBeInTheDocument();
  const katarzynaRow = screen.getByText('Katarzyna Zielińska').closest('tr')!;
  expect(within(katarzynaRow).getByText('12.04.1995')).toBeInTheDocument();
});

test('shows the assigned trainers and adds another one', async () => {
  const fetchMock = mockApi();
  const dialog = await openKatarzynasTrainers();

  expect(await within(dialog).findByText('Marek Nowak')).toBeInTheDocument();
  expect(within(dialog).getByText('od 01.10.2026')).toBeInTheDocument();

  await userEvent.click(within(dialog).getByRole('combobox', { name: 'Nowy trener' }));
  // An already assigned trainer is not offered again.
  expect(screen.queryByRole('option', { name: 'Marek Nowak', hidden: true })).toBeNull();
  await userEvent.click(
    await screen.findByRole('option', { name: 'Ewa Wiśniewska', hidden: true }),
  );
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dodaj' }));

  expect(fetchMock).toHaveBeenCalledWith(
    '/api/v1/customers/customer-1/trainers',
    expect.objectContaining({ method: 'POST', body: JSON.stringify({ staffId: 'staff-2' }) }),
  );
});

test('removes an assigned trainer', async () => {
  const fetchMock = mockApi();
  const dialog = await openKatarzynasTrainers();

  await userEvent.click(
    await within(dialog).findByRole('button', { name: 'Usuń trenera Marek Nowak' }),
  );

  expect(fetchMock).toHaveBeenCalledWith(
    '/api/v1/customers/customer-1/trainers/staff-1',
    expect.objectContaining({ method: 'DELETE' }),
  );
});

test('explains why a trainer could not be assigned', async () => {
  mockApi({
    onPost: () =>
      json({ status: 409, code: 'TRAINER_ALREADY_ASSIGNED', detail: 'Already assigned.' }, 409),
  });
  const dialog = await openKatarzynasTrainers();

  await userEvent.click(within(dialog).getByRole('combobox', { name: 'Nowy trener' }));
  await userEvent.click(
    await screen.findByRole('option', { name: 'Ewa Wiśniewska', hidden: true }),
  );
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dodaj' }));

  expect(
    await within(dialog).findByText('Ten trener jest już przypisany do klienta.'),
  ).toBeInTheDocument();
});
