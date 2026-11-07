import { Stack, Tabs, Title } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { NotificationTypesPanel } from './notifications/NotificationTypesPanel';

export function CommunicationPage() {
  const texts = pl.communication;

  return (
    <Stack>
      <Title order={2}>{texts.title}</Title>
      <Tabs defaultValue="notifications">
        <Tabs.List aria-label={texts.sectionsLabel}>
          <Tabs.Tab value="notifications">{texts.tabs.notifications}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="notifications" pt="md">
          <NotificationTypesPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
