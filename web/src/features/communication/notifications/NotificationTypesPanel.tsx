import { useState } from 'react';
import { Alert, Badge, Button, Card, Group, Stack, Switch, Text, Title } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { formatLeadTime } from './leadTime';
import { NotificationTypeFormModal } from './NotificationTypeFormModal';
import {
  listNotificationTypes,
  notificationTypeKeys,
  updateNotificationType,
  type NotificationTypeSettings,
} from './notificationTypesApi';
import { typeTexts } from './typeTexts';

export function NotificationTypesPanel() {
  const texts = pl.communication.notifications;
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<NotificationTypeSettings | null>(null);
  const types = useQuery({ queryKey: notificationTypeKeys.all, queryFn: listNotificationTypes });
  const toggle = useMutation({
    mutationFn: ({ code, enabled }: { code: string; enabled: boolean }) =>
      updateNotificationType(code, { enabled }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: notificationTypeKeys.all }),
  });

  return (
    <Stack>
      <Stack gap="xs" maw={720}>
        <Title order={3}>{texts.title}</Title>
        <Text size="sm" c="dimmed">
          {texts.intro}
        </Text>
      </Stack>
      {toggle.isError && <Alert color="red">{texts.toggleError}</Alert>}
      {types.isPending ? (
        <Text>{texts.loading}</Text>
      ) : types.isError ? (
        <Alert color="red" title={texts.loadError}>
          <Button variant="subtle" onClick={() => void types.refetch()}>
            {texts.retry}
          </Button>
        </Alert>
      ) : types.data.length === 0 ? (
        <Text c="dimmed">{texts.empty}</Text>
      ) : (
        types.data.map((type) => {
          const { name, description } = typeTexts(type.code);
          return (
            <Card key={type.code} withBorder padding="md" maw={720}>
              <Stack gap="sm">
                <Group justify="space-between" align="flex-start" wrap="nowrap">
                  <Stack gap={2}>
                    <Text fw={600}>{name}</Text>
                    {description && (
                      <Text size="sm" c="dimmed">
                        {description}
                      </Text>
                    )}
                  </Stack>
                  <Switch
                    label={texts.enabled}
                    aria-label={`${texts.enabled}: ${name}`}
                    checked={type.enabled}
                    disabled={toggle.isPending}
                    onChange={(event) =>
                      toggle.mutate({ code: type.code, enabled: event.currentTarget.checked })
                    }
                  />
                </Group>
                {type.timed && (
                  <Group gap="xs">
                    <Text size="sm">{texts.leadTimes}:</Text>
                    {type.leadTimesMinutes.map((minutes) => (
                      <Badge key={minutes} variant="light">
                        {texts.before(formatLeadTime(minutes))}
                      </Badge>
                    ))}
                  </Group>
                )}
                <Text size="sm">
                  {texts.content}:{' '}
                  <Text span size="sm" c="dimmed">
                    {type.body}
                  </Text>
                </Text>
                <Group justify="flex-end">
                  <Button variant="light" onClick={() => setEditing(type)}>
                    {texts.edit}
                  </Button>
                </Group>
              </Stack>
            </Card>
          );
        })
      )}
      {editing && (
        <NotificationTypeFormModal
          key={editing.code}
          type={editing}
          opened
          onClose={() => setEditing(null)}
        />
      )}
    </Stack>
  );
}
