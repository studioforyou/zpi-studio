import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { AccountAccessPanel } from '@/features/accounts/AccountAccessPanel';
import { RolesPage } from '@/features/accounts/RolesPage';
import {
  accountPermissionRows,
  groupByCategory,
  shownEffect,
} from '@/features/accounts/accessRows';
import { renderWithProviders } from './renderWithProviders';

const accountId = 'acc-1';

const permissions = [
  {
    code: 'SCHEDULE_MANAGE_ALL',
    category: 'SCHEDULING',
    description: 'Create and edit all classes',
  },
  { code: 'SCHEDULE_EDIT_OWN', category: 'SCHEDULING', description: 'Edit own classes' },
  { code: 'PASS_ADJUST', category: 'MEMBERSHIP', description: 'Adjust passes' },
  { code: 'ACCOUNT_APPROVE', category: 'IDENTITY', description: 'Approve accounts' },
  { code: 'CUSTOMER_ACCOUNT_CREATE', category: 'IDENTITY', description: 'Invite clients' },
];

const roles = [
  { code: 'CLIENT', displayName: 'Klient', permissions: [] },
  { code: 'TRAINER', displayName: 'Trener', permissions: ['SCHEDULE_EDIT_OWN'] },
  { code: 'ADMIN', displayName: 'Administrator', permissions: permissions.map((p) => p.code) },
];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

type Route = (url: string, init: RequestInit) => Response | undefined;

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

const get =
  (path: string, body: unknown): Route =>
  (url, init) =>
    url === path && init.method === 'GET' ? json(body) : undefined;

function dictionaries(): Route[] {
  return [
    get('/api/v1/roles', { items: roles }),
    get('/api/v1/permissions', { items: permissions }),
  ];
}

function account(roleCodes: string[], overrides: unknown[] = []): Route[] {
  return [
    get(`/api/v1/accounts/${accountId}/roles`, {
      items: roleCodes.map((code) => ({
        code,
        displayName: roles.find((role) => role.code === code)!.displayName,
        grantedBy: null,
        grantedAt: '2026-10-01T10:00:00Z',
      })),
    }),
    get(`/api/v1/accounts/${accountId}/permission-overrides`, { items: overrides }),
  ];
}

const calls = (fetchMock: ReturnType<typeof stubApi>, method: string) =>
  fetchMock.mock.calls
    .filter(([, init]) => init.method === method)
    .map(([url, init]) => [url, init.body ? JSON.parse(init.body as string) : undefined]);

function row(label: string) {
  return screen.getByText(label).closest('tr')!;
}

test('shows the roles and where each permission comes from', async () => {
  stubApi(...dictionaries(), ...account(['CLIENT', 'TRAINER']));
  renderWithProviders(<AccountAccessPanel accountId={accountId} />);

  expect(await screen.findByRole('switch', { name: 'Klient' })).toBeChecked();
  expect(screen.getByRole('switch', { name: 'Trener' })).toBeChecked();
  expect(screen.getByRole('switch', { name: /Administrator/ })).not.toBeChecked();

  const ownClasses = row('Edycja prowadzonych przez siebie zajęć');
  expect(within(ownClasses).getByText('Trener')).toBeInTheDocument();
  expect(within(ownClasses).getByRole('checkbox', { name: /Domyślnie/ })).toBeChecked();
  const inheritedGrant = within(ownClasses).getByRole('checkbox', { name: /Nadaj/ });
  expect(inheritedGrant).toBeChecked();
  expect(inheritedGrant).toBeDisabled();

  const allClasses = row('Tworzenie i edycja wszystkich zajęć w grafiku');
  expect(within(allClasses).getByRole('checkbox', { name: /Odbierz/ })).toBeChecked();
  expect(within(allClasses).getByRole('checkbox', { name: /Nadaj/ })).not.toBeChecked();
});

