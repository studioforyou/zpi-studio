import { useMemo, useState } from 'react';
import { Alert, Badge, Button, Group, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { accountKeys, searchAccounts } from '@/features/accounts/accountsApi';
import { pl } from '@/shared/i18n/pl';
import { formatSentAt } from '../announcements/announcementFormat';
import {
  HISTORY_PAGE_SIZE,
  historyKeys,
  listHistory,
  sendStatuses,
  type HistoryItem,
  type SendStatus,
} from './historyApi';
import { recipientNote, statusColor, typeLabel, typeOptions } from './historyFormat';
import { periodRange, periods, type CustomRange, type Period } from './historyPeriod';
import { SendHistoryDrawer } from './SendHistoryDrawer';

type Person = { value: string; label: string };

/** Every notification sent, with filters by type, person, period and status (#161). */
export function SendHistoryPanel() {
  const texts = pl.communication.history;
  const [type, setType] = useState<string | null>(null);
  const [person, setPerson] = useState<Person | null>(null);
  const [period, setPeriod] = useState<Period>('WEEK');
  const [custom, setCustom] = useState<CustomRange>({ from: '', to: '' });
  const [status, setStatus] = useState<SendStatus | null>(null);
  const [size, setSize] = useState(HISTORY_PAGE_SIZE);
  const [opened, setOpened] = useState<HistoryItem | null>(null);

  const range = useMemo(() => periodRange(period, custom), [period, custom]);
  const filter = { type, accountId: person?.value ?? null, ...range, status };
  const history = useQuery({
    queryKey: historyKeys.list(filter, size),
    queryFn: () => listHistory(filter, size),
    placeholderData: keepPreviousData,
  });

  const filtered = type !== null || person !== null || period !== 'WEEK' || status !== null;
  const reset = () => {
    setType(null);
    setPerson(null);
    setPeriod('WEEK');
    setCustom({ from: '', to: '' });
    setStatus(null);
    setSize(HISTORY_PAGE_SIZE);
  };
  const clearButton = (
    <Button variant="subtle" onClick={reset} disabled={!filtered}>
      {texts.filters.clear}
    </Button>
  );

  return (
    <Stack>
      <Text size="sm" c="dimmed">
        {texts.intro}
      </Text>
      <Group align="flex-end" gap="sm">
        <Select
          label={texts.filters.type}
          placeholder={texts.filters.allTypes}
          data={typeOptions}
          value={type}
          onChange={(next) => {
            setType(next);
            setSize(HISTORY_PAGE_SIZE);
          }}
          clearable
          searchable
          w={230}
        />
        <PersonFilter
          value={person}
          onChange={(next) => {
            setPerson(next);
            setSize(HISTORY_PAGE_SIZE);
          }}
        />
        <Select
          label={texts.filters.period}
          data={periods.map((value) => ({ value, label: texts.periods[value] }))}
          value={period}
          onChange={(next) => {
            setPeriod((next as Period | null) ?? 'WEEK');
            setSize(HISTORY_PAGE_SIZE);
          }}
          allowDeselect={false}
          w={190}
        />
        {period === 'CUSTOM' && (
          <>
            <TextInput
              type="date"
              label={texts.filters.from}
              value={custom.from}
              onChange={(event) => setCustom({ ...custom, from: event.currentTarget.value })}
              w={160}
            />
            <TextInput
              type="date"
              label={texts.filters.to}
              value={custom.to}
              onChange={(event) => setCustom({ ...custom, to: event.currentTarget.value })}
              w={160}
            />
          </>
        )}
        <Select
          label={texts.filters.status}
          placeholder={texts.filters.anyStatus}
          data={sendStatuses.map((value) => ({ value, label: texts.status[value] }))}
          value={status}
          onChange={(next) => {
            setStatus(next as SendStatus | null);
            setSize(HISTORY_PAGE_SIZE);
          }}
          clearable
          w={180}
        />
        <div style={{ marginLeft: 'auto' }}>{clearButton}</div>
      </Group>

      {history.isPending ? (
        <Text>{texts.loading}</Text>
      ) : history.isError ? (
        <Alert color="red" title={texts.loadError}>
          <Button variant="subtle" onClick={() => void history.refetch()}>
            {texts.retry}
          </Button>
        </Alert>
      ) : history.data.items.length === 0 ? (
        <Stack
          align="center"
          gap="xs"
          p="xl"
          style={{
            border: '1px dashed var(--mantine-color-gray-4)',
            borderRadius: 'var(--mantine-radius-md)',
          }}
        >
          <Text fw={600} size="lg">
            {texts.emptyTitle}
          </Text>
          <Text c="dimmed" ta="center">
            {texts.emptyText}
          </Text>
          {filtered && clearButton}
        </Stack>
      ) : (
        <>
          <Table highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{texts.columns.sentAt}</Table.Th>
                <Table.Th>{texts.columns.type}</Table.Th>
                <Table.Th>{texts.columns.recipient}</Table.Th>
                <Table.Th>{texts.columns.notification}</Table.Th>
                <Table.Th>{texts.columns.status}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {history.data.items.map((item) => {
                const note = recipientNote(item);
                return (
                  <Table.Tr
                    key={item.id}
                    onClick={() => setOpened(item)}
                    style={{ cursor: 'pointer' }}
                  >
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>
                      {formatSentAt(item.sentAt)}
                    </Table.Td>
                    <Table.Td>
                      <Badge
                        variant="light"
                        color={item.type === 'ANNOUNCEMENT' ? 'orange' : 'gray'}
                        radius="sm"
                        style={{ textTransform: 'none' }}
                      >
                        {typeLabel(item.type)}
                      </Badge>
                    </Table.Td>
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>
                      <Text size="sm">{item.recipientName}</Text>
                      {note && (
                        <Text size="xs" c="dimmed">
                          {note}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td style={{ maxWidth: 460 }}>
                      <Text size="sm" fw={600}>
                        {item.title}
                      </Text>
                      {item.bodyHidden ? (
                        <Text size="xs" c="dimmed" fs="italic">
                          {texts.hiddenBody}
                        </Text>
                      ) : (
                        item.body && (
                          <Text size="xs" c="dimmed" truncate="end">
                            {item.body}
                          </Text>
                        )
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Badge
                        variant="light"
                        color={statusColor[item.status]}
                        style={{ textTransform: 'none' }}
                      >
                        {texts.status[item.status]}
                      </Badge>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {texts.shown(history.data.items.length, history.data.totalItems)}
            </Text>
            {history.data.totalItems > history.data.items.length && size < 100 && (
              <Button
                variant="default"
                loading={history.isFetching}
                onClick={() => setSize((current) => Math.min(current + HISTORY_PAGE_SIZE, 100))}
              >
                {texts.more}
              </Button>
            )}
          </Group>
        </>
      )}
      <SendHistoryDrawer
        item={opened}
        onClose={() => setOpened(null)}
        onShowPerson={(item) => {
          setPerson({ value: item.recipientId, label: item.recipientName });
          setType(null);
          setStatus(null);
          setSize(HISTORY_PAGE_SIZE);
          setOpened(null);
        }}
      />
    </Stack>
  );
}

function PersonFilter({
  value,
  onChange,
}: {
  value: Person | null;
  onChange: (next: Person | null) => void;
}) {
  const texts = pl.communication.history.filters;
  const [search, setSearch] = useState('');
  const [debounced] = useDebouncedValue(search, 300);
  const people = useQuery({
    queryKey: accountKeys.list({ query: debounced, role: null, status: null, page: 0 }),
    queryFn: () => searchAccounts({ query: debounced, role: null, status: null, page: 0 }),
    placeholderData: keepPreviousData,
  });
  const options = useMemo(() => {
    const found = (people.data?.items ?? []).map((person) => ({
      value: person.id,
      label: person.displayName,
    }));
    // The chosen person stays an option even when the current search no longer matches them.
    return value && !found.some((option) => option.value === value.value)
      ? [value, ...found]
      : found;
  }, [people.data, value]);

  return (
    <Select
      label={texts.person}
      placeholder={texts.personPlaceholder}
      searchable
      clearable
      searchValue={search}
      onSearchChange={setSearch}
      filter={({ options: all }) => all}
      data={options}
      value={value?.value ?? null}
      nothingFoundMessage={texts.nothingFound}
      onChange={(_, option) =>
        onChange(option ? { value: option.value, label: option.label } : null)
      }
      w={240}
    />
  );
}
