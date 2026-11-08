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
			new Placeholder(Placeholder.LEAD_TIME, "How long before the start the reminder is sent, e.g. \"2 godz.\"")));

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

	}

}
