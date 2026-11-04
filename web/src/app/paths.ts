export const paths = {
  login: '/login',
  invite: '/invite',
  resetPassword: '/reset-password',
  schedule: '/schedule',
  rooms: '/rooms',
  accounts: '/accounts',
  accountDetails: (accountId: string, profileId?: string | null) =>
    profileId ? `/accounts/${accountId}?profile=${profileId}` : `/accounts/${accountId}`,
  passes: '/passes',
  trainings: '/trainings',
  communication: '/communication',
  statistics: '/statistics',
  system: '/system',
} as const;
