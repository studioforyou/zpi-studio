import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';

type KnownCode = Exclude<keyof typeof pl.rooms.errors, 'network' | 'unexpected'>;

function isKnownCode(code: string): code is KnownCode {
  return code in pl.rooms.errors && code !== 'network' && code !== 'unexpected';
}

/** Polish message for an error returned by the room endpoints, chosen by its API code. */
export function roomErrorMessage(error: Error): string {
  if (!(error instanceof ApiError)) {
    return pl.rooms.errors.unexpected;
  }
  if (error.code === NETWORK_ERROR) {
    return pl.rooms.errors.network;
  }
  if (error.code !== null && isKnownCode(error.code)) {
    return pl.rooms.errors[error.code];
  }
  return pl.rooms.errors.unexpected;
}
