import { apiFetch } from '@/shared/api/apiClient';

/** Only rooms exist for now; the backend rejects other values. */
export const resourceKinds = ['ROOM'] as const;
export type ResourceKind = (typeof resourceKinds)[number];

export type Resource = {
  id: string;
  name: string;
  kind: ResourceKind;
  parentResourceId: string | null;
  /** How many events (e.g. personal trainings) can use the room at the same time. */
  capacityUnits: number;
  /** Maximum number of people in group classes; null = no limit. */
  maxPeople: number | null;
  description: string | null;
  isActive: boolean;
};

/** A service type allowed in a room; `isDefault` makes the room that service's default room. */
export type ServiceTypeAssignment = {
  serviceTypeId: string;
  isDefault: boolean;
};

export type ResourceInput = {
  name: string;
  kind: ResourceKind;
  parentResourceId: string | null;
  capacityUnits: number;
  maxPeople: number | null;
  description: string | null;
  serviceTypes: ServiceTypeAssignment[];
};

/**
 * Partial update: missing fields stay unchanged, `null` clears a value and `serviceTypes`
 * replaces the whole set of allowed service types.
 */
export type ResourceUpdate = Partial<ResourceInput & { isActive: boolean }>;

export type ServiceTypeRule = ServiceTypeAssignment & {
  serviceTypeName: string | null;
};

export type ServiceType = {
  id: string;
  name: string;
};

type ListResponse<T> = { items: T[] };

export const resourceKeys = {
  all: ['resources'] as const,
  list: () => [...resourceKeys.all, 'list'] as const,
  rules: (resourceId: string) => [...resourceKeys.all, resourceId, 'service-types'] as const,
  serviceTypes: ['service-types'] as const,
};

export async function listResources(): Promise<Resource[]> {
  const response = await apiFetch<ListResponse<Resource>>('/api/v1/resources');
  return response.items;
}

export function createResource(input: ResourceInput): Promise<Resource> {
  return apiFetch<Resource>('/api/v1/resources', { method: 'POST', body: input });
}

export function updateResource(id: string, update: ResourceUpdate): Promise<Resource> {
  return apiFetch<Resource>(`/api/v1/resources/${id}`, { method: 'PATCH', body: update });
}

export async function listServiceTypeRules(resourceId: string): Promise<ServiceTypeRule[]> {
  const response = await apiFetch<ListResponse<ServiceTypeRule>>(
    `/api/v1/resources/${resourceId}/service-types`,
  );
  return response.items;
}

export async function listServiceTypes(): Promise<ServiceType[]> {
  const response = await apiFetch<ListResponse<ServiceType>>('/api/v1/service-types');
  return response.items;
}
