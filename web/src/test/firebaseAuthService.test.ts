import { FirebaseError } from 'firebase/app';
import {
  reauthenticateWithCredential,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signOut,
  updatePassword,
  type Auth,
} from 'firebase/auth';
import { expect, test, vi } from 'vitest';
import {
  AuthFailure,
  PasswordChangeFailure,
  PasswordResetFailure,
} from '@/features/auth/authFailure';
import { FirebaseAuthService } from '@/features/auth/FirebaseAuthService';
import type { MeResponse } from '@/features/auth/types';
import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';

vi.mock('firebase/auth', () => ({
  EmailAuthProvider: { credential: (email: string, password: string) => ({ email, password }) },
  reauthenticateWithCredential: vi.fn(),
  updatePassword: vi.fn(),
  sendPasswordResetEmail: vi.fn(),
  signInWithEmailAndPassword: vi.fn(),
  signOut: vi.fn(),
}));

const activeAdmin: MeResponse = {
  accountId: 'a1',
  email: 'admin@example.com',
  status: 'ACTIVE',
  displayName: 'Beata Lis',
  roles: ['ADMIN'],
};

function signedInAuth(): Auth {
  return { currentUser: {}, authStateReady: async () => {} } as unknown as Auth;
}

function reasonOf(promise: Promise<unknown>) {
  return promise.then(
    () => null,
    (error: unknown) => (error instanceof AuthFailure ? error.reason : error),
  );
}

test('signs in an active administrator', async () => {
  const service = new FirebaseAuthService(signedInAuth(), async () => activeAdmin);

  await expect(service.login(' admin@example.com ', 'secret')).resolves.toEqual({
    accountId: 'a1',
    email: 'admin@example.com',
    displayName: 'Beata Lis',
    roles: ['ADMIN'],
  });
  expect(signInWithEmailAndPassword).toHaveBeenCalledWith(
    expect.anything(),
    'admin@example.com',
    'secret',
  );
  expect(signOut).not.toHaveBeenCalled();
});

test('maps wrong credentials without asking the backend', async () => {
  vi.mocked(signInWithEmailAndPassword).mockRejectedValue(
    new FirebaseError('auth/invalid-credential', 'Invalid credential'),
  );
  const fetchMe = vi.fn();
  const service = new FirebaseAuthService(signedInAuth(), fetchMe);

  expect(await reasonOf(service.login('admin@example.com', 'wrong'))).toBe('invalidCredentials');
  expect(fetchMe).not.toHaveBeenCalled();
});

test.each([
  [{ ...activeAdmin, roles: ['TRAINER'] }, 'notAdmin'],
  [{ ...activeAdmin, status: 'PENDING_APPROVAL' }, 'pendingApproval'],
  [{ ...activeAdmin, status: 'SUSPENDED' }, 'accountInactive'],
])('rejects an account that cannot use the panel and signs out', async (me, reason) => {
  const service = new FirebaseAuthService(signedInAuth(), async () => me);

  expect(await reasonOf(service.login('admin@example.com', 'secret'))).toBe(reason);
  expect(signOut).toHaveBeenCalled();
});

test('rejects a Firebase user without an account', async () => {
  const service = new FirebaseAuthService(signedInAuth(), async () => {
    throw new ApiError(403, 'ACCOUNT_NOT_FOUND', null);
  });

  expect(await reasonOf(service.login('admin@example.com', 'secret'))).toBe('accountNotFound');
  expect(signOut).toHaveBeenCalled();
});

test('keeps the user signed in when the session cannot be restored offline', async () => {
  const service = new FirebaseAuthService(signedInAuth(), async () => {
    throw new ApiError(null, NETWORK_ERROR, null);
  });

  await expect(service.restore()).resolves.toBeNull();
  expect(signOut).not.toHaveBeenCalled();
});

test('restores nothing when nobody is signed in', async () => {
  const fetchMe = vi.fn();
  const auth = { currentUser: null, authStateReady: async () => {} } as unknown as Auth;

  await expect(new FirebaseAuthService(auth, fetchMe).restore()).resolves.toBeNull();
  expect(fetchMe).not.toHaveBeenCalled();
});

test('sends the password reset e-mail to the trimmed address', async () => {
  const service = new FirebaseAuthService(signedInAuth(), async () => activeAdmin);

  await service.sendPasswordReset(' admin@example.com ');

  expect(sendPasswordResetEmail).toHaveBeenCalledWith(expect.anything(), 'admin@example.com');
});

test('does not reveal that an address has no user', async () => {
  vi.mocked(sendPasswordResetEmail).mockRejectedValue(
    new FirebaseError('auth/user-not-found', 'no user'),
  );
  const service = new FirebaseAuthService(signedInAuth(), async () => activeAdmin);

  await expect(service.sendPasswordReset('nobody@example.com')).resolves.toBeUndefined();
});

test('explains why the password reset e-mail was not sent', async () => {
  vi.mocked(sendPasswordResetEmail).mockRejectedValue(
    new FirebaseError('auth/too-many-requests', 'slow down'),
  );
  const service = new FirebaseAuthService(signedInAuth(), async () => activeAdmin);

  const failure = await service.sendPasswordReset('admin@example.com').catch((error) => error);

  expect(failure).toBeInstanceOf(PasswordResetFailure);
  expect((failure as PasswordResetFailure).reason).toBe('tooManyAttempts');
});

function userAuth(): Auth {
  return { currentUser: { email: 'admin@example.com' } } as unknown as Auth;
}

test('confirms the current password before changing it', async () => {
  const auth = userAuth();
  const service = new FirebaseAuthService(auth, async () => activeAdmin);

  await service.changePassword('stare-haslo', 'nowe-haslo-123');

  expect(reauthenticateWithCredential).toHaveBeenCalledWith(auth.currentUser, {
    email: 'admin@example.com',
    password: 'stare-haslo',
  });
  expect(updatePassword).toHaveBeenCalledWith(auth.currentUser, 'nowe-haslo-123');
});

test('does not change the password when the current one is wrong', async () => {
  vi.mocked(reauthenticateWithCredential).mockRejectedValue(
    new FirebaseError('auth/invalid-credential', 'wrong'),
  );
  const service = new FirebaseAuthService(userAuth(), async () => activeAdmin);

  const failure = await service.changePassword('zle', 'nowe-haslo-123').catch((error) => error);

  expect(failure).toBeInstanceOf(PasswordChangeFailure);
  expect((failure as PasswordChangeFailure).reason).toBe('wrongPassword');
  expect(updatePassword).not.toHaveBeenCalled();
});
