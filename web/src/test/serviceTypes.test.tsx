import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { TrainingsPage } from '@/features/trainings/TrainingsPage';
import { renderWithProviders } from './renderWithProviders';

const studioPolicy = {
  cancellationAllowed: true,
  freeCancelHours: 12,
  lateCancelAllowed: true,
  lateCancelForfeitsEntry: true,
  bookingOpensDaysBefore: 14,
  bookingClosesMinutesBefore: 30,
  waitlistEnabled: true,
  waitlistConfirmMinutes: 0,
  autoCancelEnabled: false,
  autoCancelMinParticipants: 3,
  autoCancelHoursBefore: 3,
};

const service = {
  id: 'service-1',
  name: 'Pole dance',
  description: 'Opis zajęć',
  category: 'GROUP_CLASS',
  level: 'BEGINNER',
  minAge: 16,
  maxAge: null,
  defaultDurationMinutes: 60,
  minParticipants: 3,
  maxParticipants: 12,
  calendarColor: '#2196F3',
  isActive: true,
  bookingPolicy: { overrides: null, effective: studioPolicy },
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });
}

type Route = (url: string, init: RequestInit) => Response | undefined;

function stubApi(...routes: Route[]) {
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    for (const route of routes) {
      const response = route(url, init);
      if (response) return response;
    }
    if (url === '/api/v1/service-types/booking-defaults') return json(studioPolicy);
    throw new Error(`Unexpected request: ${init.method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const list: Route = (url, init) =>
  url === '/api/v1/service-types' && init.method === 'GET'
    ? json({ items: [service, { ...service, id: 'old', name: 'Stara joga', isActive: false }] })
    : undefined;

test('filters the service catalogue by search and activity within the training section', async () => {
  const fetchMock = stubApi(list);
  renderWithProviders(<TrainingsPage />);
  expect(screen.getByRole('tab', { name: 'Konfigurator zajęć' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  expect(screen.getByRole('tab', { name: 'Stałe grupy' })).toBeDisabled();
  const row = (await screen.findByText('Pole dance')).closest('tr')!;
  expect(within(row).getByText('Początkujący')).toBeInTheDocument();
  expect(within(row).getByText('60 min')).toBeInTheDocument();
  expect(within(row).getByText('Wiek: Od 16')).toBeInTheDocument();
  expect(within(row).getByText('3–12')).toBeInTheDocument();
  expect(screen.queryByText('Stara joga')).not.toBeInTheDocument();
  await userEvent.click(screen.getByLabelText('Pokaż nieaktywne'));
  expect(screen.getByText('Stara joga')).toBeInTheDocument();
  const search = screen.getByRole('textbox', { name: 'Szukaj usługi' });
  await userEvent.type(search, '  JOGA  ');
  expect(screen.getByText('Stara joga')).toBeInTheDocument();
  expect(screen.queryByText('Pole dance')).not.toBeInTheDocument();
  await userEvent.click(screen.getByLabelText('Pokaż nieaktywne'));
  expect(screen.getByText('Nie znaleziono usług pasujących do wyszukiwania.')).toBeInTheDocument();
  await userEvent.clear(search);
  expect(screen.getByText('Pole dance')).toBeInTheDocument();
  await userEvent.type(search, 'Opis zajęć');
  expect(screen.getByText('Pole dance')).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

test('creates a service, clears omitted limits and refreshes the list', async () => {
  let created: Record<string, unknown> | undefined;
  stubApi((url, init) => {
    if (url !== '/api/v1/service-types') return undefined;
    if (init.method === 'POST') {
      created = JSON.parse(init.body as string);
      return json({ ...created, id: 'new', isActive: true }, 201);
    }
    return json({ items: created ? [{ ...created, id: 'new', isActive: true }] : [] });
  });
  renderWithProviders(<TrainingsPage />);
  await userEvent.click(screen.getByRole('button', { name: 'Dodaj usługę' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).queryByLabelText(/Minimalny wiek/)).not.toBeInTheDocument();
  expect(within(dialog).queryByLabelText(/^Poziom/)).not.toBeInTheDocument();
  expect(within(dialog).queryByLabelText(/Minimalna liczba uczestników/)).not.toBeInTheDocument();
  expect(within(dialog).queryByLabelText(/^Domyślny limit miejsc/)).not.toBeInTheDocument();
  await userEvent.type(within(dialog).getByLabelText(/Nazwa/), '  Nowe zajęcia  ');
  await userEvent.click(within(dialog).getByRole('switch', { name: /^Ogranicz wiek uczestników/ }));
  await userEvent.type(within(dialog).getByLabelText(/Minimalny wiek/), '16');
  await userEvent.click(within(dialog).getByLabelText(/Kategoria/));
  await userEvent.click(await screen.findByRole('option', { name: 'Warsztaty', hidden: true }));
  await userEvent.click(within(dialog).getByRole('switch', { name: /^Określ poziom/ }));
  await userEvent.click(within(dialog).getByLabelText(/^Poziom/));
  await userEvent.click(await screen.findByRole('option', { name: 'Zaawansowany', hidden: true }));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  await screen.findByText('Nowe zajęcia');
  expect(created).toEqual({
    name: 'Nowe zajęcia',
    description: null,
    category: 'WORKSHOP',
    level: 'ADVANCED',
    defaultDurationMinutes: 60,
    minAge: 16,
    maxAge: null,
    minParticipants: null,
    maxParticipants: null,
    calendarColor: null,
    bookingPolicy: null,
  });
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
}, 10000);

test('validates the required name and inconsistent participation ranges without sending', async () => {
  const fetchMock = stubApi(list);
  renderWithProviders(<TrainingsPage />);
  await userEvent.click(screen.getByRole('button', { name: 'Dodaj usługę' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  expect(within(dialog).getByText('Podaj nazwę usługi.')).toBeInTheDocument();
  await userEvent.type(within(dialog).getByLabelText(/Nazwa/), 'Zajęcia');
  for (const name of [
    /^Ogranicz wiek uczestników/,
    /^Określ poziom/,
    /^Ustal domyślny limit miejsc/,
  ]) {
    await userEvent.click(within(dialog).getByRole('switch', { name }));
  }
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  expect(
    within(dialog).getByText('Podaj przynajmniej jedną granicę wieku lub wyłącz ograniczenie.'),
  ).toBeInTheDocument();
  expect(
    within(dialog).getByText('Wybierz poziom lub wyłącz jego określanie.'),
  ).toBeInTheDocument();
  expect(within(dialog).getAllByText('Wpisz dodatnią liczbę całkowitą.')).toHaveLength(1);
  await userEvent.type(within(dialog).getByLabelText(/Minimalny wiek/), '18');
  await userEvent.type(within(dialog).getByLabelText(/Maksymalny wiek/), '12');
  await userEvent.type(within(dialog).getByLabelText(/^Domyślny limit miejsc/), '4');
  await userEvent.click(within(dialog).getByRole('tab', { name: 'Zapisy i odwoływanie' }));
  await userEvent.click(
    within(dialog).getByRole('switch', { name: /^Ustaw własne minimum uczestników/ }),
  );
  await userEvent.type(within(dialog).getByLabelText(/Minimalna liczba uczestników/), '8');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  expect(
    within(dialog).getByText('Maksymalny wiek nie może być mniejszy od minimalnego.'),
  ).toBeInTheDocument();
  expect(
    within(dialog).getByText('Maksymalna liczba uczestników nie może być mniejsza od minimalnej.'),
  ).toBeInTheDocument();
  expect(fetchMock.mock.calls.some(([, init]) => init.method === 'POST')).toBe(false);
});

test('edits existing values, clears optional rules and deactivates a service', async () => {
  let update: Record<string, unknown> | undefined;
  let current: Record<string, unknown> = service;
  stubApi((url, init) => {
    if (url === '/api/v1/service-types' && init.method === 'GET') {
      return json({ items: [current] });
    }
    if (url === '/api/v1/service-types/service-1' && init.method === 'PATCH') {
      update = JSON.parse(init.body as string);
      current = { ...current, ...update, bookingPolicy: service.bookingPolicy };
      return json(current);
    }
    return undefined;
  });
  renderWithProviders(<TrainingsPage />);
  const row = (await screen.findByText('Pole dance')).closest('tr')!;
  await userEvent.click(within(row).getByRole('button', { name: 'Edytuj' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByLabelText(/^Poziom/)).toHaveValue('Początkujący');
  await userEvent.clear(within(dialog).getByLabelText('Opis'));
  for (const name of [
    /^Ogranicz wiek uczestników/,
    /^Określ poziom/,
    /^Ustal domyślny limit miejsc/,
  ]) {
    const toggle = within(dialog).getByRole('switch', { name });
    expect(toggle).toBeChecked();
    await userEvent.click(toggle);
  }
  expect(within(dialog).queryByLabelText(/Minimalny wiek/)).not.toBeInTheDocument();
  expect(within(dialog).queryByLabelText(/^Poziom/)).not.toBeInTheDocument();
  expect(within(dialog).queryByLabelText(/Minimalna liczba uczestników/)).not.toBeInTheDocument();
  expect(within(dialog).queryByLabelText(/^Domyślny limit miejsc/)).not.toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('tab', { name: 'Zapisy i odwoływanie' }));
  const minimumToggle = within(dialog).getByRole('switch', {
    name: /^Ustaw własne minimum uczestników/,
  });
  expect(minimumToggle).toBeChecked();
  await userEvent.click(minimumToggle);
  await userEvent.click(within(dialog).getByRole('tab', { name: 'Dane usługi' }));
  await userEvent.click(within(dialog).getByRole('switch', { name: /^Usługa aktywna/ }));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  await waitFor(() =>
    expect(update).toMatchObject({
      description: null,
      minAge: null,
      maxAge: null,
      level: null,
      minParticipants: null,
      maxParticipants: null,
      isActive: false,
    }),
  );
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(screen.queryByText('Pole dance')).not.toBeInTheDocument();
  await userEvent.click(screen.getByLabelText('Pokaż nieaktywne'));
  const savedRow = (await screen.findByText('Pole dance')).closest('tr')!;
  expect(within(savedRow).getByText('Nie określono')).toBeInTheDocument();
  await userEvent.click(within(savedRow).getByRole('button', { name: 'Edytuj' }));
  const savedDialog = await screen.findByRole('dialog');
  expect(
    within(savedDialog).getByRole('switch', { name: /^Ogranicz wiek uczestników/ }),
  ).not.toBeChecked();
  expect(within(savedDialog).getByRole('switch', { name: /^Określ poziom/ })).not.toBeChecked();
});

test('keeps entered values after a failed save and allows a retry', async () => {
  let attempts = 0;
  stubApi(list, (url, init) => {
    if (url === '/api/v1/service-types' && init.method === 'POST') {
      attempts++;
      return attempts === 1 ? json({ code: 'INTERNAL_ERROR' }, 500) : json(service, 201);
    }
    return undefined;
  });
  renderWithProviders(<TrainingsPage />);
  await userEvent.click(screen.getByRole('button', { name: 'Dodaj usługę' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/Nazwa/), 'Wpisane zajęcia');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  expect(
    await within(dialog).findByText(
      'Nie udało się zapisać zmian. Sprawdź dane i spróbuj ponownie.',
    ),
  ).toBeInTheDocument();
  expect(within(dialog).getByLabelText(/Nazwa/)).toHaveValue('Wpisane zajęcia');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(attempts).toBe(2);
});

test('offers retry when the catalogue cannot be loaded', async () => {
  let attempts = 0;
  stubApi(() =>
    ++attempts === 1 ? json({ code: 'INTERNAL_ERROR' }, 500) : json({ items: [service] }),
  );
  renderWithProviders(<TrainingsPage />);
  await screen.findByText('Nie udało się wczytać usług.');
  await userEvent.click(screen.getByRole('button', { name: 'Spróbuj ponownie' }));
  expect(await screen.findByText('Pole dance')).toBeInTheDocument();
});

test('blocks saving when studio rules cannot be loaded and lets the administrator retry', async () => {
  let attempts = 0;
  stubApi(list, (url) => {
    if (url === '/api/v1/service-types/booking-defaults') {
      return ++attempts === 1 ? json({ code: 'INTERNAL_ERROR' }, 500) : json(studioPolicy);
    }
    return undefined;
  });
  renderWithProviders(<TrainingsPage />);
  await userEvent.click(screen.getByRole('button', { name: 'Dodaj usługę' }));
  const dialog = await screen.findByRole('dialog');
  expect(
    await within(dialog).findByText('Nie udało się wczytać zasad studia.'),
  ).toBeInTheDocument();
  expect(within(dialog).getByRole('button', { name: 'Zapisz' })).toBeDisabled();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Spróbuj ponownie' }));
  await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Zapisz' })).toBeEnabled());
});

test('personal training saves a small group capacity and cancellation deadline without group rules', async () => {
  let created: Record<string, unknown> | undefined;
  stubApi(list, (url, init) => {
    if (url === '/api/v1/service-types' && init.method === 'POST') {
      created = JSON.parse(init.body as string);
      return json({ ...service, ...created }, 201);
    }
    return undefined;
  });
  renderWithProviders(<TrainingsPage />);
  await userEvent.click(screen.getByRole('button', { name: 'Dodaj usługę' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.type(within(dialog).getByLabelText(/Nazwa/), 'Trening w małej grupie');
  await userEvent.click(within(dialog).getByLabelText(/Kategoria/));
  await userEvent.click(
    await screen.findByRole('option', { name: 'Trening personalny', hidden: true }),
  );
  const capacity = within(dialog).getByLabelText(/^Domyślny limit miejsc/);
  await userEvent.clear(capacity);
  await userEvent.type(capacity, '3');
  await userEvent.click(within(dialog).getByRole('tab', { name: 'Zapisy i odwoływanie' }));
  const minimum = within(dialog).getByLabelText(/Minimalna liczba uczestników/);
  await userEvent.clear(minimum);
  await userEvent.type(minimum, '2');
  await userEvent.click(within(dialog).getByRole('switch', { name: /^Użyj zasad studia/ }));
  expect(within(dialog).queryByRole('switch', { name: /^Odwołuj przy/ })).not.toBeInTheDocument();
  expect(
    within(dialog).queryByRole('switch', { name: /^Lista rezerwowa/ }),
  ).not.toBeInTheDocument();
  const hours = within(dialog).getByLabelText(/^Rezygnacja bez utraty wejścia/);
  await userEvent.clear(hours);
  await userEvent.type(hours, '6');
  await userEvent.click(within(dialog).getByLabelText('Po upływie tego terminu'));
  await userEvent.click(
    await screen.findByRole('option', { name: 'Zablokuj rezygnację', hidden: true }),
  );
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  await waitFor(() =>
    expect(created).toMatchObject({
      category: 'PERSONAL_TRAINING',
      minParticipants: 2,
      maxParticipants: 3,
      bookingPolicy: {
        freeCancelHours: 6,
        lateCancelAllowed: false,
        autoCancelEnabled: false,
        waitlistEnabled: false,
      },
    }),
  );
}, 10000);

test('saves workshop cancellation settings and reloads them when editing again', async () => {
  let current = { ...service, category: 'WORKSHOP' } as Record<string, unknown>;
  let update: Record<string, unknown> | undefined;
  stubApi((url, init) => {
    if (url === '/api/v1/service-types' && init.method === 'GET') return json({ items: [current] });
    if (url === '/api/v1/service-types/service-1' && init.method === 'PATCH') {
      update = JSON.parse(init.body as string);
      current = {
        ...current,
        ...update,
        bookingPolicy: { overrides: update!.bookingPolicy, effective: update!.bookingPolicy },
      };
      return json(current);
    }
    return undefined;
  });
  renderWithProviders(<TrainingsPage />);
  await userEvent.click(
    within((await screen.findByText('Pole dance')).closest('tr')!).getByRole('button', {
      name: 'Edytuj',
    }),
  );
  let dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('tab', { name: 'Zapisy i odwoływanie' }));
  await userEvent.click(within(dialog).getByRole('switch', { name: /^Użyj zasad studia/ }));
  const minimum = within(dialog).getByLabelText(/Minimalna liczba uczestników/);
  await userEvent.clear(minimum);
  await userEvent.type(minimum, '4');
  await userEvent.click(within(dialog).getByRole('switch', { name: /^Odwołuj przy/ }));
  const checkHours = within(dialog).getByLabelText(/^Sprawdzenie frekwencji/);
  await userEvent.clear(checkHours);
  await userEvent.type(checkHours, '5');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(update).toMatchObject({
    bookingPolicy: {
      autoCancelEnabled: true,
      autoCancelMinParticipants: 4,
      autoCancelHoursBefore: 5,
    },
  });
  await userEvent.click(
    within((await screen.findByText('Pole dance')).closest('tr')!).getByRole('button', {
      name: 'Edytuj',
    }),
  );
  dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('tab', { name: 'Zapisy i odwoływanie' }));
  expect(within(dialog).getByRole('switch', { name: /^Użyj zasad studia/ })).not.toBeChecked();
  expect(within(dialog).getByRole('switch', { name: /^Odwołuj przy/ })).toBeChecked();
  expect(within(dialog).getByLabelText(/^Sprawdzenie frekwencji/)).toHaveValue('5');
  expect(within(dialog).getByLabelText(/Minimalna liczba uczestników/)).toHaveValue('4');
}, 10000);
