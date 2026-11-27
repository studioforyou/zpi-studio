package studioforyou.pl.backend.communication.announcement;

import java.util.List;
import java.util.UUID;

/** An announcement was stored with a notification for each recipient; they are pushed after the commit. */
record AnnouncementSent(UUID announcementId, List<UUID> notificationIds) {

	AnnouncementSent {
		notificationIds = List.copyOf(notificationIds);
	}

}
