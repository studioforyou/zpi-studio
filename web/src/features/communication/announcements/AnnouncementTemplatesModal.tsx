import { useState, type FormEvent } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Modal,
  SegmentedControl,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { BODY_MAX, TITLE_MAX } from './announcementsApi';
import {
  TEMPLATE_NAME_MAX,
  announcementTemplateKeys,
  createAnnouncementTemplate,
  deleteAnnouncementTemplate,
  listAnnouncementTemplates,
  updateAnnouncementTemplate,
  type AnnouncementTemplate,
} from './announcementTemplatesApi';

type Props = { opened: boolean; onClose: () => void };

type View =
  | { kind: 'list' }
  | { kind: 'form'; template: AnnouncementTemplate | null }
  | { kind: 'delete'; template: AnnouncementTemplate };

type Kind = 'STUDIO' | 'OFFER';

/** The administrator's announcement templates: list, add, edit and delete. */
export function AnnouncementTemplatesModal({ opened, onClose }: Props) {
  const texts = pl.communication.announcements.templates;
  return (
    <Modal opened={opened} onClose={onClose} title={texts.title} size="xl">
      {/* Mounted only while open, so it always starts on the list. */}
      {opened && <TemplatesContent onClose={onClose} />}
    </Modal>
  );
}

function TemplatesContent({ onClose }: { onClose: () => void }) {
  const [view, setView] = useState<View>({ kind: 'list' });
  const back = () => setView({ kind: 'list' });
  switch (view.kind) {
    case 'form':
      return <TemplateForm template={view.template} onDone={back} />;
    case 'delete':
      return <DeleteTemplate template={view.template} onDone={back} />;
    case 'list':
      return (
        <TemplateList
          onClose={onClose}
          onCreate={() => setView({ kind: 'form', template: null })}
          onEdit={(template) => setView({ kind: 'form', template })}
          onDelete={(template) => setView({ kind: 'delete', template })}
        />
      );
  }
}

/** "Komunikat studia" or "Oferta", as the mobile app labels the announcement. */
export function TemplateKindBadge({ marketing }: { marketing: boolean }) {
  const kinds = pl.communication.announcements.templates.kinds;
  return (
    <Badge color={marketing ? 'orange' : 'gray'} variant="light">
      {marketing ? kinds.OFFER : kinds.STUDIO}
    </Badge>
  );
}

type ListProps = {
  onClose: () => void;
  onCreate: () => void;
  onEdit: (template: AnnouncementTemplate) => void;
  onDelete: (template: AnnouncementTemplate) => void;
};

