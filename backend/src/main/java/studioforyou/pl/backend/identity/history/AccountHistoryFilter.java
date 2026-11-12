package studioforyou.pl.backend.identity.history;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Which entries of the account history to show; a {@code null} or empty value does not filter.
 *
 * @param accountId only changes of this account
 * @param actorId only changes made by this account
 * @param types only entries of these types
 * @param query part of the changed account's name (in any order) or e-mail
 * @param from only changes on or after this studio date
 * @param to only changes on or before this studio date
 */
record AccountHistoryFilter(@Nullable UUID accountId, @Nullable UUID actorId, Set<AccountHistoryType> types,
		@Nullable String query, @Nullable LocalDate from, @Nullable LocalDate to) {

	AccountHistoryFilter {
		types = (types == null) ? Set.of() : Set.copyOf(types);
	}

}
