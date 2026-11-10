import { pl } from '@/shared/i18n/pl';

type Types = typeof pl.communication.notifications.types;
type Placeholders = typeof pl.communication.notifications.placeholders;

export type TypeTexts = {
  name: string;
  description?: string;
  /** What a timed type's reminder times count back from, when it is not the start. */
  leadTimeAnchor?: string;
  /** The hint under a timed type's reminder times, when it differs from the default. */
  leadTimesHint?: string;
};

/** Polish name and description of a type; a type without texts yet shows its code. */
export function typeTexts(code: string): TypeTexts {
  const types: Types = pl.communication.notifications.types;
  return code in types ? types[code as keyof Types] : { name: code };
}

export type PlaceholderTexts = { label: string; description: string; example: string };

/** Button label, description and preview example of a placeholder; falls back to the backend's. */
export function placeholderTexts(code: string, name: string, fallback: string): PlaceholderTexts {
  const all: Placeholders = pl.communication.notifications.placeholders;
  const forType =
    code in all ? (all[code as keyof Placeholders] as Record<string, PlaceholderTexts>) : {};
  return forType[name] ?? { label: name, description: fallback, example: name };
}
