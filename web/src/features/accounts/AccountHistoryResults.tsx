import { Alert, Anchor, Button, Group, Pagination, Stack, Table, Text } from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { paths } from '@/app/paths';
import { getStudio, scheduleKeys } from '@/features/schedule/scheduleApi';
import { formatDateTime } from '@/features/schedule/scheduleFormat';
import { DEFAULT_TIME_ZONE } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import {
  accountHistoryKeys,
  searchAccountHistory,
  type AccountHistoryEntry,
  type AccountHistorySearch,
} from './accountHistoryApi';
import { describeEntry } from './accountHistoryText';

const texts = pl.accounts.history;

type Props = {
  search: AccountHistorySearch;
  onPageChange: (page: number) => void;
  /** Whether to show which account changed; not needed on the account's own page. */
  showAccount: boolean;
  emptyText: string;
};

/** One page of the account history, newest first, with the studio's times. */
export function AccountHistoryResults({ search, onPageChange, showAccount, emptyText }: Props) {
  const history = useQuery({
    queryKey: accountHistoryKeys.search(search),
    queryFn: () => searchAccountHistory(search),
    placeholderData: keepPreviousData,
  });
  const studio = useQuery({
    queryKey: scheduleKeys.studio,
    queryFn: getStudio,
    staleTime: Infinity,
  });
  const timeZone = studio.data?.timeZone ?? DEFAULT_TIME_ZONE;

  if (history.isPending) {
    return <Text>{texts.loading}</Text>;
  }
  if (history.isError) {
    return (
      <Alert color="red" title={texts.loadError}>
        <Button variant="subtle" onClick={() => void history.refetch()}>
          {texts.retry}
        </Button>
      </Alert>
    );
  }
  if (history.data.items.length === 0) {
    return <Text c="dimmed">{emptyText}</Text>;
  }

  return (
    <Stack gap="sm">
      <Table.ScrollContainer minWidth={showAccount ? 860 : 640}>
        <Table
          striped
          layout="fixed"
          verticalSpacing="xs"
          style={{ opacity: history.isPlaceholderData ? 0.5 : 1, transition: 'opacity 150ms' }}
        >
          <Table.Thead>
            <Table.Tr>
              <Table.Th w={140}>{texts.columns.occurredAt}</Table.Th>
              {showAccount && <Table.Th w={190}>{texts.columns.account}</Table.Th>}
              <Table.Th>{texts.columns.change}</Table.Th>
              <Table.Th w={180}>{texts.columns.actor}</Table.Th>
              <Table.Th>{texts.columns.reason}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {history.data.items.map((entry) => (
              <HistoryRow
                key={entry.id}
                entry={entry}
                showAccount={showAccount}
                timeZone={timeZone}
              />
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {texts.total(history.data.totalItems)}
        </Text>
        {history.data.totalPages > 1 && (
          <Pagination
            total={history.data.totalPages}
            value={search.page + 1}
            onChange={(value) => onPageChange(value - 1)}
          />
        )}
      </Group>
    </Stack>
  );
}

function HistoryRow({
  entry,
  showAccount,
  timeZone,
}: {
  entry: AccountHistoryEntry;
  showAccount: boolean;
  timeZone: string;
}) {
  const { title, detail } = describeEntry(entry);
  const self = entry.actor?.accountId === entry.account.accountId;
  return (
    <Table.Tr>
      <Table.Td>
        <Text size="sm">{formatDateTime(entry.occurredAt, timeZone)}</Text>
      </Table.Td>
      {showAccount && (
        <Table.Td>
          <Anchor
            component={Link}
            to={paths.accountDetails(entry.account.accountId)}
            size="sm"
            display="block"
            truncate="end"
          >
            {entry.account.displayName}
          </Anchor>
        </Table.Td>
      )}
      <Table.Td>
        <Text size="sm" fw={500}>
          {title}
        </Text>
        {detail && (
          <Text size="xs" c="dimmed">
            {detail}
          </Text>
        )}
      </Table.Td>
      <Table.Td>
        {entry.actor ? (
          <>
            <Text size="sm" truncate="end" title={entry.actor.displayName}>
              {entry.actor.displayName}
            </Text>
            {self && (
              <Text size="xs" c="dimmed">
                {texts.self}
              </Text>
            )}
          </>
        ) : (
          <Text size="sm" c="dimmed">
            {texts.system}
          </Text>
        )}
      </Table.Td>
      <Table.Td>
        <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
          {entry.reason ?? '—'}
        </Text>
      </Table.Td>
    </Table.Tr>
  );
}
