import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { RoomsPage } from '@/features/schedule/RoomsPage';
import { renderWithProviders } from './renderWithProviders';

const workoutRoom = {
  id: 'room-1',
  name: 'Siłownia',
  kind: 'ROOM',
  parentResourceId: null,
  maxPeople: 12,
  description: null,
  isActive: true,
};

const oldRoom = {
  ...workoutRoom,
  id: 'room-2',
  name: 'Stara sala',
  isActive: false,
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

type Route = (url: string, init: RequestInit) => Response | undefined;

/** Stubs `fetch`; each call is answered by the first route that returns a response. */
function stubApi(...routes: Route[]) {
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    for (const route of routes) {
      const response = route(url, init);
      if (response) {
        return response;
      }
    }
    throw new Error(`Unexpected request: ${init.method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const listRooms: Route = (url, init) =>
  url === '/api/v1/resources' && init.method === 'GET'
    ? json({ items: [workoutRoom, oldRoom] })
    : undefined;

function requestBody(fetchMock: ReturnType<typeof stubApi>, method: string, url: string): unknown {
  const call = fetchMock.mock.calls.find(
    ([callUrl, init]) => callUrl === url && init.method === method,
  );
  return call ? JSON.parse(call[1].body as string) : undefined;
}

test('lists active rooms and hides inactive ones', async () => {
  stubApi(listRooms);
  renderWithProviders(<RoomsPage />);

  const row = (await screen.findByText('Siłownia')).closest('tr')!;
  expect(within(row).getByText('12')).toBeInTheDocument();
  expect(screen.queryByText('Stara sala')).not.toBeInTheDocument();

  await userEvent.click(screen.getByLabelText('Pokaż nieaktywne'));

  expect(screen.getByText('Stara sala')).toBeInTheDocument();
});

const listServiceTypes: Route = (url) =>
  url === '/api/v1/service-types?active=true'
    ? json({
        items: [
          { id: 'group', name: 'Kalistenika – grupa' },
          { id: 'personal', name: 'Trening personalny' },
        ],
      })
    : undefined;

test('creates a room in two steps: details, then allowed services', async () => {
  const fetchMock = stubApi(listRooms, listServiceTypes, (url, init) =>
    url === '/api/v1/resources' && init.method === 'POST' ? json(workoutRoom, 201) : undefined,
  );
  renderWithProviders(<RoomsPage />);
  await screen.findByText('Siłownia');

  await userEvent.click(screen.getByRole('button', { name: 'Dodaj salę' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/Nazwa/), 'Sala pole dance');
  await userEvent.type(within(dialog).getByLabelText(/Maksymalna liczba osób/), '8');
  expect(within(dialog).queryByRole('button', { name: 'Zapisz' })).not.toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dalej' }));

  await userEvent.click(await within(dialog).findByLabelText('Dozwolone usługi'));
  // The dropdown's opening transition never finishes in jsdom, so its options count as hidden.
  await userEvent.click(
    await screen.findByRole('option', { name: 'Trening personalny', hidden: true }),
  );
  await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Trening personalny' }));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  await vi.waitFor(() =>
    expect(requestBody(fetchMock, 'POST', '/api/v1/resources')).toEqual({
      name: 'Sala pole dance',
      kind: 'ROOM',
      parentResourceId: null,
      maxPeople: 8,
      description: null,
      serviceTypes: [{ serviceTypeId: 'personal', isDefault: true }],
    }),
  );
});

test('does not go to the second step without a room name', async () => {
  stubApi(listRooms, listServiceTypes);
  renderWithProviders(<RoomsPage />);
  await screen.findByText('Siłownia');

  await userEvent.click(screen.getByRole('button', { name: 'Dodaj salę' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dalej' }));

  expect(within(dialog).getByText('Podaj nazwę sali.')).toBeInTheDocument();
  expect(within(dialog).queryByLabelText('Dozwolone usługi')).not.toBeInTheDocument();
});

test('shows the backend error when the room name is taken', async () => {
  stubApi(listRooms, listServiceTypes, (url, init) =>
    url === '/api/v1/resources' && init.method === 'POST'
      ? json({ status: 409, code: 'RESOURCE_NAME_ALREADY_USED' }, 409)
      : undefined,
  );
  renderWithProviders(<RoomsPage />);
  await screen.findByText('Siłownia');

  await userEvent.click(screen.getByRole('button', { name: 'Dodaj salę' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/Nazwa/), 'Siłownia');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dalej' }));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  expect(await within(dialog).findByText('Sala o tej nazwie już istnieje.')).toBeInTheDocument();
});

test('edits a room and keeps its current services unless they are changed', async () => {
  const fetchMock = stubApi(
    listRooms,
    listServiceTypes,
    (url, init) =>
      url === '/api/v1/resources/room-1/service-types' && init.method === 'GET'
        ? json({
            items: [
              { serviceTypeId: 'group', serviceTypeName: 'Kalistenika – grupa', isDefault: true },
            ],
          })
        : undefined,
    (url, init) =>
      url === '/api/v1/resources/room-1' && init.method === 'PATCH' ? json(workoutRoom) : undefined,
  );
  renderWithProviders(<RoomsPage />);
  await screen.findByText('Siłownia');

  await userEvent.click(screen.getByRole('button', { name: 'Edytuj' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByLabelText(/Nazwa/)).toHaveValue('Siłownia');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dalej' }));
  expect(
    await within(dialog).findByRole('checkbox', { name: 'Kalistenika – grupa' }),
  ).toBeChecked();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));

  await vi.waitFor(() =>
    expect(requestBody(fetchMock, 'PATCH', '/api/v1/resources/room-1')).toMatchObject({
      name: 'Siłownia',
      isActive: true,
      serviceTypes: [{ serviceTypeId: 'group', isDefault: true }],
    }),
  );
});
