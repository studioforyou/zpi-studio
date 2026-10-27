package studioforyou.pl.backend.offering;

import java.util.UUID;

/**
 * Basic information about a service type that other modules may rely on.
 *
 * @param category the service's category, e.g. {@code GROUP_CLASS} or {@code PERSONAL_TRAINING}
 * @param defaultDurationMinutes how long one session usually takes
 * @param maxParticipants the usual maximum number of participants, or {@code null} for no limit
 */
public record ServiceTypeSummary(UUID id, String name, boolean active, String category, int defaultDurationMinutes,
		Integer maxParticipants, String calendarColor) {
}
