import { apiFetch } from '@/shared/api/apiClient';

/** What a credit's expiry is compared with: the class it books or the moment of booking. */
export const makeupExpiryChecks = ['EVENT_DATE', 'BOOK_DATE'] as const;
export type MakeupExpiryCheck = (typeof makeupExpiryChecks)[number];

export type MakeupPolicy = {
  id: string;
  name: string;
  validityDays: number;
  /** `null` = no limit per pass. */
  maxPerPass: number | null;
  grantOnInTimeCancel: boolean;
  grantOnStudioCancel: boolean;
  expiryCheckedAgainst: MakeupExpiryCheck;
  isActive: boolean;
  createdAt: string;
  /** Active pass products using the policy. */
  productCount: number;
};

export type MakeupPolicyInput = {
  name: string;
  validityDays: number;
  maxPerPass: number | null;
  grantOnInTimeCancel: boolean;
  grantOnStudioCancel: boolean;
  expiryCheckedAgainst: MakeupExpiryCheck;
};

export async function listMakeupPolicies(): Promise<MakeupPolicy[]> {
  const response = await apiFetch<{ items: MakeupPolicy[] }>('/api/v1/makeup-policies');
  return response.items;
}

export function createMakeupPolicy(policy: MakeupPolicyInput): Promise<MakeupPolicy> {
  return apiFetch<MakeupPolicy>('/api/v1/makeup-policies', { method: 'POST', body: policy });
}

/** Applies only to credits granted from now on; granted credits keep their expiry. */
export function updateMakeupPolicy(id: string, policy: MakeupPolicyInput): Promise<MakeupPolicy> {
  return apiFetch<MakeupPolicy>(`/api/v1/makeup-policies/${id}`, { method: 'PUT', body: policy });
}

export function deactivateMakeupPolicy(id: string): Promise<MakeupPolicy> {
  return apiFetch<MakeupPolicy>(`/api/v1/makeup-policies/${id}/deactivate`, { method: 'POST' });
}

export function activateMakeupPolicy(id: string): Promise<MakeupPolicy> {
  return apiFetch<MakeupPolicy>(`/api/v1/makeup-policies/${id}/activate`, { method: 'POST' });
}
