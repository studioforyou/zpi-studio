import { formatDateTime, formatTimeRange } from '@/features/schedule/scheduleFormat';
import { toZoned } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import type { PassHistoryActor, PassHistoryEntry, PassHistorySession } from './passHistoryApi';
import { daysInclusive, formatDate } from './passFormat';

const texts = pl.passHistory;

/** `Beata Lis · studio`, or who it was when the change has no recorded author. */
export function actorText(entry: Pick<PassHistoryEntry, 'actor' | 'kind'>): string {
  if (entry.actor) {
    return personText(entry.actor);
  }
  return entry.kind === 'RENEWAL_DECLINED' ? texts.actor.participant : texts.actor.system;
}

function personText(actor: PassHistoryActor): string {
  return `${actor.name ?? texts.actor.unknownName} · ${texts.actor.roles[actor.role]}`;
}

/** `Pole dance — podstawy · 27.10.2026 18:00–19:00`. */
export function sessionHeading(session: PassHistorySession, timeZone: string): string {
  const title = session.title ?? session.serviceTypeName ?? texts.session.unknown;
  if (!session.startsAt || !session.endsAt) {
    return title;
  }
  const date = formatDate(toZoned(session.startsAt, timeZone).date);
  return `${title} · ${date} ${formatTimeRange(session.startsAt, session.endsAt, timeZone)}`;
}

/** How the place was booked, cancelled and attended, one fact per line. */
export function sessionFacts(
  session: PassHistorySession,
  timeZone: string,
  now = new Date(),
): string[] {
  const facts: string[] = [];
  const booked = texts.session.sources[session.source];
  facts.push(
    session.bookedBy ? `${booked}, ${texts.session.by(personText(session.bookedBy))}` : booked,
  );
  if (session.status === 'CANCELLED') {
    const kind = session.cancelKind
      ? texts.session.cancelKinds[session.cancelKind]
      : texts.session.cancelKinds.STUDIO;
    const parts: string[] = [kind];
    if (session.cancelledBy) {
      parts.push(texts.session.by(personText(session.cancelledBy)));
    }
    const when = session.cancelledAt ? ` · ${formatDateTime(session.cancelledAt, timeZone)}` : '';
    facts.push(`${parts.join(', ')}${when}`);
  } else if (session.eventCancelled) {
    facts.push(texts.session.eventCancelled);
  } else if (
    session.attendance !== 'UNMARKED' ||
    (session.endsAt !== null && new Date(session.endsAt) < now)
  ) {
    facts.push(texts.session.attendance[session.attendance]);
  }
  return facts;
}

/** What the change did, beyond its title, the balance and the class it concerns. */
export function changeDetails(
  entry: PassHistoryEntry,
  purchased: boolean,
  timeZone: string,
): string[] {
  const details = texts.details;
  const validity =
    entry.validUntilBefore && entry.validUntilAfter
      ? details.validityChange(
          formatDate(entry.validUntilBefore),
          formatDate(entry.validUntilAfter),
        )
      : null;
  switch (entry.kind) {
    case 'ACTIVATED':
      return [
        [
          purchased ? details.purchased : details.assigned,
          entry.validUntilAfter ? details.validUntil(formatDate(entry.validUntilAfter)) : null,
        ]
          .filter(Boolean)
          .join(', '),
      ];
    case 'ENTRY_USED':
      return entry.entriesChange === 0 ? [details.settledAtBooking] : [];
    case 'SUSPENDED':
    case 'SUSPENSION_CANCELLED': {
      const lines: string[] = [];
      if (entry.suspension) {
        const { startsOn, endsOn } = entry.suspension;
        const describe =
          entry.kind === 'SUSPENDED' ? details.suspended : details.suspensionCancelled;
        lines.push(
          describe(formatDate(startsOn), formatDate(endsOn), daysInclusive(startsOn, endsOn)),
        );
      }
      if (validity) {
        lines.push(validity);
      }
      return lines;
    }
    case 'EXTENDED':
      return validity ? [validity] : [];
    case 'RENEWAL_DECLINED':
      return [details.renewalDeclined];
    case 'MAKEUP_GRANTED': {
      const credit = entry.makeupCredit;
      const lines: string[] = entry.session ? [] : [details.makeupGrantedDirectly];
      if (credit) {
        const expires = formatDateTime(credit.expiresAt, timeZone);
        lines.push(
          credit.state === 'USED'
            ? details.makeupStates.USED
            : credit.state === 'AVAILABLE'
              ? details.makeupStates.AVAILABLE(expires)
              : details.makeupStates.EXPIRED(expires),
        );
      }
      return lines;
    }
    default:
      return [];
  }
}

/** Green for entries given back, red for entries taken, grey for a settlement without a change. */
export function changeColor(delta: number): string {
  return delta > 0 ? 'green' : delta < 0 ? 'red' : 'gray';
}
