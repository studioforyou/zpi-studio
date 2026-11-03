package studioforyou.pl.backend.customer;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * E-mail addresses of login accounts. Accounts belong to the identity module, which already
 * depends on this module, so identity implements this interface instead of the customer module
 * calling identity.
 */
public interface AccountEmails {

	/** E-mail addresses of the given accounts, by account ID; unknown accounts are left out. */
	Map<UUID, String> emailsOf(Collection<UUID> accountIds);

	/**
	 * Accounts whose e-mail address contains the text, ignoring case.
	 *
	 * @param fragment the text to look for, as typed; LIKE wildcards in it match literally
	 */
	Set<UUID> findByEmailContaining(String fragment);

}
