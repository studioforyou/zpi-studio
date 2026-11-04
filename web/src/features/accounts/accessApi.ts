import { apiFetch } from '@/shared/api/apiClient';
import { accountKeys } from './accountsApi';

export type RoleCode = 'CLIENT' | 'TRAINER' | 'ADMIN';

/** A role with the permissions it gives by default; `ADMIN` has every permission. */
export type RoleDefinition = {
  code: RoleCode;
  displayName: string;
  permissions: string[];
};

export type PermissionDefinition = {
  code: string;
  category: string;
  description: string;
};

export type RoleAssignment = {
  code: RoleCode;
  displayName: string;
  grantedBy: string | null;
  grantedAt: string;
};

export type OverrideEffect = 'GRANT' | 'REVOKE';

/** A permission given to (`GRANT`) or taken from (`REVOKE`) one account on top of its roles. */
export type PermissionOverride = {
  permission: string;
  effect: OverrideEffect;
  grantedBy: string;
  grantedAt: string;
};

export const accessKeys = {
  roles: () => ['roles'] as const,
  permissions: () => ['permissions'] as const,
  accountRoles: (accountId: string) => [...accountKeys.details(accountId), 'roles'] as const,
  accountOverrides: (accountId: string) =>
    [...accountKeys.details(accountId), 'permission-overrides'] as const,
};

export async function listRoles(): Promise<RoleDefinition[]> {
  return (await apiFetch<{ items: RoleDefinition[] }>('/api/v1/roles')).items;
}

export async function listPermissions(): Promise<PermissionDefinition[]> {
  return (await apiFetch<{ items: PermissionDefinition[] }>('/api/v1/permissions')).items;
}

export async function listAccountRoles(accountId: string): Promise<RoleAssignment[]> {
  return (await apiFetch<{ items: RoleAssignment[] }>(`/api/v1/accounts/${accountId}/roles`)).items;
}

export function grantRole(accountId: string, code: RoleCode): Promise<RoleAssignment> {
  return apiFetch<RoleAssignment>(`/api/v1/accounts/${accountId}/roles`, {
    method: 'POST',
    body: { code },
  });
}

export function revokeRole(accountId: string, code: RoleCode): Promise<void> {
  return apiFetch<void>(`/api/v1/accounts/${accountId}/roles/${code}`, { method: 'DELETE' });
}

export async function listPermissionOverrides(accountId: string): Promise<PermissionOverride[]> {
  return (
    await apiFetch<{ items: PermissionOverride[] }>(
      `/api/v1/accounts/${accountId}/permission-overrides`,
    )
  ).items;
}

export function addPermissionOverride(
  accountId: string,
  permission: string,
  effect: OverrideEffect,
): Promise<PermissionOverride> {
  return apiFetch<PermissionOverride>(`/api/v1/accounts/${accountId}/permission-overrides`, {
    method: 'POST',
    body: { permission, effect },
  });
}

export function removePermissionOverride(accountId: string, permission: string): Promise<void> {
  return apiFetch<void>(`/api/v1/accounts/${accountId}/permission-overrides/${permission}`, {
    method: 'DELETE',
  });
}
