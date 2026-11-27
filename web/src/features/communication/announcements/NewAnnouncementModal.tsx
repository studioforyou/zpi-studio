import { useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Group,
  Modal,
  Paper,
  SegmentedControl,
  Select,
  Stack,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { accountKeys, searchAccounts } from '@/features/accounts/accountsApi';
import {
  listScheduleEvents,
  listTrainingGroups,
  scheduleKeys,
} from '@/features/schedule/scheduleApi';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { formatEventWhen } from './announcementFormat';
import {
  BODY_MAX,
  TITLE_MAX,
  announcementKeys,
  audiences,
  hasTarget,
  previewAnnouncement,
  sendAnnouncement,
  type Audience,
  type AudienceInput,
} from './announcementsApi';
import {
  announcementTemplateKeys,
  listAnnouncementTemplates,
  type AnnouncementTemplate,
} from './announcementTemplatesApi';

const DAY = 24 * 60 * 60 * 1000;

/** A person the announcement is for from the start, e.g. a customer who stopped coming. */
export type AnnouncementRecipient = { accountId: string; label: string };

type Props = {
  opened: boolean;
  onClose: () => void;
  /** Opens the form addressed to this person; the administrator can still change it. */
  recipient?: AnnouncementRecipient | null;
};

/**
 * Writes and sends an announcement, optionally starting from a template; sending to every client
 * asks for confirmation first.
 */
export function NewAnnouncementModal({ opened, onClose, recipient = null }: Props) {
  const texts = pl.communication.announcements.form;
  return (
    <Modal opened={opened} onClose={onClose} title={texts.title} size="lg">
      {/* Mounted only while open, so every new announcement starts empty. */}
      {opened && <AnnouncementForm onClose={onClose} recipient={recipient} />}
    </Modal>
  );
}

function AnnouncementForm({
  onClose,
  recipient,
}: {
  onClose: () => void;
  recipient: AnnouncementRecipient | null;
}) {
  const texts = pl.communication.announcements.form;
  const queryClient = useQueryClient();
  const [audience, setAudience] = useState<Audience>(recipient ? 'PERSON' : 'GROUP');
  const [accountId, setAccountId] = useState<string | null>(recipient?.accountId ?? null);
  const [trainingGroupId, setTrainingGroupId] = useState<string | null>(null);
  const [eventId, setEventId] = useState<string | null>(null);
  const [marketing, setMarketing] = useState(false);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [touched, setTouched] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [templateId, setTemplateId] = useState<string | null>(null);

  /** Fills title, text and kind from the template; the administrator can still change them. */
  function applyTemplate(template: AnnouncementTemplate | undefined) {
    setTemplateId(template?.id ?? null);
    if (!template) return;
    setTitle(template.title);
    setBody(template.body);
    setMarketing(template.marketing);
  }

  const input: AudienceInput = { audience, accountId, trainingGroupId, eventId, marketing };
  const ready = hasTarget(input);
  const preview = useQuery({
    queryKey: announcementKeys.preview(input),
    queryFn: () => previewAnnouncement(input),
    enabled: ready,
  });
  const send = useMutation({
    mutationFn: () => sendAnnouncement({ ...input, title, body }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: announcementKeys.all });
      onClose();
    },
    onError: () => setConfirming(false),
  });

  const titleError = touched && title.trim() === '' ? texts.required : null;
  const bodyError = touched && body.trim() === '' ? texts.required : null;
  const recipients = preview.data?.recipients ?? 0;
  const canSend = ready && preview.isSuccess && recipients > 0 && !send.isPending;

  function submit() {
    setTouched(true);
    if (title.trim() === '' || body.trim() === '' || !canSend) return;
    if (audience === 'ALL') {
      setConfirming(true);
    } else {
      send.mutate();
    }
  }

  if (confirming) {
    return (
      <Stack>
        <Text fw={600} size="lg">
          {texts.confirmTitle}
        </Text>
        <Text>{texts.confirmText(title.trim(), recipients)}</Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={() => setConfirming(false)}>
            {texts.back}
          </Button>
          <Button loading={send.isPending} onClick={() => send.mutate()}>
            {texts.confirmSend(recipients)}
          </Button>
        </Group>
      </Stack>
    );
  }

  return (
    <Stack>
      <Stack gap={6}>
        <Text fw={600} size="sm" id="announcement-audience">
          {texts.audienceLabel}
        </Text>
        <SegmentedControl
          aria-labelledby="announcement-audience"
          fullWidth
          value={audience}
          onChange={(value) => setAudience(value as Audience)}
          data={audiences.map((value) => ({ value, label: texts.audiences[value] }))}
        />
      </Stack>
      {audience === 'PERSON' && (
        <PersonPicker value={accountId} onChange={setAccountId} initial={recipient} />
      )}
      {audience === 'GROUP' && (
        <GroupPicker value={trainingGroupId} onChange={setTrainingGroupId} />
      )}
      {audience === 'EVENT' && <EventPicker value={eventId} onChange={setEventId} />}
      <TemplatePicker value={templateId} onChange={applyTemplate} />
      <TextInput
        label={texts.messageTitle}
        value={title}
        maxLength={TITLE_MAX}
        onChange={(event) => setTitle(event.currentTarget.value)}
        error={titleError}
      />
      <Stack gap={4}>
        <Textarea
          label={texts.body}
          value={body}
          rows={5}
          maxLength={BODY_MAX}
          onChange={(event) => setBody(event.currentTarget.value)}
          error={bodyError}
        />
        <Text size="xs" c="dimmed" ta="right">
          {texts.counter(body.length, BODY_MAX)}
        </Text>
      </Stack>
      <Checkbox
        checked={marketing}
        onChange={(event) => setMarketing(event.currentTarget.checked)}
        label={texts.marketing}
        description={texts.marketingHint}
      />
      <Paper withBorder p="sm" bg="gray.0" aria-live="polite">
        <Text size="sm">
          {!ready && audience !== 'ALL'
            ? texts.pickTarget[audience]
            : preview.isPending
              ? texts.counting
              : preview.isError
                ? texts.previewError
                : recipients === 0
                  ? texts.nobody
                  : summary(preview.data!)}
        </Text>
      </Paper>
      {send.isError && <Alert color="red">{sendError(send.error)}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {texts.cancel}
        </Button>
        <Button onClick={submit} disabled={!canSend} loading={send.isPending}>
          {texts.send}
        </Button>
      </Group>
    </Stack>
  );
}

