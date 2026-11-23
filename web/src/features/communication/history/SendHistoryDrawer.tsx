import { Alert, Badge, Button, Card, Drawer, Group, Stack, Text, Timeline } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { formatSentAt } from '../announcements/announcementFormat';
import { getHistoryItem, historyKeys, type HistoryItem } from './historyApi';
import { recipientNote, statusColor, typeLabel } from './historyFormat';

type Props = {
  item: HistoryItem | null;
  onClose: () => void;
  /** Filters the history to the recipient of the item. */
  onShowPerson: (item: HistoryItem) => void;
};

/** One sent notification: its text (never a chat message's) and every delivery attempt. */
export function SendHistoryDrawer({ item, onClose, onShowPerson }: Props) {
  const texts = pl.communication.history;
  const details = useQuery({
    queryKey: historyKeys.details(item?.id ?? ''),
    queryFn: () => getHistoryItem(item!.id),
    enabled: item !== null,
  });

  const row = (label: string, value: React.ReactNode) => (
    <Group gap="md" wrap="nowrap" align="flex-start">
      <Text c="dimmed" w={110} style={{ flex: 'none' }}>
        {label}
      </Text>
      <div>{value}</div>
    </Group>
  );

  return (
    <Drawer
      opened={item !== null}
      onClose={onClose}
      position="right"
      size={520}
      title={item?.title}
      closeButtonProps={{ 'aria-label': texts.details.close }}
    >
      {item && (
        <Stack>
          {row(
            texts.details.type,
            <Group gap="xs">
              <Badge
                variant="light"
                color={item.type === 'ANNOUNCEMENT' ? 'orange' : 'gray'}
                radius="sm"
                style={{ textTransform: 'none' }}
              >
                {typeLabel(item.type)}
              </Badge>
              <Text size="sm">
                {item.author ? texts.details.from(item.author) : texts.details.automatic}
              </Text>
            </Group>,
          )}
          {row(
            texts.details.recipient,
            <Text>
              {item.recipientName}
              {recipientNote(item) && (
                <Text span c="dimmed" size="sm">
                  {` · ${recipientNote(item)}`}
                </Text>
              )}
            </Text>,
          )}
          {row(
            texts.details.status,
            <Group gap="xs">
              <Badge
                variant="light"
                color={statusColor[item.status]}
                style={{ textTransform: 'none' }}
              >
                {texts.status[item.status]}
              </Badge>
              <Text size="sm">
                {item.readAt
                  ? texts.details.readAt(formatSentAt(item.readAt))
                  : texts.details.unread}
              </Text>
            </Group>,
          )}
          <Card withBorder>
            {item.bodyHidden ? (
              <Text c="dimmed">{texts.details.hiddenBody}</Text>
            ) : (
              <Text style={{ whiteSpace: 'pre-line' }}>{item.body ?? '—'}</Text>
            )}
          </Card>
          <Card withBorder>
            <Text fw={600} mb="sm">
              {texts.details.timeline}
            </Text>
            {details.isError ? (
              <Text c="red" size="sm">
                {texts.details.loadError}
              </Text>
            ) : (
              <Timeline bulletSize={12} lineWidth={2}>
                <Timeline.Item color="gray">
                  <Text size="sm">{`${formatSentAt(item.sentAt)} · ${texts.details.created}`}</Text>
                </Timeline.Item>
                {(details.data?.deliveries ?? []).map((delivery, index) => (
                  <Timeline.Item
                    key={index}
                    color={
                      delivery.status === 'SENT'
                        ? 'green'
                        : delivery.status === 'FAILED' || delivery.status === 'TOKEN_INVALID'
                          ? 'red'
                          : 'gray'
                    }
                  >
                    <Text size="sm">
                      {`${formatSentAt(delivery.attemptedAt)} · ${texts.details.delivery[delivery.status] ?? delivery.status}`}
                    </Text>
                  </Timeline.Item>
                ))}
                {item.readAt && (
                  <Timeline.Item color="green">
                    <Text size="sm">{`${formatSentAt(item.readAt)} · ${texts.details.read}`}</Text>
                  </Timeline.Item>
                )}
              </Timeline>
            )}
          </Card>
          {item.status === 'PUSH_FAILED' && <Alert color="blue">{texts.details.failedHint}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => onShowPerson(item)}>
              {texts.details.showPerson}
            </Button>
          </Group>
        </Stack>
      )}
    </Drawer>
  );
}
