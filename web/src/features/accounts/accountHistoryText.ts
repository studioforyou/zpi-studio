import { pl } from '@/shared/i18n/pl';
import type { AccountHistoryEntry } from './accountHistoryApi';
import type { AccountStatus } from './accountsApi';

const texts = pl.accounts.history.changes;

/** What happened, and the role, permission or statuses it concerns. */
export type EntryDescription = {
  title: string;
  detail: string | null;
};

/** A Polish description of a history entry. */
export function describeEntry(entry: AccountHistoryEntry): EntryDescription {
  switch (entry.type) {
    case 'STATUS_CHANGED': {
      const to = entry.toStatus ?? 'ACTIVE';
      return {
        title: statusTitle(entry.fromStatus, to),
        detail: entry.fromStatus
          ? `${statusName(entry.fromStatus)} → ${statusName(to)}`
          : texts.newStatus(statusName(to)),
      };
    }
    case 'ROLE_GRANTED':
      return { title: texts.roleGranted, detail: roleName(entry.role) };
    case 'ROLE_REVOKED':
      return { title: texts.roleRevoked, detail: roleName(entry.role) };
    case 'PERMISSION_OVERRIDE_ADDED':
      return {
        title: entry.effect === 'REVOKE' ? texts.permissionRevoked : texts.permissionGranted,
        detail: permissionName(entry.permission),
      };
    case 'PERMISSION_OVERRIDE_REMOVED': {
      const was = entry.effect === 'GRANT' ? texts.wasGranted : texts.wasRevoked;
      const name = permissionName(entry.permission);
      return {
        title: texts.permissionReset,
        detail: entry.effect ? (name ? `${name} (${was})` : was) : name,
      };
    }
  }
}

function statusTitle(from: AccountStatus | null, to: AccountStatus): string {
  if (from === null) {
    return to === 'INVITED'
      ? texts.invited
      : to === 'PENDING_APPROVAL'
        ? texts.registered
        : texts.created;
  }
  switch (to) {
    case 'ACTIVE':
      return from === 'INVITED'
        ? texts.invitationAccepted
        : from === 'PENDING_APPROVAL'
          ? texts.approved
          : from === 'SUSPENDED'
            ? texts.reactivated
            : texts.statusChanged;
    case 'SUSPENDED':
      return texts.suspended;
    case 'REJECTED':
      return texts.rejected;
    case 'CLOSED':
      return texts.closed;
    default:
      return texts.statusChanged;
  }
}

function statusName(status: AccountStatus): string {
  return pl.accounts.statuses[status] ?? status;
}

function roleName(role: string | null): string | null {
  return role === null ? null : (pl.accounts.roles[role as keyof typeof pl.accounts.roles] ?? role);
}

function permissionName(permission: string | null): string | null {
  return permission === null
    ? null
    : (pl.accounts.access.names[permission as keyof typeof pl.accounts.access.names] ?? permission);
}
