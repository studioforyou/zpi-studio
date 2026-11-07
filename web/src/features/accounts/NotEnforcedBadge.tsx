import { Badge, Tooltip } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';

/** Marks a permission that no feature checks yet. */
export function NotEnforcedBadge() {
  return (
    <Tooltip label={pl.accounts.access.notEnforcedHint} multiline w={260}>
      <Badge size="xs" variant="light" color="gray">
        {pl.accounts.access.notEnforced}
      </Badge>
    </Tooltip>
  );
}
