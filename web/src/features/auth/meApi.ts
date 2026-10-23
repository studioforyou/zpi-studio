import { apiFetch } from '@/shared/api/apiClient';
import type { MeResponse } from './types';

export function fetchMe(): Promise<MeResponse> {
  return apiFetch<MeResponse>('/api/v1/me');
}
