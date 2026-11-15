import { useState } from 'react';
import { pl } from '@/shared/i18n/pl';
import { AccountHistoryResults } from './AccountHistoryResults';

/** The account's tab with its status, role and permission changes, newest first. */
export function AccountHistoryPanel({ accountId }: { accountId: string }) {
  const [page, setPage] = useState(0);
  return (
    <AccountHistoryResults
      search={{ accountId, page }}
      onPageChange={setPage}
      showAccount={false}
      emptyText={pl.accounts.history.emptyAccount}
    />
  );
}
