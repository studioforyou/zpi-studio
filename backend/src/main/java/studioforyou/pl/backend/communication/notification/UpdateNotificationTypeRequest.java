package studioforyou.pl.backend.communication.notification;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Changes to a notification type's settings; omitted fields stay as they are.
 *
 * @param enabled whether the studio sends it
 * @param leadTimesMinutes for a timed type: 1 to 5 distinct reminder times, 5 minutes to 14 days before the start
 * @param title the push title template, with {@code {{name}}} placeholders of the type
 * @param body the push text template, with {@code {{name}}} placeholders of the type
 */
public record UpdateNotificationTypeRequest(Boolean enabled,
		@Size(min = 1, max = UpdateNotificationTypeRequest.MAX_LEAD_TIMES) List<@NotNull @Min(5) @Max(20160) Integer> leadTimesMinutes,
		@Size(min = 1, max = 150) String title, @Size(min = 1, max = 500) String body) {

	static final int MAX_LEAD_TIMES = 5;

}
