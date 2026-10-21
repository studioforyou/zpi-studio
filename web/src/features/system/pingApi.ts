import { apiFetch } from '@/shared/api/apiClient';

// TODO(api): switch to GET /actuator/health once it exists in the backend (#189).
export function pingBackend(): Promise<string> {
  return apiFetch<string>('/api/ping', { responseType: 'text' });
}
