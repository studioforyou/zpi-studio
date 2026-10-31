import { useState } from 'react';
import {
  Alert,
  Button,
  Group,
  Pagination,
  SegmentedControl,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { paths } from '@/app/paths';
import { pl } from '@/shared/i18n/pl';
import {
  accountKeys,
  accountRoles,
  accountStatuses,
  formatTimestampDate,
  searchAccounts,
  type AccountRole,
  type AccountStatus,
  type AccountSummary,
} from './accountsApi';
import { RoleBadges, StatusBadge } from './accountBadges';

const texts = pl.accounts.list;

/** Every account of the studio, searchable and filtered by role and status; a row opens the account. */
export function AccountsList() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [debouncedQuery] = useDebouncedValue(query, 300);
  const [role, setRole] = useState<AccountRole | null>(null);
  const [status, setStatus] = useState<AccountStatus | null>(null);
  const [page, setPage] = useState(0);
  const search = { query: debouncedQuery, role, status, page };
  const accounts = useQuery({
    queryKey: accountKeys.list(search),
    queryFn: () => searchAccounts(search),
    placeholderData: keepPreviousData,
  });

  const items = accounts.data?.items ?? [];
  const filtered = debouncedQuery.trim() !== '' || role !== null || status !== null;

  return (
    <Stack gap="sm">
      <Title order={3}>{texts.title}</Title>
      <Group align="flex-end" gap="sm">
        <TextInput
          label={texts.search}
          placeholder={texts.searchPlaceholder}
          value={query}
          onChange={(event) => {
            setQuery(event.currentTarget.value);
            setPage(0);
          }}
          w={{ base: '100%', sm: 300 }}
        />
        <Stack gap={4}>
          <Text size="sm" fw={500}>
            {texts.roleFilter}
          </Text>
          <SegmentedControl
            aria-label={texts.roleFilter}
            value={role ?? 'ALL'}
            onChange={(value) => {
              setRole(value === 'ALL' ? null : (value as AccountRole));
              setPage(0);
            }}
            data={[
              { value: 'ALL', label: texts.allRoles },
              ...accountRoles.map((value) => ({ value, label: texts.roleFilters[value] })),
            ]}
          />
        </Stack>
        <Select
          label={texts.statusFilter}
          value={status ?? 'ALL'}
          onChange={(value) => {
            setStatus(value === null || value === 'ALL' ? null : (value as AccountStatus));
            setPage(0);
          }}
          allowDeselect={false}
          data={[
            { value: 'ALL', label: texts.allStatuses },
            ...accountStatuses.map((value) => ({ value, label: pl.accounts.statuses[value] })),
          ]}
          w={{ base: '100%', sm: 240 }}
        />
      </Group>
      {accounts.isPending ? (
        <Text>{texts.loading}</Text>
      ) : accounts.isError ? (
        <Alert color="red" title={texts.loadError}>
          <Button variant="subtle" onClick={() => void accounts.refetch()}>
            {texts.retry}
          </Button>
        </Alert>
      ) : items.length === 0 ? (
        <Text c="dimmed">{filtered ? texts.noResults : texts.empty}</Text>
      ) : (
        <>
          <Table.ScrollContainer minWidth={800}>
            <Table
              striped
              highlightOnHover
              layout="fixed"
              style={{ opacity: accounts.isPlaceholderData ? 0.5 : 1, transition: 'opacity 150ms' }}
            >
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{texts.columns.name}</Table.Th>
                  <Table.Th>{texts.columns.email}</Table.Th>
                  <Table.Th w={150}>{texts.columns.phone}</Table.Th>
                  <Table.Th w={190}>{texts.columns.roles}</Table.Th>
                  <Table.Th w={190}>{texts.columns.status}</Table.Th>
                  <Table.Th w={110}>{texts.columns.createdAt}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {items.map((account) => (
                  <AccountRow
                    key={account.id}
                    account={account}
                    onOpen={() => void navigate(paths.accountDetails(account.id))}
                  />
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {texts.total(accounts.data.totalItems)}
            </Text>
            {accounts.data.totalPages > 1 && (
              <Pagination
                total={accounts.data.totalPages}
                value={page + 1}
                onChange={(value) => setPage(value - 1)}
              />
            )}
          </Group>
        </>
      )}
    </Stack>
  );
}

function AccountRow({ account, onOpen }: { account: AccountSummary; onOpen: () => void }) {
  return (
    <Table.Tr onClick={onOpen} style={{ cursor: 'pointer' }}>
      <Table.Td>
        {/* The row is clickable with a mouse; the button makes it reachable from the keyboard. */}
        <UnstyledButton
          onClick={(event) => {
            event.stopPropagation();
            onOpen();
          }}
          aria-label={texts.open(account.displayName)}
        >
          <Text fw={500} truncate="end">
            {account.displayName}
          </Text>
        </UnstyledButton>
      </Table.Td>
      <Table.Td>
        <Text size="sm" truncate="end" title={account.email}>
          {account.email}
        </Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm">{account.phone ?? '—'}</Text>
      </Table.Td>
      <Table.Td>
        <RoleBadges roles={account.roles} />
      </Table.Td>
      <Table.Td>
        <StatusBadge status={account.status} />
      </Table.Td>
      <Table.Td>
        <Text size="sm">{formatTimestampDate(account.createdAt)}</Text>
      </Table.Td>
    </Table.Tr>
  );
}
