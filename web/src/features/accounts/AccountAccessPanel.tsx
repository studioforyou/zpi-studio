import {
  Alert,
  Card,
  Checkbox,
  Group,
  Loader,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import {
  accessKeys,
  addPermissionOverride,
  grantRole,
  listAccountRoles,
  listPermissionOverrides,
  listPermissions,
  listRoles,
  removePermissionOverride,
  revokeRole,
  type OverrideEffect,
  type RoleCode,
} from './accessApi';
import { accountKeys } from './accountsApi';
import {
  accountPermissionRows,
  groupByCategory,
  shownEffect,
  type AccountPermissionRow,
} from './accessRows';

const ADMIN: RoleCode = 'ADMIN';

/** A cross in place of the checkmark, as in the approved mockup. */
function CrossIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 10 10" fill="none" aria-hidden>
      <path d="M2 2l6 6M8 2L2 8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

type PermissionChange = {
  permission: string;
  from: OverrideEffect | null;
  to: OverrideEffect | null;
};

/** Roles and permissions of one account; every change is saved right away. */
export function AccountAccessPanel({ accountId }: { accountId: string }) {
  const texts = pl.accounts.access;
  const queryClient = useQueryClient();
  const roles = useQuery({ queryKey: accessKeys.roles(), queryFn: listRoles });
  const permissions = useQuery({ queryKey: accessKeys.permissions(), queryFn: listPermissions });
  const accountRoles = useQuery({
    queryKey: accessKeys.accountRoles(accountId),
    queryFn: () => listAccountRoles(accountId),
  });
  const overrides = useQuery({
    queryKey: accessKeys.accountOverrides(accountId),
    queryFn: () => listPermissionOverrides(accountId),
  });

  // The account's details (with its role badges) and its roles and overrides share this prefix.
  const refresh = () => queryClient.invalidateQueries({ queryKey: accountKeys.details(accountId) });

  const toggleRole = useMutation({
    mutationFn: ({ code, held }: { code: RoleCode; held: boolean }) =>
      held ? revokeRole(accountId, code) : grantRole(accountId, code).then(() => undefined),
    onSettled: refresh,
  });

  const changePermission = useMutation({
    // The API keeps one override per permission, so changing its effect removes the old one first.
    mutationFn: async ({ permission, from, to }: PermissionChange) => {
      if (from !== null) {
        await removePermissionOverride(accountId, permission);
      }
      if (to !== null) {
        await addPermissionOverride(accountId, permission, to);
      }
    },
    onSettled: refresh,
  });

  if (roles.isPending || permissions.isPending || accountRoles.isPending || overrides.isPending) {
    return <Loader />;
  }
  if (roles.isError || permissions.isError || accountRoles.isError || overrides.isError) {
    return <Alert color="red">{texts.loadError}</Alert>;
  }

  const heldRoles = accountRoles.data.map((role) => role.code);
  const isAdmin = heldRoles.includes(ADMIN);
  const rows = accountPermissionRows(permissions.data, roles.data, heldRoles, overrides.data);
  const busy = toggleRole.isPending || changePermission.isPending;
  const error = toggleRole.error ?? changePermission.error;

  function setDefault(row: AccountPermissionRow, useDefault: boolean) {
    changePermission.mutate(
      useDefault
        ? { permission: row.permission.code, from: row.override, to: null }
        : // Leaving the default keeps the current result until Grant or Revoke is changed.
          { permission: row.permission.code, from: null, to: shownEffect(row) },
    );
  }

  function setEffect(row: AccountPermissionRow, effect: OverrideEffect) {
    if (row.override !== effect) {
      changePermission.mutate({ permission: row.permission.code, from: row.override, to: effect });
    }
  }

  return (
    <Stack>
      {error && (
        <Alert
          color="red"
          withCloseButton
          onClose={() => (toggleRole.reset(), changePermission.reset())}
        >
          {error instanceof ApiError && error.code === 'LAST_ACTIVE_ADMIN'
            ? texts.lastAdmin
            : texts.saveError}
        </Alert>
      )}

      <Card withBorder radius="md" padding="lg">
        <Title order={4}>{texts.roles.title}</Title>
        <Text size="sm" c="dimmed" mb="md">
          {texts.roles.hint}
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          {roles.data.map((role) => {
            const held = heldRoles.includes(role.code);
            return (
              <Card
                key={role.code}
                withBorder
                radius="md"
                padding="md"
                bg={held ? 'blue.0' : undefined}
              >
                <Switch
                  checked={held}
                  disabled={busy}
                  onChange={() => toggleRole.mutate({ code: role.code, held })}
                  label={<Text fw={600}>{role.displayName}</Text>}
                  description={role.code === ADMIN ? texts.roles.adminDescription : undefined}
                />
              </Card>
            );
          })}
        </SimpleGrid>
      </Card>

      <Card withBorder radius="md" padding="lg">
        <Title order={4}>{texts.permissions.title}</Title>
        <Text size="sm" c="dimmed" mb="md">
          {isAdmin ? texts.permissions.adminHint : texts.permissions.hint}
        </Text>
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{texts.permissions.columns.permission}</Table.Th>
              <Table.Th>{texts.permissions.columns.inheritedFrom}</Table.Th>
              <Table.Th ta="center">{texts.permissions.columns.default}</Table.Th>
              <Table.Th w={32} />
              <Table.Th ta="center" style={{ borderLeft: '1px solid var(--mantine-color-gray-3)' }}>
                {texts.permissions.columns.grant}
              </Table.Th>
              <Table.Th ta="center">{texts.permissions.columns.revoke}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {groupByCategory(rows, (row) => row.permission.category).map((group) => [
              <Table.Tr key={group.category} bg="gray.0">
                <Table.Td colSpan={6}>
                  <Text size="xs" fw={700} c="dimmed" tt="uppercase">
                    {texts.categories[group.category as keyof typeof texts.categories] ??
                      group.category}
                  </Text>
                </Table.Td>
              </Table.Tr>,
              ...group.rows.map((row) => {
                const code = row.permission.code;
                const label =
                  texts.names[code as keyof typeof texts.names] ?? row.permission.description;
                const isDefault = row.override === null;
                const effect = shownEffect(row);
                const locked = isAdmin || busy;
                return (
                  <Table.Tr key={code}>
                    <Table.Td>
                      <Text size="sm">{label}</Text>
                      <Text size="xs" c="dimmed" ff="monospace">
                        {code}
                      </Text>
                    </Table.Td>
                    <Table.Td>{row.inheritedFrom.join(', ')}</Table.Td>
                    <Table.Td>
                      <Group justify="center">
                        <Checkbox
                          aria-label={`${texts.permissions.columns.default}: ${label}`}
                          icon={CrossIcon}
                          checked={isDefault}
                          disabled={locked}
                          onChange={(event) => setDefault(row, event.currentTarget.checked)}
                        />
                      </Group>
                    </Table.Td>
                    <Table.Td />
                    <Table.Td style={{ borderLeft: '1px solid var(--mantine-color-gray-3)' }}>
                      <Group justify="center">
                        <Checkbox
                          aria-label={`${texts.permissions.columns.grant}: ${label}`}
                          icon={CrossIcon}
                          checked={effect === 'GRANT'}
                          disabled={locked || isDefault}
                          onChange={() => setEffect(row, 'GRANT')}
                        />
                      </Group>
                    </Table.Td>
                    <Table.Td>
                      <Group justify="center">
                        <Checkbox
                          aria-label={`${texts.permissions.columns.revoke}: ${label}`}
                          icon={CrossIcon}
                          checked={effect === 'REVOKE'}
                          disabled={locked || isDefault}
                          onChange={() => setEffect(row, 'REVOKE')}
                        />
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                );
              }),
            ])}
          </Table.Tbody>
        </Table>
      </Card>
    </Stack>
  );
}
