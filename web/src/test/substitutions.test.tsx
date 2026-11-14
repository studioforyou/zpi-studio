import { screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';
import { SubstitutionsPage } from '@/features/staff/SubstitutionsPage';
import type { Substitution } from '@/features/staff/substitutionApi';
import { renderWithProviders } from './renderWithProviders';

const example: Substitution = {
  id: 'request',
  eventId: 'event',
  version: 1,
  eventVersion: 0,
  status: 'CLAIMED',
  kind: 'PERSONAL_TRAINING',
  title: 'Trening personalny',
  rooms: 'Sala 1',
  startsAt: '2026-11-20T09:00:00Z',
  endsAt: '2026-11-20T10:00:00Z',
  originalStaffId: 'ewa',
  originalTrainer: 'Ewa Wiśniewska',
  substituteStaffId: 'marek',
  substituteTrainer: 'Marek Nowak',
  approvalRequired: true,
  decisionNote: null,
};
function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': status < 400 ? 'application/json' : 'application/problem+json' },
  });
}

function setup({ conflict = false, unavailable = false } = {}) {
  let current = { ...example };
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    if (url === '/api/v1/studio')
      return json({ name: 'Studio', timeZone: 'Europe/Warsaw', currency: 'PLN' });
    if (url === '/api/v1/staff') return json({ items: [] });
    if (url.startsWith('/api/v1/substitution-requests?')) return json({ items: [current] });
    if (url === '/api/v1/substitution-requests/request/candidates')
      return json({
        items: [
          {
            staffId: 'marek',
            displayName: 'Marek Nowak',
            available: !unavailable,
            reason: unavailable ? 'UNAVAILABLE' : null,
            warnings: [],
          },
        ],
      });
    if (url === '/api/v1/substitution-requests/request') return json(current);
    if (url === '/api/v1/substitution-requests/request/approve') {
      if (conflict) {
        current = { ...current, version: 2, status: 'EXPIRED' };
        return json({ code: 'VERSION_CONFLICT' }, 409);
      }
      current = { ...current, status: 'APPROVED', version: 2 };
      return json(current);
    }
    if (url === '/api/v1/substitution-requests/request/reject') {
      current = { ...current, status: 'REJECTED', version: 2 };
      return json(current);
    }
    if (url === '/api/v1/schedule-events/event/cancel') {
      current = { ...current, status: 'CANCELLED' };
      return new Response(null, { status: 204 });
    }
    throw new Error(`Unexpected request ${init.method}: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  renderWithProviders(<SubstitutionsPage />);
  return fetchMock;
}
async function open() {
  await userEvent.click(await screen.findByRole('button', { name: 'Otwórz: Trening personalny' }));
  return screen.findByRole('dialog', { name: 'Trening personalny' });
}
afterEach(() => vi.unstubAllGlobals());

test('approves the displayed version only after confirmation', async () => {
  const fetchMock = setup();
  const drawer = await open();
  const approve = await within(drawer).findByRole('button', { name: 'Zatwierdź zastępcę' });
  await waitFor(() => expect(approve).toBeEnabled());
  await userEvent.click(approve);
  const dialog = await screen.findByRole('dialog', { name: 'Zatwierdzić zastępcę?' });
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź' }));
  await waitFor(() =>
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/approve'))).toBe(true),
  );
  const call = fetchMock.mock.calls.find(([url]) => url.endsWith('/approve'))!;
  expect(JSON.parse(call[1].body as string)).toEqual({ version: 1, note: null });
  await waitFor(() =>
    expect(
      within(drawer).queryByRole('button', { name: 'Zatwierdź zastępcę' }),
    ).not.toBeInTheDocument(),
  );
});

test('disables approval of a candidate who became unavailable', async () => {
  setup({ unavailable: true });
  const drawer = await open();
  expect(await within(drawer).findByText('kolizja lub urlop')).toBeInTheDocument();
  expect(within(drawer).getByRole('button', { name: 'Zatwierdź zastępcę' })).toBeDisabled();
});

test('shows a version conflict and refreshes the request', async () => {
  setup({ conflict: true });
  const drawer = await open();
  const approve = await within(drawer).findByRole('button', { name: 'Zatwierdź zastępcę' });
  await waitFor(() => expect(approve).toBeEnabled());
  await userEvent.click(approve);
  const dialog = await screen.findByRole('dialog', { name: 'Zatwierdzić zastępcę?' });
  await userEvent.click(within(dialog).getByRole('button', { name: 'Potwierdź' }));
  expect(
    await within(dialog).findByText(
      'Zgłoszenie zmieniło się w międzyczasie. Sprawdź odświeżone dane.',
    ),
  ).toBeInTheDocument();
});

test('studio cancellation requires a reason and the current event version', async () => {
  const fetchMock = setup();
  const drawer = await open();
  await userEvent.click(await within(drawer).findByRole('button', { name: 'Odwołaj zajęcia' }));
  const dialog = await screen.findByRole('dialog', { name: 'Odwołać ten termin?' });
  const submit = within(dialog).getByRole('button', { name: 'Odwołaj zajęcia' });
  expect(submit).toBeDisabled();
  await userEvent.type(within(dialog).getByLabelText(/Powód odwołania/), 'Brak zastępcy');
  await userEvent.click(submit);
  await waitFor(() =>
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/cancel'))).toBe(true),
  );
  const call = fetchMock.mock.calls.find(([url]) => url.endsWith('/cancel'))!;
  expect(JSON.parse(call[1].body as string)).toEqual({ version: 0, reason: 'Brak zastępcy' });
});
