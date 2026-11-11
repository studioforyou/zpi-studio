package studioforyou.pl.backend.communication.notification;

import java.util.List;
import java.util.Optional;

/**
 * Notification types ({@code notification_type.code}). Every type is a row loaded by a migration
 * and is configured by the studio administrator: on/off, content and, for a timed type, how long
 * before the start it is sent.
 *
 * @param id the {@code notification_type.id}
 * @param timed whether the type is sent some time before a start ({@code notification_lead_time})
 * rather than right after an event
 * @param placeholders the {@code {{name}}} values its template can use
 */
public enum NotificationType {

	/** Reminder about a class or training the participant has an active reservation for. */
	CLASS_REMINDER((short) 1, true, List.of(
			new Placeholder(Placeholder.CLASS, "Name of the class or training"),
			new Placeholder(Placeholder.DAY, "Day of the class: \"dziś\", \"jutro\" or the weekday and date, e.g. \"wtorek 12.11\""),
			new Placeholder(Placeholder.TIME, "Start time in the studio time zone, e.g. \"18:00\""),
			new Placeholder(Placeholder.ROOM, "Room names, or \"studio\" when no room is assigned"),
			new Placeholder(Placeholder.LEAD_TIME, "How long before the start the reminder is sent, e.g. \"2 godz.\""))),

	/**
	 * Reminder that a pass is about to expire, sent before 9:00 on its last day. Not sent when the
	 * participant already has a following pass or no entries are left.
	 */
	PASS_EXPIRING((short) 2, true, List.of(
			new Placeholder(Placeholder.PASS, "Name of the pass"),
			new Placeholder(Placeholder.VALID_UNTIL, "Last day of the pass, e.g. \"12.11.2026\""),
			new Placeholder(Placeholder.LEAD_TIME, "How long before 9:00 on the last day the reminder is sent, e.g. \"3 dni\""))),

	/**
	 * A booking used the last entry of a pass. Sent once per pass, and not when the participant
	 * already has a following pass.
	 */
	PASS_LAST_ENTRY_USED((short) 3, false, List.of(
			new Placeholder(Placeholder.PASS, "Name of the pass"),
			new Placeholder(Placeholder.VALID_UNTIL, "Last day of the pass, e.g. \"12.11.2026\"")));

	/**
	 * A new message in a conversation the recipient takes part in, sent right after it is written to
	 * every other participant.
	 */
	NEW_MESSAGE((short) 4, false, List.of(
			new Placeholder(Placeholder.SENDER, "Who wrote the message; in a group or class chat with the chat's name, e.g. \"Pilates · Anna Nowak\""),
			new Placeholder(Placeholder.CONVERSATION, "Name of the conversation: the other person's name, or the group or class chat's name"),
			new Placeholder(Placeholder.TEXT, "The beginning of the message, at most 100 characters")));		

	private final short id;

	private final boolean timed;

	private final List<Placeholder> placeholders;

	NotificationType(short id, boolean timed, List<Placeholder> placeholders) {
		this.id = id;
		this.timed = timed;
		this.placeholders = placeholders;
	}

	public short id() {
		return id;
	}

	public boolean timed() {
		return timed;
	}

	public List<Placeholder> placeholders() {
		return placeholders;
	}

	public static Optional<NotificationType> fromCode(String code) {
		for (NotificationType type : values()) {
			if (type.name().equals(code)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}

	/**
	 * A value a template can use as {@code {{name}}}. Names are Polish because the studio
	 * administrator, not a developer, writes the templates.
	 *
	 * @param name the Polish name used between the braces
	 * @param description what the backend puts there
	 */
	public record Placeholder(String name, String description) {

		/** Name of the class or training. */
		public static final String CLASS = "zajęcia";

		/** Day of the class. */
		public static final String DAY = "dzień";

		/** Start time. */
		public static final String TIME = "godzina";

		/** Room names. */
		public static final String ROOM = "sala";

		/** How long before the start the reminder is sent. */
		public static final String LEAD_TIME = "ile przed";

		/** Name of the pass. */
		public static final String PASS = "karnet";

		/** Last day of the pass. */
		public static final String VALID_UNTIL = "ważny do";

		/** Who wrote the message. */
		public static final String SENDER = "od";

		/** Name of the conversation. */
		public static final String CONVERSATION = "rozmowa";

		/** The beginning of the message. */
		public static final String TEXT = "treść";

	}

}
