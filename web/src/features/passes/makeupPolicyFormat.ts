import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import type { MakeupPolicy } from './makeupPoliciesApi';

type PolicyTerms = Pick<
  MakeupPolicy,
  'validityDays' | 'maxPerPass' | 'grantOnInTimeCancel' | 'grantOnStudioCancel'
>;

const t = pl.makeupPolicies;

export function limitLabel(maxPerPass: number | null): string {
  return maxPerPass === null ? t.noLimit : t.perPass(maxPerPass);
}

/** When the policy grants a credit, e.g. "odwołanie w terminie, odwołanie przez studio". */
export function grantedForLabel(policy: PolicyTerms): string {
  const reasons = [
    policy.grantOnInTimeCancel ? t.grantedFor.inTime : null,
    policy.grantOnStudioCancel ? t.grantedFor.studio : null,
  ].filter((reason) => reason !== null);
  return reasons.length === 0 ? t.grantedFor.none : reasons.join(', ');
}

/** One-line summary shown next to the policy chosen for a product. */
export function policySummary(policy: PolicyTerms): string {
  return t.summary(
    t.days(policy.validityDays),
    limitLabel(policy.maxPerPass),
    grantedForLabel(policy),
  );
}

export function makeupPolicyErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code !== null) {
    const known = t.errors[error.code as keyof typeof t.errors];
    if (known) {
      return known;
    }
  }
  return t.errors.unknown;
}
