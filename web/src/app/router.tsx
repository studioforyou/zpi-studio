import { Anchor, Stack, Title } from '@mantine/core';
import { createBrowserRouter, Link, Navigate, type RouteObject } from 'react-router';
import { AccountsPage } from '@/features/accounts/AccountsPage';
import { LoginPage } from '@/features/auth/LoginPage';
import { RequireAuth } from '@/features/auth/RequireAuth';
import { CommunicationPage } from '@/features/communication/CommunicationPage';
import { PassesPage } from '@/features/passes/PassesPage';
import { RoomsPage } from '@/features/schedule/RoomsPage';
import { SchedulePage } from '@/features/schedule/SchedulePage';
import { StatisticsPage } from '@/features/statistics/StatisticsPage';
import { SystemStatusPage } from '@/features/system/SystemStatusPage';
import { TrainingsPage } from '@/features/trainings/TrainingsPage';
import { pl } from '@/shared/i18n/pl';
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
          { path: paths.passes, element: <PassesPage /> },
          { path: paths.trainings, element: <TrainingsPage /> },
          { path: paths.communication, element: <CommunicationPage /> },
          { path: paths.statistics, element: <StatisticsPage /> },
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
