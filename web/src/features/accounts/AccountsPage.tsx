import { useState } from 'react';
import { Button, Group, Stack, Title } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { CustomerActivitySection } from './CustomerActivitySection';
import { InviteAccountModal } from './InviteAccountModal';

export function AccountsPage() {
  const [inviteOpened, setInviteOpened] = useState(false);

  return (
    <Stack gap="lg">
      <Group justify="space-between" align="flex-start">
        <Title order={2}>{pl.accounts.title}</Title>
        <Button onClick={() => setInviteOpened(true)}>{pl.accounts.inviteButton}</Button>
      </Group>
      <CustomerActivitySection />
      <InviteAccountModal opened={inviteOpened} onClose={() => setInviteOpened(false)} />
    </Stack>
  );
}
