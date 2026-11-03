import { useState, type ReactNode } from 'react';
import {
  Alert,
  Anchor,
  Box,
  Button,
  Grid,
  Group,
  Paper,
  SegmentedControl,
  Stack,
  Tabs,
  Text,
  Title,
  Tooltip,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router';
import { paths } from '@/app/paths';
import { useAuth } from '@/features/auth/AuthProvider';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { AccountDataForm } from './AccountDataForm';
import { AccountStatusModal, type StatusAction } from './AccountStatusModal';
import { RoleBadges, StatusBadge } from './accountBadges';
import {
  accountKeys,
  formatTimestampDate,
  getAccount,
  type AccountDetails,
  type AccountProfile,
} from './accountsApi';
import { ChildProfileForm, StaffProfileForm } from './ProfileForms';

const texts = pl.accounts.details;

/**
 * One profile of an account (`?profile=`, by default the client profile): its data and roles, a
 * bar to switch to the account's other profiles, and the account-wide status actions.
 */
export function AccountDetailsPage() {
  const { accountId = '' } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
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

  const profiles = account.data.profiles;
  const selected = selectedProfile(profiles, searchParams.get('profile'));
  const otherProfiles = profiles.filter((profile) => profile !== selected);

  return (
    <Stack gap="lg">
      {back}
      <Group gap="sm" align="center">
        <Title order={2}>
          {selected ? `${selected.firstName} ${selected.lastName}` : account.data.displayName}
        </Title>
        <StatusBadge status={account.data.status} />
        <RoleBadges roles={profileRoles(account.data, selected)} />
      </Group>
      <Grid gap="xl">
        <Grid.Col span={{ base: 12, md: 8 }}>
          <Stack gap="md">
            {selected && profiles.length > 1 && (
              <Stack gap={4}>
                <Text size="sm" c="dimmed">
                  {texts.profilesLabel}
                </Text>
                <SegmentedControl
                  aria-label={texts.profilesLabel}
                  fullWidth
                  size="md"
                  value={selected.id}
                  onChange={(profileId) => setSearchParams({ profile: profileId })}
                  data={profiles.map((profile) => ({
                    value: profile.id,
                    label: (
                      <Stack gap={0} align="center">
                        <Text size="sm" fw={600}>
                          {profile.firstName} {profile.lastName}
                        </Text>
                        <Text size="xs" c="dimmed">
                          {texts.profileKinds[profile.kind]}
                        </Text>
                      </Stack>
                    ),
                  }))}
                />
              </Stack>
            )}
            {/* Each profile starts on its data, from its stored values. */}
            <Tabs
              key={`${selected?.id}-${account.data.status}`}
              defaultValue="data"
              keepMounted={false}
            >
              <Tabs.List aria-label={texts.tabsLabel}>
                <Tabs.Tab value="data">{texts.tabs.data}</Tabs.Tab>
                <Tabs.Tab value="access" disabled={selected?.kind === 'CHILD'}>
                  {texts.tabs.access}
                </Tabs.Tab>
              </Tabs.List>
              <Tabs.Panel value="data" pt="md">
                <ProfileForm account={account.data} profile={selected} />
              </Tabs.Panel>
              <Tabs.Panel value="access" pt="md">
                {/* Will render the account's roles and permission overrides once they can be managed. */}
                <Text c="dimmed" maw={560}>
                  {texts.accessPlaceholder}
                </Text>
              </Tabs.Panel>
            </Tabs>
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 4 }}>
          <Stack>
            <AccountInfo account={account.data} />
            <AccountActions account={account.data} otherProfiles={otherProfiles} />
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  );
}

/** The asked-for profile, else the client profile, else the staff profile. */
function selectedProfile(
  profiles: AccountProfile[],
  profileId: string | null,
): AccountProfile | undefined {
  return (
    profiles.find((profile) => profile.id === profileId) ??
    profiles.find((profile) => profile.kind === 'CLIENT') ??
    profiles.find((profile) => profile.kind === 'STAFF') ??
    profiles[0]
  );
}

/** The roles of the person behind a profile: a client, the account's staff roles, or a child. */
function profileRoles(account: AccountDetails, profile: AccountProfile | undefined): string[] {
  switch (profile?.kind) {
    case 'CLIENT':
      return account.roles.includes('CLIENT') ? ['CLIENT'] : [];
    case 'STAFF':
      return account.roles.filter((role) => role !== 'CLIENT');
    case 'CHILD':
      return ['CHILD'];
    default:
      return account.roles;
  }
}

function ProfileForm({
  account,
  profile,
}: {
  account: AccountDetails;
  profile: AccountProfile | undefined;
}) {
  if (profile?.kind === 'CHILD') {
    return <ChildProfileForm account={account} profile={profile} />;
  }
  if (profile?.kind === 'STAFF' && account.profiles.length > 1) {
    return <StaffProfileForm account={account} profile={profile} />;
  }
  // The client profile, or the only profile of the account, also holds the login e-mail.
  return <AccountDataForm account={account} />;
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
  rows.unshift([info.email, account.email]);
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

function AccountActions({
  account,
  otherProfiles,
}: {
  account: AccountDetails;
  otherProfiles: AccountProfile[];
}) {
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
                <Button variant="default" onClick={() => setAction('suspend')}>
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
        <AccountStatusModal
          account={account}
          action={action}
          otherProfiles={otherProfiles}
          onClose={() => setAction(null)}
        />
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
        {/* Only explains the action, so it does not look or act like a button. */}
        <Box
          component="span"
          tabIndex={0}
          role="img"
          aria-label={`${texts.actions.hintLabel}: ${hint}`}
          c="dimmed"
          display="inline-flex"
          style={{ cursor: 'default' }}
        >
          <InfoIcon />
        </Box>
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
