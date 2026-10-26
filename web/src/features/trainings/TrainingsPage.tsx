import { Stack, Tabs, Title } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { ServiceTypesPanel } from './services/ServiceTypesPanel';

export function TrainingsPage() {
  const texts = pl.trainings;

  return (
    <Stack>
      <Title order={2}>{texts.title}</Title>
      <Tabs defaultValue="services">
        <Tabs.List aria-label={texts.sectionsLabel}>
          <Tabs.Tab value="services">{texts.tabs.services}</Tabs.Tab>
          <Tabs.Tab value="groups" disabled>
            {texts.tabs.groups}
          </Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="services" pt="md">
          <ServiceTypesPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
