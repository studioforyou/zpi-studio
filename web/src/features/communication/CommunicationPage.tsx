import { Stack, Tabs, Title } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { AnnouncementsPanel } from './announcements/AnnouncementsPanel';
import { SendHistoryPanel } from './history/SendHistoryPanel';
import { NotificationTypesPanel } from './notifications/NotificationTypesPanel';

export function CommunicationPage() {
  const texts = pl.communication;

  return (
    <Stack>
      <Title order={2}>{texts.title}</Title>
      <Tabs defaultValue="announcements" keepMounted={false}>
        <Tabs.List aria-label={texts.sectionsLabel}>
          <Tabs.Tab value="announcements">{texts.tabs.announcements}</Tabs.Tab>
          <Tabs.Tab value="history">{texts.tabs.history}</Tabs.Tab>
          <Tabs.Tab value="notifications">{texts.tabs.notifications}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="announcements" pt="md">
          <AnnouncementsPanel />
        </Tabs.Panel>
        <Tabs.Panel value="history" pt="md">
          <SendHistoryPanel />
        </Tabs.Panel>
        <Tabs.Panel value="notifications" pt="md">
          <NotificationTypesPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
