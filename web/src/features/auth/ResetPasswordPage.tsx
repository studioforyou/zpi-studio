import { useState, type FormEvent } from 'react';
import { Alert, Anchor, Button, Card, Center, Stack, Text, TextInput, Title } from '@mantine/core';
import { Link, useLocation } from 'react-router';
import { paths } from '@/app/paths';
import { pl } from '@/shared/i18n/pl';
import { PasswordResetFailure } from './authFailure';
import { authService } from './authService';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Sends the e-mail with a link to set a new password; open without signing in. */
export function ResetPasswordPage() {
  const texts = pl.auth.reset;
  const location = useLocation();
  // The login page passes on the e-mail typed there.
  const [email, setEmail] = useState((location.state as { email?: string } | null)?.email ?? '');
  const [submitted, setSubmitted] = useState(false);
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const emailError = !email.trim()
    ? pl.auth.fieldRequired
    : EMAIL_PATTERN.test(email.trim())
      ? undefined
      : texts.errors.invalidEmail;

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    setError(null);
    if (emailError) {
      return;
    }
    setSending(true);
    try {
      await authService.sendPasswordReset(email);
      setSent(true);
    } catch (failure) {
      setError(texts.errors[failure instanceof PasswordResetFailure ? failure.reason : 'unknown']);
    } finally {
      setSending(false);
    }
  }

  return (
    <Center mih="100vh">
      <Card withBorder shadow="sm" padding="xl" w={400}>
        <form onSubmit={handleSubmit} noValidate>
          <Stack>
            <Title order={2}>{texts.title}</Title>
            {sent ? (
              <Alert color="green" role="status">
                {texts.sent}
              </Alert>
            ) : (
              <Text size="sm" c="dimmed">
                {texts.intro}
              </Text>
            )}
            {error && (
              <Alert color="red" role="alert">
                {error}
              </Alert>
            )}
            <TextInput
              label={pl.auth.email}
              type="email"
              autoComplete="username"
              value={email}
              onChange={(event) => setEmail(event.currentTarget.value)}
              error={submitted ? emailError : undefined}
            />
            <Button type="submit" loading={sending}>
              {sent ? texts.resend : texts.send}
            </Button>
            <Anchor component={Link} to={paths.login} size="sm" ta="center">
              {texts.backToLogin}
            </Anchor>
          </Stack>
        </form>
      </Card>
    </Center>
  );
}
