package studioforyou.pl.backend.identity.history;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.history.AccountHistoryEntry.Person;
import studioforyou.pl.backend.shared.api.ItemsResponse;
import studioforyou.pl.backend.shared.api.PageResponse;

/**
 * Who changed the status, roles and permissions of accounts, and when. Only for accounts with the
 * {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/v1/account-history")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "accounts")
public class AccountHistoryController {

	private final AccountHistory history;

	AccountHistoryController(AccountHistory history) {
		this.history = history;
	}

	@GetMapping
	@Operation(summary = "Search the history of account statuses, roles and permissions",
			operationId = "searchAccountHistory",
			description = "Creations, activations, suspensions, reactivations and closures of accounts, granted "
					+ "and revoked roles, and added and removed permission overrides, newest first, with who "
					+ "made each change. Without filters, the changes of every account.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: e.g. to is before from.")
	public PageResponse<AccountHistoryEntry> search(
			@Parameter(description = "Only changes of this account.")
			@RequestParam(required = false) @Nullable UUID accountId,
			@Parameter(description = "Only changes made by this account.")
			@RequestParam(required = false) @Nullable UUID actorId,
			@Parameter(description = "Only entries of these types; repeat the parameter for several.")
			@RequestParam(name = "type", required = false) @Nullable Set<AccountHistoryType> types,
			@Parameter(description = "Part of the changed account's first and/or last name or e-mail.")
			@RequestParam(required = false) @Nullable @Size(max = 200) String query,
			@Parameter(description = "Only changes on or after this date in the studio's time zone.",
					example = "2026-10-01")
			@RequestParam(required = false) @Nullable @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@Parameter(description = "Only changes on or before this date in the studio's time zone.",
					example = "2026-10-31")
			@RequestParam(required = false) @Nullable @DateTimeFormat(iso = ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return history.search(new AccountHistoryFilter(accountId, actorId, types, query, from, to), page, size);
	}

	@GetMapping("/actors")
	@Operation(summary = "List the people who changed accounts", operationId = "listAccountHistoryActors",
			description = "Everyone who changed the status, roles or permissions of an account, sorted by name; "
					+ "for the actor filter of the history.")
	public ItemsResponse<Person> actors() {
		return new ItemsResponse<>(history.actors());
	}

}
