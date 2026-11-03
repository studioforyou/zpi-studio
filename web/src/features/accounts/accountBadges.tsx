import { Badge, Group } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import type { AccountStatus } from './accountsApi';

const statusColors: Record<AccountStatus, string> = {
  ACTIVE: 'green',
  INVITED: 'blue',
  PENDING_APPROVAL: 'yellow',
  SUSPENDED: 'orange',
  REJECTED: 'gray',
  CLOSED: 'gray',
};

const roleColors: Record<string, string> = {
  CLIENT: 'teal',
  TRAINER: 'grape',
  ADMIN: 'indigo',
  CHILD: 'orange',
};

export function StatusBadge({ status }: { status: AccountStatus }) {
  return (
    <Badge color={statusColors[status]} variant="light" size="sm">
      {pl.accounts.statuses[status]}
    </Badge>
  );
}

export function RoleBadges({ roles }: { roles: string[] }) {
  return (
    <Group gap={4}>
      {roles.map((role) => (
        <Badge key={role} color={roleColors[role] ?? 'gray'} variant="outline" size="sm">
          {pl.accounts.roles[role as keyof typeof pl.accounts.roles] ?? role}
        </Badge>
      ))}
    </Group>
  );
}
