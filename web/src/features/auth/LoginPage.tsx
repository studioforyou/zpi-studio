import { useState, type FormEvent } from 'react';
import { Button, Card, Center, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { Navigate, useLocation, type Location } from 'react-router';
import { paths } from '@/app/paths';
import { pl } from '@/shared/i18n/pl';
import { useAuth } from './AuthProvider';

export function LoginPage() {
  const { isAuthenticated, login } = useAuth();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitted, setSubmitted] = useState(false);

  // Covers both a successful login and opening /login while signed in.
  if (isAuthenticated) {
    const from = (location.state as { from?: Location } | null)?.from;
    return <Navigate to={from?.pathname ?? paths.schedule} replace />;
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (!email.trim() || !password.trim()) {
      return;
    }
    await login(email, password);
  }

  return (
    <Center mih="100vh">
      <Card withBorder shadow="sm" padding="xl" w={400}>
        <form onSubmit={handleSubmit} noValidate>
          <Stack>
            <Title order={2}>{pl.auth.loginTitle}</Title>
            <TextInput
              label={pl.auth.email}
              type="email"
              value={email}
              onChange={(event) => setEmail(event.currentTarget.value)}
              error={submitted && !email.trim() ? pl.auth.fieldRequired : undefined}
            />
            <PasswordInput
              label={pl.auth.password}
              value={password}
              onChange={(event) => setPassword(event.currentTarget.value)}
              error={submitted && !password.trim() ? pl.auth.fieldRequired : undefined}
            />
            <Button type="submit">{pl.auth.submit}</Button>
            <Text size="sm" c="dimmed">
              {pl.auth.devNotice}
            </Text>
          </Stack>
        </form>
      </Card>
    </Center>
  );
}
