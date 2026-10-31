import { useState, type ReactNode } from 'react';
import {
  ActionIcon,
  Alert,
  Anchor,
  Button,
  Grid,
  Group,
  Paper,
  Stack,
  Tabs,
  Text,
  Title,
  Tooltip,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { paths } from '@/app/paths';
import { useAuth } from '@/features/auth/AuthProvider';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { AccountDataForm } from './AccountDataForm';
import { AccountStatusModal, type StatusAction } from './AccountStatusModal';
import { RoleBadges, StatusBadge } from './accountBadges';
import { accountKeys, formatTimestampDate, getAccount, type AccountDetails } from './accountsApi';

const texts = pl.accounts.details;

/** One account: its personal data, roles and permissions, and the status actions. */
export function AccountDetailsPage() {
  const { accountId = '' } = useParams();
  const account = useQuery({
    queryKey: accountKeys.details(accountId),
    queryFn: () => getAccount(accountId),
  });

  const back = (
    <Anchor component={Link} to={paths.accounts} size="sm">
      ← {texts.back}
    </Anchor>
  );

  if (account.isPending) {
    return (
      <Stack align="flex-start">
        {back}
        <Text>{texts.loading}</Text>
      </Stack>
    );
  }
  if (account.isError) {
    const notFound = account.error instanceof ApiError && account.error.status === 404;
    return (
      <Stack align="flex-start">
        {back}
        <Alert color="red">{notFound ? texts.notFound : texts.loadError}</Alert>
      </Stack>
    );
  }

  return (
    <Stack gap="lg">
      {back}
      <Group gap="sm" align="center">
        <Title order={2}>{account.data.displayName}</Title>
        <StatusBadge status={account.data.status} />
        <RoleBadges roles={account.data.roles} />
      </Group>
      <Grid gap="xl">
        <Grid.Col span={{ base: 12, md: 8 }}>
          <Tabs defaultValue="data" keepMounted={false}>
            <Tabs.List aria-label={texts.tabsLabel}>
              <Tabs.Tab value="data">{texts.tabs.data}</Tabs.Tab>
              <Tabs.Tab value="access">{texts.tabs.access}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="data" pt="md">
              {/* A status change (e.g. closing) starts a fresh form from the stored values. */}
              <AccountDataForm
                key={`${account.data.id}-${account.data.status}`}
                account={account.data}
              />
            </Tabs.Panel>
            <Tabs.Panel value="access" pt="md">
              {/* Will render the account's roles and permission overrides once they can be managed. */}
              <Text c="dimmed" maw={560}>
                {texts.accessPlaceholder}
              </Text>
            </Tabs.Panel>
          </Tabs>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 4 }}>
          <Stack>
            <AccountInfo account={account.data} />
            <AccountActions account={account.data} />
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  );
}

function AccountInfo({ account }: { account: AccountDetails }) {
  const info = texts.info;
  const rows: [string, string][] = [
    [info.createdAt, formatTimestampDate(account.createdAt)],
    [
      info.activatedAt,
      account.activatedAt ? formatTimestampDate(account.activatedAt) : info.notActivated,
    ],
    [info.login, account.hasLogin ? info.hasLogin : info.noLogin],
  ];
  if (account.invitationExpiresAt) {
    rows.push([info.invitation, formatTimestampDate(account.invitationExpiresAt)]);
  }
  return (
    <Paper withBorder radius="md" p="md">
      <Stack gap="xs">
        <Text fw={600}>{info.title}</Text>
        {rows.map(([label, value]) => (
          <Group key={label} justify="space-between" gap="xs" wrap="nowrap">
            <Text size="sm" c="dimmed">
              {label}
            </Text>
            <Text size="sm" ta="right">
              {value}
            </Text>
          </Group>
        ))}
      </Stack>
    </Paper>
  );
}

function AccountActions({ account }: { account: AccountDetails }) {
  const actions = texts.actions;
  const { session } = useAuth();
  const [action, setAction] = useState<StatusAction | null>(null);

  if (account.status === 'CLOSED') {
    return null;
  }
  const own = session?.accountId === account.id;

  return (
    <Paper withBorder radius="md" p="md">
      <Stack gap="sm">
        <Text fw={600}>{actions.title}</Text>
        {own ? (
          <Text size="sm" c="dimmed">
            {actions.ownAccount}
          </Text>
        ) : (
          <>
            {account.status === 'ACTIVE' && (
              <ActionItem hint={actions.suspendHint}>
                <Button variant="default" color="orange" onClick={() => setAction('suspend')}>
                  {actions.suspend}
                </Button>
              </ActionItem>
            )}
            {account.status === 'SUSPENDED' && (
              <ActionItem hint={actions.reactivateHint}>
                <Button variant="light" color="green" onClick={() => setAction('reactivate')}>
                  {actions.reactivate}
                </Button>
              </ActionItem>
            )}
            <ActionItem hint={actions.closeHint}>
              <Button variant="light" color="red" onClick={() => setAction('close')}>
                {actions.close}
              </Button>
            </ActionItem>
          </>
        )}
      </Stack>
      {action && (
        <AccountStatusModal account={account} action={action} onClose={() => setAction(null)} />
      )}
    </Paper>
  );
}

function ActionItem({ hint, children }: { hint: string; children: ReactNode }) {
  return (
    <Group gap="xs" wrap="nowrap">
      {children}
      <Tooltip
        label={hint}
        multiline
        w={260}
        withArrow
        events={{ hover: true, focus: true, touch: true }}
      >
        <ActionIcon
          variant="subtle"
          color="gray"
          radius="xl"
          size="sm"
          aria-label={`${texts.actions.hintLabel}: ${hint}`}
        >
          <InfoIcon />
        </ActionIcon>
      </Tooltip>
    </Group>
  );
}

function InfoIcon() {
  return (
    <svg
      width={16}
      height={16}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx={12} cy={12} r={10} />
      <path d="M12 16v-4M12 8h.01" />
    </svg>
  );
}
