import { useState } from 'react';
import { Button, Group, Stack, Title } from '@mantine/core';
import { Link } from 'react-router';
import { paths } from '@/app/paths';
import { pl } from '@/shared/i18n/pl';
import { AccountsList } from './AccountsList';
import { InviteAccountModal } from './InviteAccountModal';

export function AccountsPage() {
  const [inviteOpened, setInviteOpened] = useState(false);

  return (
    <Stack gap="lg">
      <Group justify="space-between" align="flex-start">
        <Title order={2}>{pl.accounts.title}</Title>
        <Group gap="sm">
          <Button variant="default" component={Link} to={paths.roles}>
            {pl.accounts.access.matrix.link}
          </Button>
          <Button onClick={() => setInviteOpened(true)}>{pl.accounts.inviteButton}</Button>
        </Group>
      </Group>
      <AccountsList />
      <InviteAccountModal opened={inviteOpened} onClose={() => setInviteOpened(false)} />
    </Stack>
  );
}
