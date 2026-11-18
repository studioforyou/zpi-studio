import { apiFetch } from '@/shared/api/apiClient';
import type { Page } from '@/features/passes/customerPassesApi';

export const audiences = ['PERSON', 'GROUP', 'EVENT', 'ALL'] as const;
export type Audience = (typeof audiences)[number];

/** Who an announcement is for; only the target matching the audience is sent. */
export type AudienceInput = {
  audience: Audience;
  accountId: string | null;
  trainingGroupId: string | null;
  eventId: string | null;
  marketing: boolean;
};

export type AnnouncementInput = AudienceInput & { title: string; body: string };

export type AnnouncementPreview = {
  recipients: number;
  /** Recipients who get it only as the guardian of a child in the audience. */
  guardians: number;
  /** People a marketing announcement leaves out for lack of consent. */
  skippedWithoutConsent: number;
};

export type AnnouncementSummary = {
  id: string;
  title: string;
  body: string;
  audience: Audience;
  /** The person, group or class it was for; `null` for everyone. */
  targetName: string | null;
  eventStartsAt: string | null;
  marketing: boolean;
  sentAt: string;
  authorName: string | null;
  recipients: number;
  read: number;
  pushDelivered: number;
  skippedWithoutConsent: number;
};

export type RecipientStatus = 'READ' | 'DELIVERED' | 'APP_ONLY';

export type AnnouncementDetails = {
  announcement: AnnouncementSummary;
  recipients: { accountId: string; name: string; about: string | null; status: RecipientStatus }[];
};

export const TITLE_MAX = 160;
export const BODY_MAX = 2000;
export const HISTORY_PAGE_SIZE = 20;

export const announcementKeys = {
  all: ['announcements'] as const,
  list: (size: number) => [...announcementKeys.all, 'list', size] as const,
  details: (id: string) => [...announcementKeys.all, 'details', id] as const,
  preview: (input: AudienceInput) => [...announcementKeys.all, 'preview', input] as const,
};

/** The request body: the target field of the audience only. */
function audienceBody(input: AudienceInput) {
  return {
    audience: input.audience,
    accountId: input.audience === 'PERSON' ? input.accountId : null,
    trainingGroupId: input.audience === 'GROUP' ? input.trainingGroupId : null,
    eventId: input.audience === 'EVENT' ? input.eventId : null,
    marketing: input.marketing,
  };
}

/** Whether the audience has the target it needs. */
export function hasTarget(input: AudienceInput): boolean {
  switch (input.audience) {
    case 'PERSON':
      return input.accountId !== null;
    case 'GROUP':
      return input.trainingGroupId !== null;
    case 'EVENT':
      return input.eventId !== null;
    case 'ALL':
      return true;
  }
}

export function previewAnnouncement(input: AudienceInput): Promise<AnnouncementPreview> {
  return apiFetch<AnnouncementPreview>('/api/v1/announcements/preview', {
    method: 'POST',
    body: audienceBody(input),
  });
}

export function sendAnnouncement(input: AnnouncementInput): Promise<AnnouncementSummary> {
  return apiFetch<AnnouncementSummary>('/api/v1/announcements', {
    method: 'POST',
    body: { ...audienceBody(input), title: input.title.trim(), body: input.body.trim() },
  });
}

export function listAnnouncements(size: number): Promise<Page<AnnouncementSummary>> {
  return apiFetch<Page<AnnouncementSummary>>(`/api/v1/announcements?page=0&size=${size}`);
}

export function getAnnouncement(id: string): Promise<AnnouncementDetails> {
  return apiFetch<AnnouncementDetails>(`/api/v1/announcements/${id}`);
}
