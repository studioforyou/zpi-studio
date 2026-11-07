import { apiFetch } from '@/shared/api/apiClient';

export type NotificationPlaceholder = { name: string; description: string };

export type NotificationTypeSettings = {
  code: string;
  enabled: boolean;
  /** Sent some time before a start; only then `leadTimesMinutes` applies. */
  timed: boolean;
  /** Longest first; empty when the type is not timed. */
  leadTimesMinutes: number[];
  title: string;
  body: string;
  placeholders: NotificationPlaceholder[];
  updatedAt: string | null;
};

export type NotificationTypeUpdate = Partial<
  Pick<NotificationTypeSettings, 'enabled' | 'leadTimesMinutes' | 'title' | 'body'>
>;

export const notificationTypeKeys = {
  all: ['notification-types'] as const,
};

export async function listNotificationTypes(): Promise<NotificationTypeSettings[]> {
  const response = await apiFetch<{ items: NotificationTypeSettings[] }>(
    '/api/v1/notification-types',
  );
  return response.items;
}

export function updateNotificationType(
  code: string,
  update: NotificationTypeUpdate,
): Promise<NotificationTypeSettings> {
  return apiFetch<NotificationTypeSettings>(`/api/v1/notification-types/${code}`, {
    method: 'PATCH',
    body: update,
  });
}
