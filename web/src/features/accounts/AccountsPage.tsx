import { useState } from 'react';
import { Button, Group } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { PlaceholderPage } from '@/shared/ui/PlaceholderPage';
import { InviteAccountModal } from './InviteAccountModal';

export function AccountsPage() {
  const [inviteOpened, setInviteOpened] = useState(false);

  return (
    <>
      <Group justify="space-between" align="flex-start">
        <PlaceholderPage title={pl.nav.accounts} />
        <Button onClick={() => setInviteOpened(true)}>{pl.accounts.inviteButton}</Button>
      </Group>
      <InviteAccountModal opened={inviteOpened} onClose={() => setInviteOpened(false)} />
    </>
  );
}
