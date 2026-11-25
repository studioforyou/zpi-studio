import { useState } from 'react';
import { Alert, Badge, Button, Group, Progress, Stack, Table, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { AnnouncementDetailsDrawer } from './AnnouncementDetailsDrawer';
import { audienceLabel, formatSentAt } from './announcementFormat';
import {
  HISTORY_PAGE_SIZE,
  announcementKeys,
  listAnnouncements,
  type AnnouncementSummary,
} from './announcementsApi';
import { NewAnnouncementModal } from './NewAnnouncementModal';

/** Sent announcements, newest first, and the form for a new one. */
export function AnnouncementsPanel() {
  const texts = pl.communication.announcements;
  const [size, setSize] = useState(HISTORY_PAGE_SIZE);
  const [composing, setComposing] = useState(false);
  const [opened, setOpened] = useState<AnnouncementSummary | null>(null);
  const history = useQuery({
    queryKey: announcementKeys.list(size),
    queryFn: () => listAnnouncements(size),
    placeholderData: (previous) => previous,
  });

  const newButton = <Button onClick={() => setComposing(true)}>{texts.newAnnouncement}</Button>;

  return (
    <Stack>
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
          {newButton}
        </Stack>
      ) : (
        <>
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {texts.intro}
            </Text>
            {newButton}
          </Group>
          <Table highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{texts.columns.sentAt}</Table.Th>
                <Table.Th>{texts.columns.title}</Table.Th>
                <Table.Th>{texts.columns.audience}</Table.Th>
                <Table.Th>{texts.columns.recipients}</Table.Th>
                <Table.Th>{texts.columns.read}</Table.Th>
                <Table.Th>{texts.columns.author}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {history.data.items.map((announcement) => (
                <Table.Tr
                  key={announcement.id}
                  onClick={() => setOpened(announcement)}
                  style={{ cursor: 'pointer' }}
                >
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>
                    {formatSentAt(announcement.sentAt)}
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      <Text fw={600} size="sm">
                        {announcement.title}
                      </Text>
                      {announcement.marketing && (
                        <Badge color="orange" variant="light">
                          {texts.marketingBadge}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td>{audienceLabel(announcement)}</Table.Td>
                  <Table.Td>{announcement.recipients}</Table.Td>
                  <Table.Td style={{ whiteSpace: 'nowrap' }}>
                    <Group gap="xs" wrap="nowrap">
                      <Progress
                        w={110}
                        value={
                          announcement.recipients === 0
                            ? 0
                            : (100 * announcement.read) / announcement.recipients
                        }
                        aria-hidden
                      />
                      <Text size="sm">
                        {texts.readOf(announcement.read, announcement.recipients)}
                      </Text>
                    </Group>
                  </Table.Td>
                  <Table.Td>{announcement.authorName ?? '—'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {history.data.totalItems > history.data.items.length && size < 100 && (
            <Group justify="center">
              <Button
                variant="subtle"
                loading={history.isFetching}
                onClick={() => setSize((current) => Math.min(current + HISTORY_PAGE_SIZE, 100))}
              >
                {texts.more}
              </Button>
            </Group>
          )}
        </>
      )}
      <NewAnnouncementModal opened={composing} onClose={() => setComposing(false)} />
      <AnnouncementDetailsDrawer announcement={opened} onClose={() => setOpened(null)} />
    </Stack>
  );
}
