package studioforyou.pl.backend.identity;

/**
 * Lifecycle of a login account ({@code account.status}).
 */
public enum AccountStatus {

	/** Created by the studio, waiting for the first login. */
	INVITED,

	/** Self-registration waiting for approval by the studio. */
	PENDING_APPROVAL,

	/** Approved; the only status with access to the studio's features. */
	ACTIVE,

	/** Registration rejected by the studio. */
	REJECTED,

	/** Access suspended by the studio. */
	SUSPENDED,

	/** Account closed. */
	CLOSED

}