function TemplateList({ onClose, onCreate, onEdit, onDelete }: ListProps) {
  const texts = pl.communication.announcements.templates;
  const templates = useQuery({
    queryKey: announcementTemplateKeys.all,
    queryFn: listAnnouncementTemplates,
  });

  return (
    <Stack>
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <Text size="sm" c="dimmed">
          {texts.intro}
        </Text>
        <Button onClick={onCreate} style={{ flexShrink: 0 }}>
          {texts.newTemplate}
        </Button>
      </Group>
      {templates.isPending ? (
        <Text>{texts.loading}</Text>
      ) : templates.isError ? (
        <Alert color="red" title={texts.loadError}>
          <Button variant="subtle" onClick={() => void templates.refetch()}>
            {texts.retry}
          </Button>
        </Alert>
      ) : templates.data.length === 0 ? (
        <Text c="dimmed" ta="center" p="lg">
          {texts.empty}
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{texts.columns.name}</Table.Th>
              <Table.Th>{texts.columns.title}</Table.Th>
              <Table.Th>{texts.columns.kind}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {templates.data.map((template) => (
              <Table.Tr key={template.id}>
                <Table.Td>
                  <Text fw={600} size="sm">
                    {template.name}
                  </Text>
                </Table.Td>
                <Table.Td>{template.title}</Table.Td>
                <Table.Td>
                  <TemplateKindBadge marketing={template.marketing} />
                </Table.Td>
                <Table.Td>
                  <Group gap="xs" justify="flex-end" wrap="nowrap">
                    <Button
                      variant="subtle"
                      size="compact-sm"
                      aria-label={`${texts.edit}: ${template.name}`}
                      onClick={() => onEdit(template)}
                    >
                      {texts.edit}
                    </Button>
                    <Button
                      variant="subtle"
                      color="red"
                      size="compact-sm"
                      aria-label={`${texts.remove}: ${template.name}`}
                      onClick={() => onDelete(template)}
                    >
                      {texts.remove}
                    </Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {texts.close}
        </Button>
      </Group>
    </Stack>
  );
}

type Errors = Partial<Record<'name' | 'title' | 'body', string>>;

function TemplateForm({
  template,
  onDone,
}: {
  template: AnnouncementTemplate | null;
  onDone: () => void;
}) {
  const texts = pl.communication.announcements.templates;
  const queryClient = useQueryClient();
  const [name, setName] = useState(template?.name ?? '');
  const [title, setTitle] = useState(template?.title ?? '');
  const [body, setBody] = useState(template?.body ?? '');
  const [kind, setKind] = useState<Kind>(template?.marketing ? 'OFFER' : 'STUDIO');
  const [errors, setErrors] = useState<Errors>({});

  const save = useMutation({
    mutationFn: () => {
      const input = { name, title, body, marketing: kind === 'OFFER' };
      return template
        ? updateAnnouncementTemplate(template.id, input)
        : createAnnouncementTemplate(input);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: announcementTemplateKeys.all });
      onDone();
    },
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'ANNOUNCEMENT_TEMPLATE_NAME_ALREADY_USED') {
        setErrors({ name: texts.nameTaken });
      }
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (save.isPending) return;
    const next: Errors = {};
    if (!name.trim()) next.name = texts.required;
    if (!title.trim()) next.title = texts.required;
    if (!body.trim()) next.body = texts.required;
    setErrors(next);
    if (Object.keys(next).length > 0) return;
    save.mutate();
  }

  const nameTaken =
    save.error instanceof ApiError && save.error.code === 'ANNOUNCEMENT_TEMPLATE_NAME_ALREADY_USED';

  return (
    <form onSubmit={submit} noValidate>
      <Stack>
        <Text fw={600} size="lg">
          {template ? texts.editTitle : texts.createTitle}
        </Text>
        {save.isError && !nameTaken && <Alert color="red">{texts.saveError}</Alert>}
        <TextInput
          label={texts.name}
          description={texts.nameHint}
          required
          value={name}
          maxLength={TEMPLATE_NAME_MAX}
          onChange={(event) => setName(event.currentTarget.value)}
          error={errors.name}
        />
        <TextInput
          label={texts.messageTitle}
          required
          value={title}
          maxLength={TITLE_MAX}
          onChange={(event) => setTitle(event.currentTarget.value)}
          error={errors.title}
        />
        <Stack gap={4}>
          <Textarea
            label={texts.body}
            required
            rows={5}
            value={body}
            maxLength={BODY_MAX}
            onChange={(event) => setBody(event.currentTarget.value)}
            error={errors.body}
          />
          <Text size="xs" c="dimmed" ta="right">
            {texts.counter(body.length, BODY_MAX)}
          </Text>
        </Stack>
        <Stack gap={6}>
          <Text fw={500} size="sm" id="template-kind">
            {texts.kind}
          </Text>
          <SegmentedControl
            aria-labelledby="template-kind"
            value={kind}
            onChange={(value) => setKind(value as Kind)}
            data={[
              { value: 'STUDIO', label: texts.kinds.STUDIO },
              { value: 'OFFER', label: texts.kinds.OFFER },
            ]}
          />
          {kind === 'OFFER' && (
            <Text size="xs" c="dimmed">
              {texts.offerHint}
            </Text>
          )}
        </Stack>
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>
            {texts.cancel}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {texts.save}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function DeleteTemplate({
  template,
  onDone,
}: {
  template: AnnouncementTemplate;
  onDone: () => void;
}) {
  const texts = pl.communication.announcements.templates;
  const queryClient = useQueryClient();
  const remove = useMutation({
    mutationFn: () => deleteAnnouncementTemplate(template.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: announcementTemplateKeys.all });
      onDone();
    },
  });

  return (
    <Stack>
      <Text>{texts.confirmDelete(template.name)}</Text>
      {remove.isError && <Alert color="red">{texts.deleteError}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>
          {texts.back}
        </Button>
        <Button color="red" loading={remove.isPending} onClick={() => remove.mutate()}>
          {texts.remove}
        </Button>
      </Group>
    </Stack>
  );
}
