import { apiFetch } from '@/shared/api/apiClient';

export type InvitedRole = 'CLIENT' | 'TRAINER';

export type InviteRequest = {
  email: string;
  firstName: string;
  lastName: string;
  role: InvitedRole;
};

/** Response of `POST /api/v1/invitations`. */
export type NewInvitation = {
  invitationId: string;
  accountId: string;
  email: string;
  expiresAt: string;
};

export function inviteAccount(request: InviteRequest): Promise<NewInvitation> {
  return apiFetch<NewInvitation>('/api/v1/invitations', { method: 'POST', body: request });
}
