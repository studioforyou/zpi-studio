import { apiFetch } from '@/shared/api/apiClient';

export const serviceCategories = ['GROUP_CLASS', 'PERSONAL_TRAINING', 'WORKSHOP'] as const;
export type ServiceCategory = (typeof serviceCategories)[number];

export type BookingPolicyValues = {
  cancellationAllowed: boolean;
  freeCancelHours: number;
  lateCancelAllowed: boolean;
  lateCancelForfeitsEntry: boolean;
  bookingOpensDaysBefore: number;
  bookingClosesMinutesBefore: number;
  waitlistEnabled: boolean;
  waitlistConfirmMinutes: number;
  autoCancelEnabled: boolean;
  autoCancelMinParticipants: number | null;
  autoCancelHoursBefore: number | null;
};

export type ServiceBookingPolicy = {
  overrides: Partial<BookingPolicyValues> | null;
  effective: BookingPolicyValues;
};

export type ServiceType = {
  id: string;
  name: string;
  description: string | null;
  category: ServiceCategory;
  level: string | null;
  minAge: number | null;
  maxAge: number | null;
  defaultDurationMinutes: number;
  minParticipants: number | null;
  maxParticipants: number | null;
  calendarColor: string | null;
  isActive: boolean;
  bookingPolicy: ServiceBookingPolicy;
};

export type ServiceTypeInput = Omit<ServiceType, 'id' | 'isActive' | 'bookingPolicy'> & {
  bookingPolicy: BookingPolicyValues | null;
};
export type ServiceTypeUpdate = Partial<ServiceTypeInput & { isActive: boolean }>;

export const serviceTypeKeys = {
  all: ['service-types'] as const,
};

export async function listServiceTypes(): Promise<ServiceType[]> {
  const response = await apiFetch<{ items: ServiceType[] }>('/api/v1/service-types');
  return response.items;
}

export function getBookingDefaults(): Promise<BookingPolicyValues> {
  return apiFetch<BookingPolicyValues>('/api/v1/service-types/booking-defaults');
}

export function createServiceType(input: ServiceTypeInput): Promise<ServiceType> {
  return apiFetch<ServiceType>('/api/v1/service-types', { method: 'POST', body: input });
}

export function updateServiceType(id: string, input: ServiceTypeUpdate): Promise<ServiceType> {
  return apiFetch<ServiceType>(`/api/v1/service-types/${id}`, { method: 'PATCH', body: input });
}
