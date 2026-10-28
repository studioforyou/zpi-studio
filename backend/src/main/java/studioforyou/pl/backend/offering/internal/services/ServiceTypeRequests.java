package studioforyou.pl.backend.offering.internal.services;

import java.util.Optional;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import studioforyou.pl.backend.configuration.BookingPolicies.Values;

/** Service catalogue inputs. Null clears optional values in a partial update. */
final class ServiceTypeRequests {

	private ServiceTypeRequests() {
	}

	@Schema(name = "CreateServiceTypeRequest")
	record CreateServiceTypeRequest(
			@NotBlank @Size(max = 120) String name,
			String description,
			@NotBlank @Pattern(regexp = "GROUP_CLASS|PERSONAL_TRAINING|WORKSHOP") String category,
			@Size(max = 20) String level,
			@Min(0) @Max(Short.MAX_VALUE) Integer minAge,
			@Min(0) @Max(Short.MAX_VALUE) Integer maxAge,
			@NotNull @Min(1) @Max(Short.MAX_VALUE) Integer defaultDurationMinutes,
			@Min(1) @Max(Short.MAX_VALUE) Integer minParticipants,
			@Min(1) @Max(Short.MAX_VALUE) Integer maxParticipants,
			@Pattern(regexp = "#[0-9a-fA-F]{6}") String calendarColor,
			@Valid Values bookingPolicy) {
	}

	/**
	 * Setters distinguish missing optional fields from explicit nulls, as in room updates.
	 * Missing fields and null required fields stay unchanged.
	 */
	@Schema(name = "UpdateServiceTypeRequest", description = "Partial update. Null clears optional fields; omitted fields stay unchanged.")
	static final class UpdateServiceTypeRequest {

		@Size(max = 120)
		@Pattern(regexp = ".*\\S.*")
		private String name;

		private Optional<String> description;

		@Pattern(regexp = "GROUP_CLASS|PERSONAL_TRAINING|WORKSHOP")
		private String category;

		private Optional<@Size(max = 20) String> level;

		private Optional<@Min(0) @Max(Short.MAX_VALUE) Integer> minAge;

		private Optional<@Min(0) @Max(Short.MAX_VALUE) Integer> maxAge;

		@Min(1) @Max(Short.MAX_VALUE)
		private Integer defaultDurationMinutes;

		private Optional<@Min(1) @Max(Short.MAX_VALUE) Integer> minParticipants;

		private Optional<@Min(1) @Max(Short.MAX_VALUE) Integer> maxParticipants;

		private Optional<@Pattern(regexp = "#[0-9a-fA-F]{6}") String> calendarColor;

		private Boolean isActive;

		private Optional<@Valid Values> bookingPolicy;

		public Optional<Values> getBookingPolicy() {
			return this.bookingPolicy;
		}

		public void setBookingPolicy(Optional<Values> bookingPolicy) {
			this.bookingPolicy = bookingPolicy;
		}

		public String getName() {
			return this.name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Optional<String> getDescription() {
			return this.description;
		}

		public void setDescription(Optional<String> description) {
			this.description = description;
		}

		public String getCategory() {
			return this.category;
		}

		public void setCategory(String category) {
			this.category = category;
		}

		public Optional<String> getLevel() {
			return this.level;
		}

		public void setLevel(Optional<String> level) {
			this.level = level;
		}

		public Optional<Integer> getMinAge() {
			return this.minAge;
		}

		public void setMinAge(Optional<Integer> minAge) {
			this.minAge = minAge;
		}

		public Optional<Integer> getMaxAge() {
			return this.maxAge;
		}

		public void setMaxAge(Optional<Integer> maxAge) {
			this.maxAge = maxAge;
		}

		public Integer getDefaultDurationMinutes() {
			return this.defaultDurationMinutes;
		}

		public void setDefaultDurationMinutes(Integer defaultDurationMinutes) {
			this.defaultDurationMinutes = defaultDurationMinutes;
		}

		public Optional<Integer> getMinParticipants() {
			return this.minParticipants;
		}

		public void setMinParticipants(Optional<Integer> minParticipants) {
			this.minParticipants = minParticipants;
		}

		public Optional<Integer> getMaxParticipants() {
			return this.maxParticipants;
		}

		public void setMaxParticipants(Optional<Integer> maxParticipants) {
			this.maxParticipants = maxParticipants;
		}

		public Optional<String> getCalendarColor() {
			return this.calendarColor;
		}

		public void setCalendarColor(Optional<String> calendarColor) {
			this.calendarColor = calendarColor;
		}

		public Boolean getIsActive() {
			return this.isActive;
		}

		public void setIsActive(Boolean isActive) {
			this.isActive = isActive;
		}

	}

}
