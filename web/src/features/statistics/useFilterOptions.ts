import { useQuery } from '@tanstack/react-query';
import { listResources, resourceKeys } from '@/features/schedule/resourcesApi';
import {
  listAllServiceTypes,
  listStaff,
  listTrainingGroups,
  scheduleKeys,
} from '@/features/schedule/scheduleApi';
import { pl } from '@/shared/i18n/pl';

const texts = pl.statistics.filters;

export type FilterOption = { value: string; label: string };
export type FilterOptionGroup = { group: string; items: FilterOption[] };

export type FilterOptions = {
  /** Service types grouped by category. */
  services: FilterOptionGroup[];
  staff: FilterOption[];
  groups: FilterOption[];
  rooms: FilterOption[];
  isError: boolean;
};

/** Order of the service categories in the list. */
const categoryOrder = ['GROUP_CLASS', 'WORKSHOP', 'PERSONAL_TRAINING'];

/**
 * What the event filters can choose from. Inactive services, staff, groups and rooms are offered
 * too: past events in the period may still use them.
 */
export function useFilterOptions(): FilterOptions {
  const services = useQuery({ queryKey: scheduleKeys.serviceTypes, queryFn: listAllServiceTypes });
  const staff = useQuery({ queryKey: scheduleKeys.staff, queryFn: listStaff });
  const groups = useQuery({ queryKey: scheduleKeys.trainingGroups, queryFn: listTrainingGroups });
  const rooms = useQuery({ queryKey: resourceKeys.list(), queryFn: listResources });

  const byCategory = new Map<string, FilterOption[]>();
  for (const service of services.data ?? []) {
    const category = categoryOrder.includes(service.category) ? service.category : 'other';
    byCategory.set(category, [
      ...(byCategory.get(category) ?? []),
      { value: service.id, label: service.name },
    ]);
  }
  return {
    services: [...categoryOrder, 'other']
      .filter((category) => byCategory.has(category))
      .map((category) => ({
        group: texts.categories[category],
        items: sortedByLabel(byCategory.get(category) ?? []),
      })),
    staff: sortedByLabel(
      (staff.data ?? []).map((member) => ({ value: member.id, label: member.displayName })),
    ),
    groups: sortedByLabel(
      (groups.data ?? []).map((group) => ({ value: group.id, label: group.name })),
    ),
    rooms: sortedByLabel(
      (rooms.data ?? [])
        .filter((room) => room.kind === 'ROOM')
        .map((room) => ({ value: room.id, label: room.name })),
    ),
    isError: services.isError || staff.isError || groups.isError || rooms.isError,
  };
}

function sortedByLabel(options: FilterOption[]): FilterOption[] {
  return [...options].sort((a, b) => a.label.localeCompare(b.label, 'pl'));
}
