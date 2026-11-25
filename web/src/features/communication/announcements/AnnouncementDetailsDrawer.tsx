import { Alert, Badge, Card, Drawer, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { audienceLabel, formatSentAt } from './announcementFormat';
import {
  announcementKeys,
  getAnnouncement,
  type AnnouncementSummary,
  type RecipientStatus,
} from './announcementsApi';

const statusColor: Record<RecipientStatus, string> = {
  READ: 'green',
  DELIVERED: 'blue',
  APP_ONLY: 'gray',
};

type Props = {
  announcement: AnnouncementSummary | null;
  onClose: () => void;
};

/** A sent announcement: its text, delivery figures and every recipient's status. */
export function AnnouncementDetailsDrawer({ announcement, onClose }: Props) {
  const texts = pl.communication.announcements;
  const details = useQuery({
    queryKey: announcementKeys.details(announcement?.id ?? ''),
    queryFn: () => getAnnouncement(announcement!.id),
    enabled: announcement !== null,
  });

  const figures = announcement && [
    { value: announcement.recipients, label: texts.details.recipients },
    { value: announcement.read, label: texts.details.read },
    { value: announcement.pushDelivered, label: texts.details.pushDelivered },
    ...(announcement.marketing
      ? [{ value: announcement.skippedWithoutConsent, label: texts.details.skipped }]
      : []),
  ];

  return (
    <Drawer
      opened={announcement !== null}
      onClose={onClose}
      position="right"
      size={520}
      title={announcement?.title}
      closeButtonProps={{ 'aria-label': texts.details.close }}
    >
      {announcement && (
        <Stack>
          <Group gap="md" wrap="nowrap">
            <Text c="dimmed" w={110}>
              {texts.details.sent}
            </Text>
            <Text>
              {formatSentAt(announcement.sentAt)}
              {announcement.authorName ? ` · ${announcement.authorName}` : ''}
            </Text>
          </Group>
          <Group gap="md" wrap="nowrap">
            <Text c="dimmed" w={110}>
              {texts.details.audience}
            </Text>
            <Group gap="xs">
              <Text>{audienceLabel(announcement)}</Text>
              {announcement.marketing && (
                <Badge color="orange" variant="light">
                  {texts.marketingBadge}
                </Badge>
              )}
            </Group>
          </Group>
          <Card withBorder>
            <Text style={{ whiteSpace: 'pre-line' }}>{announcement.body}</Text>
          </Card>
          <SimpleGrid cols={figures!.length}>
            {figures!.map((figure) => (
              <Card key={figure.label} withBorder padding="sm">
                <Text fw={700} size="xl">
                  {figure.value}
                </Text>
                <Text size="xs" c="dimmed">
                  {figure.label}
                </Text>
              </Card>
            ))}
          </SimpleGrid>
          <Card withBorder>
            <Text fw={600} mb="xs">
              {texts.details.list}
            </Text>
            {details.isError ? (
              <Alert color="red">{texts.details.loadError}</Alert>
            ) : (
              details.data?.recipients.map((recipient) => (
                <Group
                  key={recipient.accountId}
                  justify="space-between"
                  py={6}
                  style={{ borderTop: '1px solid var(--mantine-color-gray-1)' }}
                  wrap="nowrap"
                >
                  <Text size="sm">
                    {recipient.name}
                    {recipient.about && (
                      <Text span c="dimmed" size="sm">
                        {` · ${texts.details.about(recipient.about)}`}
                      </Text>
                    )}
                  </Text>
                  <Badge color={statusColor[recipient.status]} variant="light">
                    {texts.details.status[recipient.status]}
                  </Badge>
                </Group>
              ))
            )}
          </Card>
        </Stack>
      )}
    </Drawer>
  );
}
