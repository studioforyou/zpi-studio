import type {
  OverrideEffect,
  PermissionDefinition,
  PermissionOverride,
  RoleCode,
  RoleDefinition,
} from './accessApi';

/** Order of the permission categories in the tables; unknown categories go last. */
export const categoryOrder = [
  'SCHEDULING',
  'STAFF',
  'CUSTOMER',
  'IDENTITY',
  'MEMBERSHIP',
  'BOOKING',
];

/**
 * Permissions that some feature of the application already checks. The others are in the
 * dictionary but do not change anything yet; add a code here when a feature starts to check it.
 */
export const enforcedPermissions: ReadonlySet<string> = new Set(['CUSTOMER_ACCOUNT_CREATE']);

export type PermissionGroup<T> = { category: string; rows: T[] };

export type AccountPermissionRow = {
  permission: PermissionDefinition;
  /** Display names of the account's roles that give this permission by default. */
  inheritedFrom: string[];
  /** The account's own override, or `null` when the permission follows its roles. */
  override: OverrideEffect | null;
};

export function groupByCategory<T>(
  rows: T[],
  categoryOf: (row: T) => string,
): PermissionGroup<T>[] {
  const groups = new Map<string, T[]>();
  for (const row of rows) {
    const category = categoryOf(row);
    groups.set(category, [...(groups.get(category) ?? []), row]);
  }
  const rank = (category: string) => {
    const index = categoryOrder.indexOf(category);
    return index === -1 ? categoryOrder.length : index;
  };
  return [...groups.entries()]
    .sort(([a], [b]) => rank(a) - rank(b))
    .map(([category, groupRows]) => ({ category, rows: groupRows }));
}

export function accountPermissionRows(
  permissions: PermissionDefinition[],
  roles: RoleDefinition[],
  accountRoles: RoleCode[],
  overrides: PermissionOverride[],
): AccountPermissionRow[] {
  const heldRoles = roles.filter((role) => accountRoles.includes(role.code));
  const overrideOf = new Map(overrides.map((item) => [item.permission, item.effect]));
  return permissions.map((permission) => ({
    permission,
    inheritedFrom: heldRoles
      .filter((role) => role.permissions.includes(permission.code))
      .map((role) => role.displayName),
    override: overrideOf.get(permission.code) ?? null,
  }));
}

/**
 * The effect shown in the Grant / Revoke boxes: the override, or with no override what the
 * roles give (Grant when a role gives the permission, Revoke otherwise).
 */
export function shownEffect(row: AccountPermissionRow): OverrideEffect {
  return row.override ?? (row.inheritedFrom.length > 0 ? 'GRANT' : 'REVOKE');
}
