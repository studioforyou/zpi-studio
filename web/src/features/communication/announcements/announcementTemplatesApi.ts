import { apiFetch } from '@/shared/api/apiClient';

/** Reusable announcement content; `marketing` marks an offer rather than a studio message. */
export type AnnouncementTemplate = {
  id: string;
  name: string;
  title: string;
  body: string;
  marketing: boolean;
  updatedAt: string;
};

export type AnnouncementTemplateInput = Pick<
  AnnouncementTemplate,
  'name' | 'title' | 'body' | 'marketing'
>;

export const TEMPLATE_NAME_MAX = 100;

export const announcementTemplateKeys = {
  all: ['announcement-templates'] as const,
};

export async function listAnnouncementTemplates(): Promise<AnnouncementTemplate[]> {
  const response = await apiFetch<{ items: AnnouncementTemplate[] }>(
    '/api/v1/announcement-templates',
  );
  return response.items;
}

function requestBody(input: AnnouncementTemplateInput) {
  return {
    name: input.name.trim(),
    title: input.title.trim(),
    body: input.body.trim(),
    marketing: input.marketing,
  };
}

export function createAnnouncementTemplate(
  input: AnnouncementTemplateInput,
): Promise<AnnouncementTemplate> {
  return apiFetch<AnnouncementTemplate>('/api/v1/announcement-templates', {
    method: 'POST',
    body: requestBody(input),
  });
}

export function updateAnnouncementTemplate(
  id: string,
  input: AnnouncementTemplateInput,
): Promise<AnnouncementTemplate> {
  return apiFetch<AnnouncementTemplate>(`/api/v1/announcement-templates/${id}`, {
    method: 'PATCH',
    body: requestBody(input),
  });
}

export function deleteAnnouncementTemplate(id: string): Promise<void> {
  return apiFetch<void>(`/api/v1/announcement-templates/${id}`, { method: 'DELETE' });
}
