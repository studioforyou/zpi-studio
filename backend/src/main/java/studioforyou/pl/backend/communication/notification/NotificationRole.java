package studioforyou.pl.backend.communication.notification;

/**
 * The role a notification concerns ({@code notification.recipient_role}). An account that is both
 * a client and a trainer sees which one in the notification.
 */
public enum NotificationRole {

	CLIENT("Klient"),

	TRAINER("Trener"),

	ADMIN("Administrator");

	private final String label;

	NotificationRole(String label) {
		this.label = label;
	}

	/** The Polish label shown in front of the title for an account with several roles. */
	public String label() {
		return label;
	}

}
