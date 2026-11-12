import { Anchor, Stack, Title } from '@mantine/core';
import { createBrowserRouter, Link, Navigate, type RouteObject } from 'react-router';
import { AccountDetailsPage } from '@/features/accounts/AccountDetailsPage';
import { AccountHistoryPage } from '@/features/accounts/AccountHistoryPage';
import { AccountsPage } from '@/features/accounts/AccountsPage';
import { RolesPage } from '@/features/accounts/RolesPage';
import { LoginPage } from '@/features/auth/LoginPage';
import { RequireAuth } from '@/features/auth/RequireAuth';
import { InvitePage } from '@/features/invitations/InvitePage';
import { ResetPasswordPage } from '@/features/auth/ResetPasswordPage';
import { CommunicationPage } from '@/features/communication/CommunicationPage';
import { PassesPage } from '@/features/passes/PassesPage';
import { RoomsPage } from '@/features/schedule/RoomsPage';
import { SchedulePage } from '@/features/schedule/SchedulePage';
import { SystemStatusPage } from '@/features/system/SystemStatusPage';
import { TrainingsPage } from '@/features/trainings/TrainingsPage';
import { pl } from '@/shared/i18n/pl';
import { FullPageLoader } from '@/shared/ui/FullPageLoader';
import { AdminLayout } from './layout/AdminLayout';
import { paths } from './paths';

const notFoundPage = (
  <Stack align="flex-start">
    <Title order={2}>{pl.common.notFoundTitle}</Title>
    <Anchor component={Link} to={paths.schedule}>
      {pl.common.backToPanel}
    </Anchor>
  </Stack>
);

export const appRoutes: RouteObject[] = [
  { path: paths.login, element: <LoginPage /> },
  // Public: opened by invited clients and trainers from the invitation e-mail.
  { path: paths.invite, element: <InvitePage /> },
  { path: paths.resetPassword, element: <ResetPasswordPage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AdminLayout />,
        children: [
          { index: true, element: <Navigate to={paths.schedule} replace /> },
          { path: paths.schedule, element: <SchedulePage /> },
          { path: paths.rooms, element: <RoomsPage /> },
          { path: paths.accounts, element: <AccountsPage /> },
          { path: `${paths.accounts}/:accountId`, element: <AccountDetailsPage /> },
          { path: paths.roles, element: <RolesPage /> },
          { path: paths.accountHistory, element: <AccountHistoryPage /> },
          { path: paths.passes, element: <PassesPage /> },
          { path: paths.trainings, element: <TrainingsPage /> },
          { path: paths.communication, element: <CommunicationPage /> },
          {
            path: paths.statistics,
            // Loaded when first opened: its chart library would otherwise enlarge every page.
            lazy: async () => {
              const { StatisticsPage } = await import('@/features/statistics/StatisticsPage');
              return { Component: StatisticsPage };
            },
            hydrateFallbackElement: <FullPageLoader />,
          },
          { path: paths.system, element: <SystemStatusPage /> },
          { path: '*', element: notFoundPage },
        ],
      },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(appRoutes);
}
