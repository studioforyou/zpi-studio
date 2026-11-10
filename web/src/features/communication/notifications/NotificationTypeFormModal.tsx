import { useRef, useState, type FormEvent } from 'react';
import {
  ActionIcon,
  Alert,
  Button,
  Group,
  Modal,
  NumberInput,
  Paper,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
  Tooltip,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import {
  formatLeadTime,
  leadTimeUnits,
  MAX_LEAD_MINUTES,
  MAX_LEAD_TIMES,
  MIN_LEAD_MINUTES,
  toLeadTimeValue,
  toMinutes,
  type LeadTimeUnit,
  type LeadTimeValue,
} from './leadTime';
import {
  notificationTypeKeys,
  updateNotificationType,
  type NotificationTypeSettings,
  type NotificationTypeUpdate,
} from './notificationTypesApi';
import { placeholderTexts, typeTexts } from './typeTexts';

type Props = { opened: boolean; onClose: () => void; type: NotificationTypeSettings };
type Errors = Partial<Record<'leadTimes' | 'title' | 'body', string>>;
type TextField = 'title' | 'body';

const TITLE_MAX = 150;
const BODY_MAX = 500;
// Same rule as the backend: Polish words, single words or several separated by spaces.
const PLACEHOLDER = /\{\{\s*(\p{L}[\p{L}\p{N}]*(?:\s+[\p{L}\p{N}]+)*)\s*\}\}/gu;
const LEAD_TIME_PLACEHOLDER = 'ile przed';

function placeholderName(raw: string): string {
  return raw.replace(/\s+/g, ' ');
}

/** The template with known placeholders replaced by examples; unknown ones stay as typed. */
function renderPreview(template: string, examples: Map<string, string>): string {
  return template.replace(PLACEHOLDER, (match, raw: string) => {
    return examples.get(placeholderName(raw)) ?? match;
  });
}

export function NotificationTypeFormModal({ opened, onClose, type }: Props) {
  const texts = pl.communication.notifications.form;
  const queryClient = useQueryClient();
  const [leadTimes, setLeadTimes] = useState<LeadTimeValue[]>(
    type.leadTimesMinutes.map(toLeadTimeValue),
  );
  const [title, setTitle] = useState(type.title);
  const [body, setBody] = useState(type.body);
  const [errors, setErrors] = useState<Errors>({});
  const [lastField, setLastField] = useState<TextField>('body');
  const titleRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const known = new Set(type.placeholders.map((placeholder) => placeholder.name));
  const placeholders = type.placeholders.map((placeholder) => ({
    name: placeholder.name,
    ...placeholderTexts(type.code, placeholder.name, placeholder.description),
  }));
  // The preview shows the shortest reminder time the administrator has set, e.g. "2 godz.".
  const validMinutes = leadTimes
    .map(toMinutes)
    .filter((m): m is number => m !== null && m >= MIN_LEAD_MINUTES && m <= MAX_LEAD_MINUTES);
  const examples = new Map(
    placeholders.map((placeholder) => [
      placeholder.name,
      placeholder.name === LEAD_TIME_PLACEHOLDER && validMinutes.length > 0
        ? formatLeadTime(Math.min(...validMinutes))
        : placeholder.example,
    ]),
  );

  /** Inserts {{name}} where the cursor was in the title or body the administrator used last. */
  function insertPlaceholder(name: string) {
    const token = `{{${name}}}`;
    const field = lastField;
    const element = field === 'title' ? titleRef.current : bodyRef.current;
    const value = field === 'title' ? title : body;
    const max = field === 'title' ? TITLE_MAX : BODY_MAX;
    if (value.length + token.length > max) return;
    const start = element?.selectionStart ?? value.length;
    const end = element?.selectionEnd ?? start;
    const next = value.slice(0, start) + token + value.slice(end);
    if (field === 'title') setTitle(next);
    else setBody(next);
    const caret = start + token.length;
    requestAnimationFrame(() => {
      element?.focus();
      element?.setSelectionRange(caret, caret);
    });
  }

  const save = useMutation({
    mutationFn: (update: NotificationTypeUpdate) => updateNotificationType(type.code, update),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: notificationTypeKeys.all });
      onClose();
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'VALIDATION_FAILED') {
        const next: Errors = {};
        for (const issue of error.problem?.errors ?? []) {
          const field = issue.field.startsWith('leadTimesMinutes') ? 'leadTimes' : issue.field;
          if (field === 'leadTimes' || field === 'title' || field === 'body') {
            next[field] = texts.invalidValue;
          }
        }
        setErrors(next);
      }
    },
  });

  function textError(value: string, max: number): string | undefined {
    if (!value.trim()) return texts.required;
    if (value.trim().length > max) return texts.tooLong(max);
    const unknown = [...value.matchAll(PLACEHOLDER)]
      .map((match) => placeholderName(match[1]))
      .filter((name) => !known.has(name));
    if (unknown.length > 0) return texts.unknownPlaceholders(unknown.map((n) => `{{${n}}}`));
    return undefined;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (save.isPending) return;
    const next: Errors = {};
    const minutes = leadTimes.map(toMinutes);
    if (type.timed) {
      if (leadTimes.length === 0) next.leadTimes = texts.leadTimesRequired;
      else if (minutes.some((m) => m === null)) next.leadTimes = texts.leadTimeInvalid;
      else if (minutes.some((m) => m! < MIN_LEAD_MINUTES || m! > MAX_LEAD_MINUTES))
        next.leadTimes = texts.leadTimeRange;
      else if (new Set(minutes).size !== minutes.length) next.leadTimes = texts.leadTimesDistinct;
    }
    const titleError = textError(title, TITLE_MAX);
    const bodyError = textError(body, BODY_MAX);
    if (titleError) next.title = titleError;
    if (bodyError) next.body = bodyError;
    setErrors(next);
    if (Object.keys(next).length > 0) return;
    save.mutate({
      title: title.trim(),
      body: body.trim(),
      ...(type.timed ? { leadTimesMinutes: (minutes as number[]).toSorted((a, b) => b - a) } : {}),
    });
  }

  function setLeadTime(index: number, change: Partial<LeadTimeValue>) {
    setLeadTimes((current) =>
      current.map((value, i) => (i === index ? { ...value, ...change } : value)),
    );
  }

  const errorMessage =
    save.error instanceof ApiError && save.error.code === NETWORK_ERROR
      ? texts.network
      : texts.saveError;

  return (
    <Modal opened={opened} onClose={onClose} title={typeTexts(type.code).name} size="lg">
      <form onSubmit={submit} noValidate>
        <Stack>
          {save.isError && <Alert color="red">{errorMessage}</Alert>}
          {type.timed && (
            <Stack gap="xs">
              <Text size="sm" fw={500}>
                {texts.leadTimes}
              </Text>
              <Text size="xs" c="dimmed">
                {typeTexts(type.code).leadTimesHint ?? texts.leadTimesHint}
              </Text>
              {leadTimes.map((value, index) => (
                <Group key={index} gap="xs" align="flex-end" wrap="nowrap">
                  <NumberInput
                    aria-label={texts.leadTimeAmount(index + 1)}
                    value={value.amount}
                    min={1}
                    allowDecimal={false}
                    allowNegative={false}
                    onChange={(amount) => setLeadTime(index, { amount })}
                    w={110}
                  />
                  <Select
                    aria-label={texts.leadTimeUnit(index + 1)}
                    data={leadTimeUnits.map((unit) => ({ value: unit, label: texts.units[unit] }))}
                    value={value.unit}
                    allowDeselect={false}
                    onChange={(unit) => unit && setLeadTime(index, { unit: unit as LeadTimeUnit })}
                    w={130}
                  />
                  <Text size="sm" pb={8}>
                    {typeTexts(type.code).leadTimeAnchor ?? texts.beforeStart}
                  </Text>
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    aria-label={texts.removeLeadTime(index + 1)}
                    disabled={leadTimes.length === 1}
                    onClick={() => setLeadTimes((current) => current.filter((_, i) => i !== index))}
                    mb={4}
                  >
                    ✕
                  </ActionIcon>
                </Group>
              ))}
              {errors.leadTimes && (
                <Text size="sm" c="red">
                  {errors.leadTimes}
                </Text>
              )}
              <Group>
                <Button
                  variant="subtle"
                  size="compact-sm"
                  disabled={leadTimes.length >= MAX_LEAD_TIMES}
                  onClick={() =>
                    setLeadTimes((current) => [...current, { amount: 1, unit: 'HOURS' }])
                  }
                >
                  {texts.addLeadTime}
                </Button>
              </Group>
            </Stack>
          )}
          <TextInput
            ref={titleRef}
            label={texts.title}
            required
            value={title}
            onFocus={() => setLastField('title')}
            maxLength={TITLE_MAX}
            onChange={(event) => setTitle(event.currentTarget.value)}
            error={errors.title}
          />
          <Textarea
            ref={bodyRef}
            label={texts.body}
            required
            minRows={3}
            value={body}
            onFocus={() => setLastField('body')}
            maxLength={BODY_MAX}
            onChange={(event) => setBody(event.currentTarget.value)}
            error={errors.body}
          />
          <Stack gap={4}>
            <Text size="sm" fw={500}>
              {texts.placeholders}
            </Text>
            <Text size="xs" c="dimmed">
              {texts.placeholdersHint}
            </Text>
            <Group gap="xs">
              {placeholders.map((placeholder) => (
                <Tooltip key={placeholder.name} label={placeholder.description} multiline w={260}>
                  <Button
                    variant="light"
                    size="compact-sm"
                    aria-label={texts.insertPlaceholder(placeholder.label)}
                    onClick={() => insertPlaceholder(placeholder.name)}
                  >
                    + {placeholder.label}
                  </Button>
                </Tooltip>
              ))}
            </Group>
          </Stack>
          <Stack gap={4}>
            <Text size="sm" fw={500}>
              {texts.preview}
            </Text>
            <Text size="xs" c="dimmed">
              {texts.previewHint}
            </Text>
            <Paper withBorder radius="md" p="sm" bg="gray.0" aria-label={texts.preview}>
              <Text size="sm" fw={600}>
                {renderPreview(title, examples)}
              </Text>
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                {renderPreview(body, examples)}
              </Text>
            </Paper>
          </Stack>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {texts.cancel}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {texts.save}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