test('grants a role right away', async () => {
  const fetchMock = stubApi(...dictionaries(), ...account(['CLIENT']), (url, init) =>
    url === `/api/v1/accounts/${accountId}/roles` && init.method === 'POST'
      ? json(
          {
            code: 'TRAINER',
            displayName: 'Trener',
            grantedBy: 'admin',
            grantedAt: '2026-10-30T10:00:00Z',
          },
          201,
        )
      : undefined,
  );
  renderWithProviders(<AccountAccessPanel accountId={accountId} />);

  await userEvent.click(await screen.findByRole('switch', { name: 'Trener' }));

  await waitFor(() =>
    expect(calls(fetchMock, 'POST')).toEqual([
      [`/api/v1/accounts/${accountId}/roles`, { code: 'TRAINER' }],
    ]),
  );
});

test('explains why the last administrator keeps the role', async () => {
  stubApi(...dictionaries(), ...account(['ADMIN']), (url, init) =>
    url === `/api/v1/accounts/${accountId}/roles/ADMIN` && init.method === 'DELETE'
      ? json({ status: 422, code: 'LAST_ACTIVE_ADMIN' }, 422)
      : undefined,
  );
  renderWithProviders(<AccountAccessPanel accountId={accountId} />);

  await userEvent.click(await screen.findByRole('switch', { name: /Administrator/ }));

  expect(
    await screen.findByText(/Nie można odebrać roli Administrator ostatniemu aktywnemu/),
  ).toBeInTheDocument();
});

test('leaving the default keeps the result, then Revoke replaces it', async () => {
  const fetchMock = stubApi(...dictionaries(), ...account(['TRAINER']), (url, init) =>
    url === `/api/v1/accounts/${accountId}/permission-overrides` && init.method === 'POST'
      ? json({}, 201)
      : undefined,
  );
  renderWithProviders(<AccountAccessPanel accountId={accountId} />);
  await screen.findByRole('switch', { name: 'Trener' });

  await userEvent.click(
    within(row('Edycja prowadzonych przez siebie zajęć')).getByRole('checkbox', {
      name: /Domyślnie/,
    }),
  );

  await waitFor(() =>
    expect(calls(fetchMock, 'POST')).toEqual([
      [
        `/api/v1/accounts/${accountId}/permission-overrides`,
        { permission: 'SCHEDULE_EDIT_OWN', effect: 'GRANT' },
      ],
    ]),
  );
});

test('changing Grant to Revoke removes the old override first', async () => {
  const fetchMock = stubApi(
    ...dictionaries(),
    ...account(
      ['TRAINER'],
      [
        {
          permission: 'PASS_ADJUST',
          effect: 'GRANT',
          grantedBy: 'admin',
          grantedAt: '2026-10-01T10:00:00Z',
        },
      ],
    ),
    (url, init) =>
      url === `/api/v1/accounts/${accountId}/permission-overrides/PASS_ADJUST` &&
      init.method === 'DELETE'
        ? new Response(null, { status: 204 })
        : undefined,
    (url, init) =>
      url === `/api/v1/accounts/${accountId}/permission-overrides` && init.method === 'POST'
        ? json({}, 201)
        : undefined,
  );
  renderWithProviders(<AccountAccessPanel accountId={accountId} />);
  await screen.findByRole('switch', { name: 'Trener' });

  const passes = row('Ręczna korekta karnetów i wejść');
  expect(within(passes).getByRole('checkbox', { name: /Domyślnie/ })).not.toBeChecked();
  await userEvent.click(within(passes).getByRole('checkbox', { name: /Odbierz/ }));

  await waitFor(() =>
    expect(calls(fetchMock, 'POST')).toEqual([
      [
        `/api/v1/accounts/${accountId}/permission-overrides`,
        { permission: 'PASS_ADJUST', effect: 'REVOKE' },
      ],
    ]),
  );
  expect(calls(fetchMock, 'DELETE')).toEqual([
    [`/api/v1/accounts/${accountId}/permission-overrides/PASS_ADJUST`, undefined],
  ]);
});

