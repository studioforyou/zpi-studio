package studioforyou.pl.backend.identity.access;

/**
 * What a permission override does to the permissions an account gets from its roles
 * ({@code account_permission_override.effect}).
 */
public enum OverrideEffect {

	/** The account has the permission even if none of its roles gives it. */
	GRANT,

	/** The account does not have the permission even if one of its roles gives it. */
	REVOKE

}
