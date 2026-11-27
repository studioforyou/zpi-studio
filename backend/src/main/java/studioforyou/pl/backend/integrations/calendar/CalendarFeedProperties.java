package studioforyou.pl.backend.integrations.calendar;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Calendar feed settings ({@code app.calendar-feeds.*}).
 *
 * @param baseUrl public address of the backend that external calendars download the feed from,
 * without a trailing slash, e.g. {@code https://api.example.com}
 */
@Validated
@ConfigurationProperties("app.calendar-feeds")
record CalendarFeedProperties(@NotBlank String baseUrl) {
}
