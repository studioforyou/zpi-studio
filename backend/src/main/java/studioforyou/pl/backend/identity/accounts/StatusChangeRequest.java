package studioforyou.pl.backend.identity.accounts;

import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * @param reason why the administrator changes the account's status, kept in its status history
 */
public record StatusChangeRequest(@Nullable @Size(max = 500) String reason) {
}
