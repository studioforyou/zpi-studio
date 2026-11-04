import { Stack, Text } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';

type Props = {
  displayName: string;
  /** The participant's address or, for a child, its guardian's. */
  email: string | null;
  emailOfGuardian: boolean;
};

/**
 * A participant's name with their e-mail address below it, to tell participants with the same
 * name apart. A child shows its guardian's address.
 */
export function CustomerContact({ displayName, email, emailOfGuardian }: Props) {
  return (
    <Stack gap={0}>
      <Text size="sm">{displayName}</Text>
      <Text size="xs" c="dimmed">
        {email === null
          ? pl.customerPasses.noEmail
          : emailOfGuardian
            ? pl.customerPasses.guardianEmail(email)
            : email}
      </Text>
    </Stack>
  );
}
