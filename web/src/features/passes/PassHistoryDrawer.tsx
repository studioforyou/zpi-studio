import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Chip,
  Drawer,
  Group,
  Loader,
  Pagination,
  Paper,
  Stack,
  Text,
} from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { getStudio, scheduleKeys } from '@/features/schedule/scheduleApi';
import { formatDateTime } from '@/features/schedule/scheduleFormat';
import { DEFAULT_TIME_ZONE } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import type { HeldPass } from './customerPassesApi';
import { formatDate } from './passFormat';
import {
  getPassHistory,
  passHistoryCategories,
  passHistoryKeys,
  type PassHistoryCategory,
  type PassHistoryEntry,
} from './passHistoryApi';
import {
  actorText,
  changeColor,
  changeDetails,
  sessionFacts,
  sessionHeading,
} from './passHistoryFormat';

const PAGE_SIZE = 20;
const ALL = 'ALL';
const texts = pl.passHistory;

type Props = {
  /** The pass as listed; the drawer loads its history. */
  held: HeldPass;
  onClose: () => void;
};

/**
 * Everything that changed a participant's pass, newest first, so the administrator can explain how
 * its entries were used: bookings and their classes, corrections, validity changes, make-up credits.
 */
export function PassHistoryDrawer({ held, onClose }: Props) {
  const { holder, pass } = held;
  const [category, setCategory] = useState<PassHistoryCategory | null>(null);
  const [page, setPage] = useState(0);
  const search = { customerId: holder.id, passId: pass.id, category, page, size: PAGE_SIZE };
  const history = useQuery({
    queryKey: passHistoryKeys.search(search),
    queryFn: () => getPassHistory(search),
    placeholderData: keepPreviousData,
  });
  const studio = useQuery({
    queryKey: scheduleKeys.studio,
    queryFn: getStudio,
    staleTime: Infinity,
  });
  const timeZone = studio.data?.timeZone ?? DEFAULT_TIME_ZONE;
  const summary = history.data?.pass;
  const entriesTotal = summary?.entriesTotal ?? pass.entriesTotal;
  const entriesRemaining = summary?.entriesRemaining ?? pass.entriesRemaining;

  return (
    <Drawer opened onClose={onClose} position="right" size="lg" title={texts.title}>
      <Stack gap="md">
        <Stack gap={2}>
          <Text fw={600}>{holder.displayName}</Text>
          <Text size="sm">{pass.productName}</Text>
          <Text size="sm" c="dimmed">
            {[
              entriesTotal === null || entriesRemaining === null
                ? texts.unlimited
                : texts.balance(entriesRemaining, entriesTotal),
              pass.validFrom && pass.validUntil
                ? texts.validity(formatDate(pass.validFrom), formatDate(pass.validUntil))
                : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </Text>
        </Stack>
        {summary &&
          summary.ledgerBalance !== null &&
          summary.entriesRemaining !== null &&
          summary.ledgerBalance !== summary.entriesRemaining && (
            <Alert color="yellow">
              {texts.mismatch(summary.ledgerBalance, summary.entriesRemaining)}
            </Alert>
          )}
        <Chip.Group
          multiple={false}
          value={category ?? ALL}
          onChange={(value) => {
            setCategory(value === ALL ? null : (value as PassHistoryCategory));
            setPage(0);
          }}
        >
          <Group gap="xs" role="group" aria-label={texts.filter}>
            <Chip value={ALL} size="sm">
              {texts.all}
            </Chip>
            {passHistoryCategories.map((option) => (
              <Chip key={option} value={option} size="sm">
                {history.data
                  ? `${texts.categories[option]} (${history.data.counts[option]})`
                  : texts.categories[option]}
              </Chip>
            ))}
          </Group>
        </Chip.Group>
        {history.isPending ? (
          <Group gap="xs">
            <Loader size="sm" />
            <Text size="sm">{texts.loading}</Text>
          </Group>
        ) : history.isError ? (
          <Alert color="red" title={texts.loadError}>
            {history.error instanceof ApiError && history.error.code === 'NOT_FOUND' ? (
              texts.notFound
            ) : (
              <Button variant="subtle" onClick={() => void history.refetch()}>
                {texts.retry}
              </Button>
            )}
          </Alert>
        ) : history.data.entries.items.length === 0 ? (
          <Text c="dimmed">{category ? texts.emptyCategory : texts.empty}</Text>
        ) : (
          <Stack
            gap="sm"
            style={{ opacity: history.isPlaceholderData ? 0.5 : 1, transition: 'opacity 150ms' }}
          >
            {history.data.entries.items.map((entry) => (
              <HistoryEntry
                key={`${entry.kind}:${entry.id}`}
                entry={entry}
                purchased={history.data.pass.purchased}
                timeZone={timeZone}
              />
            ))}
            <Group justify="space-between">
              <Text size="sm" c="dimmed">
                {texts.total(history.data.entries.totalItems)}
              </Text>
              {history.data.entries.totalPages > 1 && (
                <Pagination
                  size="sm"
                  total={history.data.entries.totalPages}
                  value={page + 1}
                  onChange={(value) => setPage(value - 1)}
                />
              )}
            </Group>
          </Stack>
        )}
      </Stack>
    </Drawer>
  );
}

function HistoryEntry({
  entry,
  purchased,
  timeZone,
}: {
  entry: PassHistoryEntry;
  purchased: boolean;
  timeZone: string;
}) {
  const details = changeDetails(entry, purchased, timeZone);
  const usedFor = entry.makeupCredit?.usedFor ?? null;
  return (
    <Paper withBorder radius="md" p="sm" role="article" aria-label={texts.kinds[entry.kind]}>
      <Stack gap={4}>
        <Group justify="space-between" align="flex-start" wrap="nowrap" gap="sm">
          <Stack gap={0} style={{ minWidth: 0 }}>
            <Text fw={600} size="sm">
              {texts.kinds[entry.kind]}
            </Text>
            <Text size="xs" c="dimmed">
              {formatDateTime(entry.occurredAt, timeZone)} · {actorText(entry)}
            </Text>
          </Stack>
          {entry.entriesChange !== null && (
            <Stack gap={0} align="flex-end" style={{ flexShrink: 0 }}>
              <Badge
                variant="light"
                color={changeColor(entry.entriesChange)}
                aria-label={texts.changeLabel(entry.entriesChange)}
              >
                {texts.change(entry.entriesChange)}
              </Badge>
              {entry.balanceAfter !== null && (
                <Text size="xs" c="dimmed">
                  {texts.balanceAfter(entry.balanceAfter)}
                </Text>
              )}
            </Stack>
          )}
        </Group>
        {entry.session && (
          <SessionLines
            label={entry.kind === 'MAKEUP_GRANTED' ? texts.details.makeupFor : null}
            session={entry.session}
            timeZone={timeZone}
          />
        )}
        {details.map((line) => (
          <Text key={line} size="sm">
            {line}
          </Text>
        ))}
        {usedFor && (
          <SessionLines label={texts.details.makeupUsedFor} session={usedFor} timeZone={timeZone} />
        )}
        {entry.reason && (
          <Text size="sm" fs="italic" style={{ overflowWrap: 'anywhere' }}>
            „{entry.reason}”
          </Text>
        )}
      </Stack>
    </Paper>
  );
}

function SessionLines({
  label,
  session,
  timeZone,
}: {
  label: string | null;
  session: NonNullable<PassHistoryEntry['session']>;
  timeZone: string;
}) {
  const heading = sessionHeading(session, timeZone);
  return (
    <Stack gap={0}>
      <Text size="sm" fw={500}>
        {label ? `${label}: ${heading}` : heading}
      </Text>
      {sessionFacts(session, timeZone).map((fact) => (
        <Text key={fact} size="xs" c="dimmed">
          {fact}
        </Text>
      ))}
    </Stack>
  );
}