function summary(preview: {
  recipients: number;
  guardians: number;
  skippedWithoutConsent: number;
}) {
  const texts = pl.communication.announcements.form;
  let text = texts.reaches(preview.recipients);
  if (preview.guardians > 0) text += texts.guardians(preview.guardians);
  text += '.';
  if (preview.skippedWithoutConsent > 0) text += ` ${texts.skipped(preview.skippedWithoutConsent)}`;
  return text;
}

function sendError(error: unknown): string {
  const errors = pl.communication.announcements.form.errors;
  if (error instanceof ApiError && error.code) {
    if (error.code === 'ANNOUNCEMENT_NO_RECIPIENTS') return errors.ANNOUNCEMENT_NO_RECIPIENTS;
    if (error.code === 'VALIDATION_FAILED') return errors.VALIDATION_FAILED;
  }
  return errors.generic;
}

type PickerProps = { value: string | null; onChange: (value: string | null) => void };

/** Shown only when the studio has templates. */
function TemplatePicker({
  value,
  onChange,
}: {
  value: string | null;
  onChange: (template: AnnouncementTemplate | undefined) => void;
}) {
  const texts = pl.communication.announcements.form;
  const templates = useQuery({
    queryKey: announcementTemplateKeys.all,
    queryFn: listAnnouncementTemplates,
  });
  if (!templates.data || templates.data.length === 0) return null;
  return (
    <Select
      label={texts.template}
      description={texts.templateHint}
      placeholder={texts.templatePlaceholder}
      searchable
      clearable
      data={templates.data.map((template) => ({ value: template.id, label: template.name }))}
      value={value}
      nothingFoundMessage={texts.nothingFound}
      onChange={(id) => onChange(templates.data.find((template) => template.id === id))}
    />
  );
}

