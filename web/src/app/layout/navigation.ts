import { paths } from '@/app/paths';
import type { pl } from '@/shared/i18n/pl';

export type NavigationItem = {
  path: string;
  labelKey: keyof typeof pl.nav;
};

export const navigation: NavigationItem[] = [
  { path: paths.schedule, labelKey: 'schedule' },
  { path: paths.rooms, labelKey: 'rooms' },
  { path: paths.accounts, labelKey: 'accounts' },
  { path: paths.passes, labelKey: 'passes' },
  { path: paths.trainings, labelKey: 'trainings' },
  { path: paths.communication, labelKey: 'communication' },
  { path: paths.statistics, labelKey: 'statistics' },
  { path: paths.system, labelKey: 'system' },
];
