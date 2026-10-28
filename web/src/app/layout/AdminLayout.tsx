import { useState } from 'react';
import { AppShell, Button, Group, Menu, NavLink, Text } from '@mantine/core';
import { NavLink as RouterNavLink, Outlet, useMatch, useNavigate } from 'react-router';
import { paths } from '@/app/paths';
import { useAuth } from '@/features/auth/AuthProvider';
import { ChangePasswordModal } from '@/features/auth/ChangePasswordModal';
import { pl } from '@/shared/i18n/pl';
import { navigation, type NavigationItem } from './navigation';

export function AdminLayout() {
  const { session, logout } = useAuth();
  const navigate = useNavigate();
  const [changingPassword, setChangingPassword] = useState(false);

  async function handleLogout() {
    await logout();
    navigate(paths.login, { replace: true });
  }

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 240, breakpoint: 0 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Group gap="xs">
            <Text fw={700}>{pl.appName}</Text>
            <Text c="dimmed">{pl.panelName}</Text>
          </Group>
          <Menu position="bottom-end">
            <Menu.Target>
              <Button variant="subtle" color="gray" rightSection="▾">
                <Text fw={500}>{session?.displayName}</Text>
              </Button>
            </Menu.Target>
            <Menu.Dropdown miw={200} maw={360}>
              <Menu.Label style={{ overflowWrap: 'anywhere' }}>{session?.email}</Menu.Label>
              <Menu.Item onClick={() => setChangingPassword(true)}>
                {pl.auth.changePassword.menuItem}
              </Menu.Item>
              <Menu.Divider />
              <Menu.Item color="red" onClick={() => void handleLogout()}>
                {pl.auth.logout}
              </Menu.Item>
            </Menu.Dropdown>
          </Menu>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        {navigation.map((item) => (
          <NavigationLink key={item.path} item={item} />
        ))}
      </AppShell.Navbar>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
      <ChangePasswordModal opened={changingPassword} onClose={() => setChangingPassword(false)} />
    </AppShell>
  );
}

function NavigationLink({ item }: { item: NavigationItem }) {
  // Mantine styles the active link from its `active` prop, so the match is passed on explicitly.
  const match = useMatch({ path: item.path, end: false });
  return (
    <NavLink
      component={RouterNavLink}
      to={item.path}
      label={pl.nav[item.labelKey]}
      active={match !== null}
    />
  );
}
