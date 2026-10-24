import { useMemo } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import {
  getStudio,
  listAllServiceTypes,
  listStaff,
  listTrainingGroups,
  scheduleKeys,
  type ServiceTypeDetails,
  type StaffMember,
  type TrainingGroup,
} from './scheduleApi';
import {
  listResources,
  listServiceTypeRules,
  resourceKeys,
  type Resource,
  type ServiceTypeRule,
} from './resourcesApi';
import { DEFAULT_TIME_ZONE } from './studioTime';

export type ScheduleLookups = {
  /** The studio's IANA time zone; all times in the schedule are shown in it. */
  timeZone: string;
  staff: StaffMember[];
  staffById: ReadonlyMap<string, StaffMember>;
  serviceTypes: ServiceTypeDetails[];
  serviceTypeById: ReadonlyMap<string, ServiceTypeDetails>;
  rooms: Resource[];
  resourceById: ReadonlyMap<string, Resource>;
  groups: TrainingGroup[];
  isError: boolean;
};

/** Staff, service types, rooms, groups and the studio's time zone used by the schedule. */
export function useScheduleLookups(): ScheduleLookups {
  const studio = useQuery({
    queryKey: scheduleKeys.studio,
    queryFn: getStudio,
    staleTime: Infinity,
  });
  const staff = useQuery({ queryKey: scheduleKeys.staff, queryFn: listStaff });
  const serviceTypes = useQuery({
    queryKey: scheduleKeys.serviceTypes,
    queryFn: listAllServiceTypes,
  });
  const resources = useQuery({ queryKey: resourceKeys.list(), queryFn: listResources });
  const groups = useQuery({ queryKey: scheduleKeys.trainingGroups, queryFn: listTrainingGroups });

  const timeZone = studio.data?.timeZone ?? DEFAULT_TIME_ZONE;
  const isError = staff.isError || serviceTypes.isError || resources.isError || groups.isError;
  return useMemo(() => {
    const allResources = resources.data ?? [];
    return {
      timeZone,
      staff: staff.data ?? [],
      staffById: new Map((staff.data ?? []).map((member) => [member.id, member])),
      serviceTypes: serviceTypes.data ?? [],
      serviceTypeById: new Map((serviceTypes.data ?? []).map((type) => [type.id, type])),
      rooms: allResources,
      resourceById: new Map(allResources.map((resource) => [resource.id, resource])),
      groups: groups.data ?? [],
      isError,
    };
  }, [timeZone, staff.data, serviceTypes.data, resources.data, groups.data, isError]);
}

/** The service types allowed in each of the given rooms; a room is missing until its rules load. */
export function useRoomRules(rooms: Resource[]): ReadonlyMap<string, ServiceTypeRule[]> {
  const results = useQueries({
    queries: rooms.map((room) => ({
      queryKey: resourceKeys.rules(room.id),
      queryFn: () => listServiceTypeRules(room.id),
    })),
  });
  const rules = new Map<string, ServiceTypeRule[]>();
  results.forEach((result, index) => {
    if (result.data) {
      rules.set(rooms[index].id, result.data);
    }
  });
  return rules;
}
