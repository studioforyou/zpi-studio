import { useState } from 'react';
import {
  Anchor,
  Breadcrumbs,
  Group,
  SegmentedControl,
  Select,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { paths } from '@/app/paths';
import { pl } from '@/shared/i18n/pl';
import {
  accountHistoryKeys,
  historyCategories,
  listHistoryActors,
  type HistoryCategory,
} from './accountHistoryApi';
import { AccountHistoryResults } from './AccountHistoryResults';

const texts = pl.accounts.history;

const categories = Object.keys(historyCategories) as HistoryCategory[];

/**
 * Every change of account statuses, roles and permissions in the studio, newest first, filtered by
 * the changed account, who made the change, its kind and the studio dates.
 */
export function AccountHistoryPage() {
  const [query, setQuery] = useState('');
  const [debouncedQuery] = useDebouncedValue(query, 300);
  const [actorId, setActorId] = useState<string | null>(null);
  const [category, setCategory] = useState<HistoryCategory | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const actors = useQuery({ queryKey: accountHistoryKeys.actors(), queryFn: listHistoryActors });

  const reversed = from !== '' && to !== '' && to < from;
  const filtered =
    debouncedQuery.trim() !== '' ||
    actorId !== null ||
    category !== null ||
    from !== '' ||
    to !== '';

  /** Every filter change starts again from the first page. */
  function change<T>(set: (value: T) => void) {
    return (value: T) => {
      set(value);
      setPage(0);
    };
  }

  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to={paths.accounts}>
          {pl.accounts.title}
        </Anchor>
        <Text>{texts.breadcrumb}</Text>
      </Breadcrumbs>
      <Stack gap="xs">
        <Title order={2}>{texts.title}</Title>
        <Text size="sm" c="dimmed">
          {texts.intro}
        </Text>
      </Stack>
      <Group align="flex-start" gap="sm">
        <TextInput
          label={texts.filters.query}
          placeholder={texts.filters.queryPlaceholder}
          value={query}
          onChange={(event) => change(setQuery)(event.currentTarget.value)}
          w={{ base: '100%', sm: 260 }}
        />
        <Select
          label={texts.filters.actor}
          placeholder={texts.filters.allActors}
          value={actorId}
          onChange={change(setActorId)}
          data={(actors.data ?? []).map((actor) => ({
            value: actor.accountId,
            label: actor.displayName,
          }))}
          searchable
          clearable
          nothingFoundMessage={texts.filters.noActors}
          w={{ base: '100%', sm: 240 }}
        />
        <Stack gap={4}>
          <Text size="sm" fw={500}>
            {texts.filters.category}
          </Text>
          <SegmentedControl
            aria-label={texts.filters.category}
            value={category ?? 'ALL'}
            onChange={(value) =>
              change(setCategory)(value === 'ALL' ? null : (value as HistoryCategory))
            }
            data={[
              { value: 'ALL', label: texts.filters.categories.ALL },
              ...categories.map((value) => ({ value, label: texts.filters.categories[value] })),
            ]}
          />
        </Stack>
        <TextInput
          type="date"
          label={texts.filters.from}
          value={from}
          onChange={(event) => change(setFrom)(event.currentTarget.value)}
        />
        <TextInput
          type="date"
          label={texts.filters.to}
          value={to}
          onChange={(event) => change(setTo)(event.currentTarget.value)}
          error={reversed ? texts.filters.reversedPeriod : undefined}
        />
      </Group>
      {!reversed && (
        <AccountHistoryResults
          search={{ query: debouncedQuery, actorId, category, from, to, page }}
          onPageChange={setPage}
          showAccount
          emptyText={filtered ? texts.noResults : texts.empty}
        />
      )}
    </Stack>
  );
}
