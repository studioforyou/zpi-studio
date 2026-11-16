package studioforyou.pl.backend.identity.history;

/**
 * What an entry of the account history records.
 */
public enum AccountHistoryType {

	/**
	 * The account's status changed: created ({@code fromStatus} is {@code null}), activated, suspended,
	 * reactivated or closed.
	 */
	STATUS_CHANGED,

	ROLE_GRANTED,

	ROLE_REVOKED,

	/** A permission was given to ({@code GRANT}) or taken from ({@code REVOKE}) the account on top of its roles. */
	PERMISSION_OVERRIDE_ADDED,

	/** The account went back to its roles' default for a permission; the effect is the removed override's. */
	PERMISSION_OVERRIDE_REMOVED

}
