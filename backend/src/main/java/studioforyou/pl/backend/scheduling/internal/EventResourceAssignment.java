package studioforyou.pl.backend.scheduling.internal;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A room used by an event ({@code event_resource}).
 *
 * @param blocksWholeRoom {@code true} when no other event may use the room at the same time;
 * {@code false} when it can be shared with other events that do not block it either
 */
@Embeddable
record EventResourceAssignment(@Column(name = "resource_id") UUID resourceId,
		@Column(name = "blocks_whole_room") boolean blocksWholeRoom) {
}
