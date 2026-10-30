import { useState, type FormEvent } from 'react';
import {
  Alert,
  Anchor,
  Button,
  Card,
  Center,
  PasswordInput,
  Stack,
  TextInput,
  Title,
} from '@mantine/core';
import { Link, Navigate, useLocation, type Location } from 'react-router';
import { paths } from '@/app/paths';
import { pl } from '@/shared/i18n/pl';
import { FullPageLoader } from '@/shared/ui/FullPageLoader';
import { AuthFailure } from './authFailure';
import { useAuth } from './AuthProvider';

export function LoginPage() {
  const { isAuthenticated, isRestoring, login } = useAuth();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (isRestoring) {
    return <FullPageLoader />;
  }

  // Covers both a successful login and opening /login while signed in.
  if (isAuthenticated) {
    const from = (location.state as { from?: Location } | null)?.from;
    return <Navigate to={from?.pathname ?? paths.schedule} replace />;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    setError(null);
    if (!email.trim() || !password.trim()) {
      return;
    }
    setSubmitting(true);
    try {
      await login(email, password);
    } catch (failure) {
      setError(pl.auth.errors[failure instanceof AuthFailure ? failure.reason : 'unknown']);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Center mih="100vh">
      <Card withBorder shadow="sm" padding="xl" w={400}>
        <form onSubmit={handleSubmit} noValidate>
          <Stack>
            <Title order={2}>{pl.auth.loginTitle}</Title>
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
              error={submitted && !email.trim() ? pl.auth.fieldRequired : undefined}
            />
            <PasswordInput
              label={pl.auth.password}
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.currentTarget.value)}
              error={submitted && !password.trim() ? pl.auth.fieldRequired : undefined}
            />
            <Button type="submit" loading={submitting}>
              {pl.auth.submit}
            </Button>
            <Anchor
              component={Link}
              to={paths.resetPassword}
              state={{ email: email.trim() }}
              size="sm"
              ta="center"
            >
              {pl.auth.forgotPassword}
            </Anchor>
          </Stack>
        </form>
      </Card>
    </Center>
  );
}