test('an administrator always has every permission', async () => {
  stubApi(...dictionaries(), ...account(['ADMIN']));
  renderWithProviders(<AccountAccessPanel accountId={accountId} />);
  await screen.findByRole('switch', { name: /Administrator/ });

  const passes = row('Ręczna korekta karnetów i wejść');
  expect(within(passes).getByText('Administrator')).toBeInTheDocument();
  for (const checkbox of within(passes).getAllByRole('checkbox')) {
    expect(checkbox).toBeDisabled();
  }
});

test('the roles page shows the default permissions of each role', async () => {
  stubApi(...dictionaries());
  renderWithProviders(<RolesPage />, {
    routes: [{ path: '/accounts/roles', element: <RolesPage /> }],
    initialEntry: '/accounts/roles',
  });

  const ownClasses = (await screen.findByText('Edycja prowadzonych przez siebie zajęć')).closest(
    'tr',
  )!;
  expect(within(ownClasses).getAllByText('✓')).toHaveLength(2);
  expect(within(ownClasses).getAllByText('–')).toHaveLength(1);
});

test('permission rows follow the roles and the overrides', () => {
  const rows = accountPermissionRows(
    permissions,
    roles as never,
    ['TRAINER'],
    [{ permission: 'SCHEDULE_EDIT_OWN', effect: 'REVOKE', grantedBy: 'a', grantedAt: 'x' }],
  );

  const ownClasses = rows.find((item) => item.permission.code === 'SCHEDULE_EDIT_OWN')!;
  expect(ownClasses.inheritedFrom).toEqual(['Trener']);
  expect(shownEffect(ownClasses)).toBe('REVOKE');
  const allClasses = rows.find((item) => item.permission.code === 'SCHEDULE_MANAGE_ALL')!;
  expect(shownEffect(allClasses)).toBe('REVOKE');
  expect(groupByCategory(rows, (item) => item.permission.category).map((g) => g.category)).toEqual([
    'SCHEDULING',
    'IDENTITY',
    'MEMBERSHIP',
  ]);
});

test('saving one permission leaves the other rows usable', async () => {
  stubApi(...dictionaries(), ...account(['TRAINER']), (url, init) =>
    url === `/api/v1/accounts/${accountId}/permission-overrides` && init.method === 'POST'
      ? // Never answers, so the first change stays in progress.
        (new Promise<Response>(() => {}) as unknown as Response)
      : undefined,
  );
  renderWithProviders(<AccountAccessPanel accountId={accountId} />);
  await screen.findByRole('switch', { name: 'Trener' });

  await userEvent.click(
    within(row('Ręczna korekta karnetów i wejść')).getByRole('checkbox', { name: /Domyślnie/ }),
  );

  await waitFor(() =>
    expect(
      within(row('Ręczna korekta karnetów i wejść')).getByRole('checkbox', { name: /Domyślnie/ }),
    ).toBeDisabled(),
  );
  expect(
    within(row('Edycja prowadzonych przez siebie zajęć')).getByRole('checkbox', {
      name: /Domyślnie/,
    }),
  ).toBeEnabled();
  expect(screen.getByRole('switch', { name: 'Klient' })).toBeEnabled();
});

test('marks the permissions that nothing checks yet', async () => {
  stubApi(...dictionaries(), ...account(['TRAINER']));
  renderWithProviders(<AccountAccessPanel accountId={accountId} />);
  await screen.findByRole('switch', { name: 'Trener' });

  expect(
    within(row('Ręczna korekta karnetów i wejść')).getByText('jeszcze bez efektu'),
  ).toBeInTheDocument();
  expect(
    within(row('Zapraszanie klientów (zakładanie kont w ich imieniu)')).queryByText(
      'jeszcze bez efektu',
    ),
  ).not.toBeInTheDocument();
});
