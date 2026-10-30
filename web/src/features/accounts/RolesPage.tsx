import { Alert, Anchor, Breadcrumbs, Card, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { paths } from '@/app/paths';
import { pl } from '@/shared/i18n/pl';
import { accessKeys, listPermissions, listRoles } from './accessApi';
import { groupByCategory } from './accessRows';

/** Read-only table of the permissions each role gives by default. */
export function RolesPage() {
  const texts = pl.accounts.access;
  const roles = useQuery({ queryKey: accessKeys.roles(), queryFn: listRoles });
  const permissions = useQuery({ queryKey: accessKeys.permissions(), queryFn: listPermissions });

  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to={paths.accounts}>
          {pl.accounts.title}
        </Anchor>
        <Text>{texts.matrix.breadcrumb}</Text>
      </Breadcrumbs>
      <Stack gap="xs">
        <Title order={2}>{texts.matrix.title}</Title>
        <Text size="sm" c="dimmed">
          {texts.matrix.intro}
        </Text>
      </Stack>
      {roles.isPending || permissions.isPending ? (
        <Loader />
      ) : roles.isError || permissions.isError ? (
        <Alert color="red">{texts.loadError}</Alert>
      ) : (
        <Card withBorder radius="md" padding="lg">
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{texts.permissions.columns.permission}</Table.Th>
                {roles.data.map((role) => (
                  <Table.Th key={role.code} ta="center">
                    {role.displayName}
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {groupByCategory(permissions.data, (permission) => permission.category).map(
                (group) => [
                  <Table.Tr key={group.category} bg="gray.0">
                    <Table.Td colSpan={roles.data.length + 1}>
                      <Text size="xs" fw={700} c="dimmed" tt="uppercase">
                        {texts.categories[group.category as keyof typeof texts.categories] ??
                          group.category}
                      </Text>
                    </Table.Td>
                  </Table.Tr>,
                  ...group.rows.map((permission) => (
                    <Table.Tr key={permission.code}>
                      <Table.Td>
                        {texts.names[permission.code as keyof typeof texts.names] ??
                          permission.description}
                      </Table.Td>
                      {roles.data.map((role) =>
                        role.permissions.includes(permission.code) ? (
                          <Table.Td key={role.code} ta="center" c="green.8" fw={600}>
                            ✓
                          </Table.Td>
                        ) : (
                          <Table.Td key={role.code} ta="center" c="gray.5">
                            –
                          </Table.Td>
                        ),
                      )}
                    </Table.Tr>
                  )),
                ],
              )}
            </Table.Tbody>
          </Table>
          <Text size="sm" c="dimmed" mt="md">
            {texts.matrix.adminNote}
          </Text>
        </Card>
      )}
    </Stack>
  );
}