function PersonPicker({
  value,
  onChange,
  initial = null,
}: PickerProps & { initial?: AnnouncementRecipient | null }) {
  const texts = pl.communication.announcements.form;
  const [chosen, setChosen] = useState<{ value: string; label: string } | null>(
    initial && initial.accountId === value
      ? { value: initial.accountId, label: initial.label }
      : null,
  );
  // A preselected person shows their name in the field, as if the administrator had picked them.
  const [search, setSearch] = useState(chosen?.label ?? '');
  const [debounced] = useDebouncedValue(search, 300);
  // The chosen person's label is not a search the accounts list understands.
  const query = chosen !== null && debounced === chosen.label ? '' : debounced;
  const people = useQuery({
    queryKey: accountKeys.list({ query, role: null, status: 'ACTIVE', page: 0 }),
    queryFn: () => searchAccounts({ query, role: null, status: 'ACTIVE', page: 0 }),
    placeholderData: keepPreviousData,
  });
  const options = useMemo(() => {
    const found = (people.data?.items ?? [])
      .filter((person) => person.roles.includes('CLIENT') || person.roles.includes('TRAINER'))
      .map((person) => ({
        value: person.id,
        label: `${person.displayName} · ${[
          person.roles.includes('CLIENT') ? texts.roleClient : null,
          person.roles.includes('TRAINER') ? texts.roleTrainer : null,
        ]
          .filter(Boolean)
          .join(', ')}`,
      }));
    // The chosen person stays an option even when the current search no longer matches them.
    return chosen && !found.some((option) => option.value === chosen.value)
      ? [chosen, ...found]
      : found;
  }, [people.data, chosen, texts.roleClient, texts.roleTrainer]);

  return (
    <Select
      label={texts.person}
      placeholder={texts.personPlaceholder}
      searchable
      searchValue={search}
      onSearchChange={setSearch}
      filter={({ options }) => options}
      data={options}
      value={value}
      nothingFoundMessage={texts.nothingFound}
      onChange={(next, option) => {
        setChosen(option ?? null);
        onChange(next);
      }}
    />
  );
}

function GroupPicker({ value, onChange }: PickerProps) {
  const texts = pl.communication.announcements.form;
  const groups = useQuery({ queryKey: scheduleKeys.trainingGroups, queryFn: listTrainingGroups });
  return (
    <Select
      label={texts.group}
      placeholder={texts.groupPlaceholder}
      searchable
      data={(groups.data ?? [])
        .filter((group) => group.isActive)
        .map((group) => ({ value: group.id, label: group.name }))}
      value={value}
      nothingFoundMessage={texts.nothingFound}
      onChange={onChange}
    />
  );
}

function EventPicker({ value, onChange }: PickerProps) {
  const texts = pl.communication.announcements.form;
  // Rounded to the minute so the query key stays the same while the form is open.
  const [from] = useState(() => new Date(Math.floor(Date.now() / 60000) * 60000));
  const events = useQuery({
    queryKey: ['announcements', 'events', from.toISOString()],
    queryFn: () =>
      listScheduleEvents(from.toISOString(), new Date(from.getTime() + 30 * DAY).toISOString()),
  });
  const options = (events.data ?? [])
    .filter(
      (event) =>
        (event.kind === 'CLASS' || event.kind === 'WORKSHOP') &&
        event.status !== 'CANCELLED' &&
        new Date(event.endsAt) > from,
    )
    .map((event) => ({
      value: event.id,
      label: `${event.title ?? event.serviceTypeName ?? '—'} · ${formatEventWhen(event.startsAt)}`,
    }));
  return (
    <Select
      label={texts.event}
      placeholder={texts.eventPlaceholder}
      searchable
      data={options}
      value={value}
      nothingFoundMessage={texts.nothingFound}
      onChange={onChange}
      limit={50}
    />
  );
}
