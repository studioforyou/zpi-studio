import { useState, type FormEvent } from 'react';
import { Alert, Button, Group, Modal, PasswordInput, Stack } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { PasswordChangeFailure, type PasswordChangeFailureReason } from './authFailure';
import { authService } from './authService';

// Same limits as passwords set when accepting an invitation.
const MIN_LENGTH = 8;
const MAX_LENGTH = 128;

type ChangePasswordModalProps = {
  opened: boolean;
  onClose: () => void;
};

export function ChangePasswordModal({ opened, onClose }: ChangePasswordModalProps) {
  const texts = pl.auth.changePassword;
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(false);
  const [failure, setFailure] = useState<PasswordChangeFailureReason | null>(null);

  function handleClose() {
    setCurrent('');
    setNext('');
    setRepeat('');
    setSubmitted(false);
    setDone(false);
    setFailure(null);
    onClose();
  }

  const errors = {
    current: current ? undefined : pl.auth.fieldRequired,
    next:
      next.length < MIN_LENGTH
        ? texts.tooShort(MIN_LENGTH)
        : next.length > MAX_LENGTH
          ? texts.tooLong(MAX_LENGTH)
          : next === current
            ? texts.sameAsCurrent
            : undefined,
    repeat: repeat === next ? undefined : texts.mismatch,
  };

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    setFailure(null);
    if (errors.current || errors.next || errors.repeat) {
      return;
    }
    setSaving(true);
    try {
      await authService.changePassword(current, next);
      setDone(true);
    } catch (error) {
      setFailure(error instanceof PasswordChangeFailure ? error.reason : 'unknown');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal opened={opened} onClose={handleClose} title={texts.title} size="sm" centered>
      {done ? (
        <Stack>
          <Alert color="green" role="status">
            {texts.done}
          </Alert>
          <Group justify="flex-end">
            <Button onClick={handleClose}>{texts.close}</Button>
          </Group>
        </Stack>
      ) : (
        <form onSubmit={handleSubmit} noValidate>
          <Stack>
            {failure && failure !== 'wrongPassword' && (
              <Alert color="red" role="alert">
                {texts.errors[failure]}
              </Alert>
            )}
            <PasswordInput
              label={texts.current}
              autoComplete="current-password"
              value={current}
              onChange={(event) => {
                setCurrent(event.currentTarget.value);
                setFailure(null);
              }}
              error={
                failure === 'wrongPassword'
                  ? texts.errors.wrongPassword
                  : submitted
                    ? errors.current
                    : undefined
              }
            />
            <PasswordInput
              label={texts.new}
              autoComplete="new-password"
              value={next}
              onChange={(event) => setNext(event.currentTarget.value)}
              error={submitted ? errors.next : undefined}
            />
            <PasswordInput
              label={texts.repeat}
              autoComplete="new-password"
              value={repeat}
              onChange={(event) => setRepeat(event.currentTarget.value)}
              error={submitted ? errors.repeat : undefined}
            />
            <Group justify="flex-end">
              <Button variant="default" onClick={handleClose}>
                {texts.cancel}
              </Button>
              <Button type="submit" loading={saving}>
                {texts.submit}
              </Button>
            </Group>
          </Stack>
        </form>
      )}
    </Modal>
  );
}
