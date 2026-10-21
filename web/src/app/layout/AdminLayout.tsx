import { AppShell, Button, Group, NavLink, Text } from '@mantine/core';
import { NavLink as RouterNavLink, Outlet, useMatch, useNavigate } from 'react-router';
import { paths } from '@/app/paths';
import { useAuth } from '@/features/auth/AuthProvider';
import { pl } from '@/shared/i18n/pl';
import { navigation, type NavigationItem } from './navigation';

export function AdminLayout() {
  const { session, logout } = useAuth();
  const navigate = useNavigate();

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
          <Group gap="sm">
            <Text>{session?.displayName}</Text>
            <Button variant="subtle" onClick={() => void handleLogout()}>
              {pl.auth.logout}
            </Button>
          </Group>
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
