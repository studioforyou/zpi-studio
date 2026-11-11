package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checks that the generated OpenAPI document contains the expected paths, security and error responses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, OpenApiDocsTest.PublicTestController.class })
class OpenApiDocsTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void documentsTheApi() throws Exception {
		String json = apiDocs();

		Map<String, Object> paths = JsonPath.read(json, "$.paths");
		assertThat(paths).containsKey("/api/v1/studio");
		assertThat(paths.keySet()).allMatch((path) -> path.startsWith("/api/"));
		assertThat((String) JsonPath.read(json, "$.components.securitySchemes.bearerAuth.type")).isEqualTo("http");
		assertThat((String) JsonPath.read(json, "$.components.securitySchemes.bearerAuth.scheme")).isEqualTo("bearer");
	}

	@Test
	void documentsOwnDocumentEndpoints() throws Exception {
		String json = apiDocs();

		Map<String, Object> paths = JsonPath.read(json, "$.paths");
		assertThat(paths).containsKeys("/api/v1/me/profiles/{profileId}/documents",
				"/api/v1/me/profiles/{profileId}/documents/{documentId}",
				"/api/v1/me/profiles/{profileId}/documents/{documentId}/acceptances");
		Map<String, Object> acceptances = JsonPath.read(json,
				"$.paths['/api/v1/me/profiles/{profileId}/documents/{documentId}/acceptances']");
		assertThat(acceptances).containsOnlyKeys("post", "delete");
		Map<String, Object> request = JsonPath.read(json, "$.components.schemas.AcceptDocumentRequest.properties");
		assertThat(request).containsOnlyKeys("versionId");
	}

	@Test
	void documentsTheFiltersOfEventReportsAsQueryParameters() throws Exception {
		String json = apiDocs();

		for (String path : List.of("/api/v1/reports/class-attendance", "/api/v1/reports/booking-outcomes")) {
			List<String> names = JsonPath.read(json, "$.paths['" + path + "'].get.parameters[*].name");
			assertThat(names).containsExactlyInAnyOrder("from", "to", "kind", "serviceTypeId", "staffId",
					"trainingGroupId", "resourceId");
			List<String> places = JsonPath.read(json, "$.paths['" + path + "'].get.parameters[*].in");
			assertThat(places).containsOnly("query");
		}
	}

	@Test
	void documentsOwnWaitlistEndpoints() throws Exception {
		String json = apiDocs();

		Map<String, Object> paths = JsonPath.read(json, "$.paths");
		assertThat(paths).containsKeys("/api/v1/me/profiles/{profileId}/group-classes/{eventId}/waitlist-entries",
				"/api/v1/me/profiles/{profileId}/waitlist-entries/{entryId}",
				"/api/v1/me/profiles/{profileId}/waitlist-entries/{entryId}/cancel",
				"/api/v1/me/profiles/{profileId}/waitlist-entries/{entryId}/confirm");
		Map<String, Object> request = JsonPath.read(json,
				"$.components.schemas.JoinGroupClassWaitlistRequest.properties");
		assertThat(request).containsOnlyKeys("customerPassId");
		Map<String, Object> waitlist = JsonPath.read(json, "$.components.schemas.GroupClassWaitlist.properties");
		assertThat(waitlist).containsOnlyKeys("allowed", "reason", "waitingCount", "confirmationMinutes", "ownEntry");
		Map<String, Object> groupClass = JsonPath.read(json, "$.components.schemas.CustomerGroupClass.properties");
		assertThat(groupClass).containsKeys("booking", "waitlist");
	}

	@Test
	void documentsRoomEndpoints() throws Exception {
		String json = apiDocs();

		Map<String, Object> paths = JsonPath.read(json, "$.paths");
		assertThat(paths).containsKeys("/api/v1/resources", "/api/v1/resources/{resourceId}",
				"/api/v1/resources/{resourceId}/service-types", "/api/v1/service-types");
		Map<String, Object> updateProperties = JsonPath.read(json,
				"$.components.schemas.UpdateResourceRequest.properties");
		assertThat(updateProperties).containsOnlyKeys("name", "kind", "parentResourceId", "maxPeople",
				"description", "isActive", "serviceTypes");
	}

	@Test
	void documentsScheduleEndpoints() throws Exception {
		String json = apiDocs();

		Map<String, Object> paths = JsonPath.read(json, "$.paths");
		assertThat(paths).containsKeys("/api/v1/schedule-events", "/api/v1/schedule-events/{eventId}",
				"/api/v1/schedule-events/preview", "/api/v1/schedule-events/{eventId}/assignments",
				"/api/v1/recurrence-series", "/api/v1/recurrence-series/preview",
				"/api/v1/recurrence-series/{seriesId}", "/api/v1/staff", "/api/v1/training-groups");
		List<Map<String, Object>> parameters = JsonPath.read(json, "$.paths['/api/v1/schedule-events'].get.parameters");
		assertThat(parameters).extracting((parameter) -> parameter.get("name"))
			.contains("from", "to", "kind", "status", "serviceTypeId", "staffId", "resourceId", "trainingGroupId");
		assertThat((String) JsonPath.read(json, "$.components.schemas.RecurrenceSeries.properties.localStartTime.type"))
			.isEqualTo("string");
	}

	@Test
	void documentsTrainerEventParticipants() throws Exception {
		String json = apiDocs();

		assertThat((String) JsonPath.read(json, "$.paths['/api/v1/me/schedule-events/{eventId}'].get.operationId"))
			.isEqualTo("getOwnScheduleEvent");
		String path = "$.paths['/api/v1/me/schedule-events/{eventId}/participants'].get";
		assertThat((String) JsonPath.read(json, path + ".operationId")).isEqualTo("listOwnEventParticipants");
		Map<String, Object> responses = JsonPath.read(json, path + ".responses");
		assertThat(responses).containsKeys("200", "401", "403", "404");
		Map<String, Object> fields = JsonPath.read(json, "$.components.schemas.EventParticipant.properties");
		assertThat(fields).containsOnlyKeys("reservationId", "customerId", "kind", "firstName", "lastName",
				"displayName", "birthDate", "bookedAt");
	}

	@Test
	void documentsAttendance() throws Exception {
		String json = apiDocs();

		String own = "$.paths['/api/v1/me/schedule-events/{eventId}/attendance']";
		assertThat((Map<String, Object>) JsonPath.read(json, own)).containsOnlyKeys("get", "patch");
		assertThat((String) JsonPath.read(json, own + ".patch.operationId")).isEqualTo("markOwnEventAttendance");
		assertThat((Map<String, Object>) JsonPath.read(json, own + ".patch.responses"))
			.containsKeys("200", "400", "401", "403", "404", "422");
		assertThat((String) JsonPath.read(json,
				"$.paths['/api/v1/me/schedule-events/{eventId}/complete'].post.operationId")).isEqualTo("completeOwnEvent");
		String correct = "$.paths['/api/v1/schedule-events/{eventId}/reservations/{reservationId}/correct-attendance'].post";
		assertThat((String) JsonPath.read(json, correct + ".operationId")).isEqualTo("correctEventAttendance");
		assertThat((Map<String, Object>) JsonPath.read(json, correct + ".responses"))
			.containsKeys("200", "400", "401", "403", "404", "409", "422");
		assertThat((String) JsonPath.read(json, "$.paths['/api/v1/schedule-events/{eventId}/attendance'].get.operationId"))
			.isEqualTo("getEventAttendance");
		Map<String, Object> request = JsonPath.read(json, "$.components.schemas.CorrectAttendanceRequest.properties");
		assertThat(request).containsOnlyKeys("attendance", "reason", "version");
		Map<String, Object> change = JsonPath.read(json, "$.components.schemas.AttendanceChange.properties");
		assertThat(change).containsOnlyKeys("changedAt", "kind", "previousAttendance", "newAttendance", "changedBy",
				"reason");
	}

	@Test
	void documentsStaffBooking() throws Exception {
		String json = apiDocs();

		String reservations = "$.paths['/api/v1/schedule-events/{eventId}/reservations']";
		assertThat((Map<String, Object>) JsonPath.read(json, reservations)).containsOnlyKeys("get", "post");
		assertThat((String) JsonPath.read(json, reservations + ".get.operationId")).isEqualTo("listEventReservations");
		assertThat((String) JsonPath.read(json, reservations + ".post.operationId")).isEqualTo("bookEventReservation");
		assertThat((Map<String, Object>) JsonPath.read(json, reservations + ".post.responses"))
			.containsKeys("200", "201", "400", "401", "403", "404", "409", "422");
		String cancel = "$.paths['/api/v1/schedule-events/{eventId}/reservations/{reservationId}/cancel'].post";
		assertThat((String) JsonPath.read(json, cancel + ".operationId")).isEqualTo("cancelEventReservation");
		assertThat((Map<String, Object>) JsonPath.read(json, cancel + ".responses"))
			.containsKeys("200", "400", "401", "403", "404", "409", "422");
		assertThat((String) JsonPath.read(json,
				"$.paths['/api/v1/schedule-events/{eventId}/booking-options'].get.operationId"))
			.isEqualTo("getStaffBookingOptions");
		String own = "$.paths['/api/v1/me/schedule-events/{eventId}";
		assertThat((Map<String, Object>) JsonPath.read(json, own + "/reservations']")).containsOnlyKeys("post");
		assertThat((String) JsonPath.read(json, own + "/reservations'].post.operationId"))
			.isEqualTo("bookOwnEventReservation");
		assertThat((Map<String, Object>) JsonPath.read(json, own + "/reservations'].post.responses"))
			.containsKeys("200", "201", "400", "401", "403", "404", "409", "422");
		assertThat((String) JsonPath.read(json, own + "/booking-options'].get.operationId"))
			.isEqualTo("getOwnEventBookingOptions");
		assertThat((Map<String, Object>) JsonPath.read(json, "$.components.schemas.StaffBookRequest.properties"))
			.containsOnlyKeys("customerId", "customerPassId", "acceptedIssues", "reason");
		assertThat((Map<String, Object>) JsonPath.read(json,
				"$.components.schemas.StaffCancelReservationRequest.properties"))
			.containsOnlyKeys("expectedVersion", "returnEntry", "reason");
	}

	@Test
	void sessionOperationsGetAuthErrors() throws Exception {
		Map<String, Object> responses = JsonPath.read(apiDocs(), "$.paths['/api/v1/studio'].get.responses");

		assertThat(responses).containsKeys("200", "401", "403", "404", "500");
	}

	@Test
	void publicOperationsHaveNoSecurityAndNoAuthErrors() throws Exception {
		String json = apiDocs();
		List<Object> security = JsonPath.read(json, "$.paths['/api/v1/test-public'].get.security");
		Map<String, Object> responses = JsonPath.read(json, "$.paths['/api/v1/test-public'].get.responses");

		assertThat(security).isEmpty();
		assertThat(responses).doesNotContainKeys("401", "403").containsKey("500");
	}

	@Test
	void documentsTrainerProfileOperationsAndPartialUpdateRules() throws Exception {
		String json = apiDocs();
		String path = "$.paths['/api/v1/me/staff-profile']";
		Map<String, Object> getResponses = JsonPath.read(json, path + ".get.responses");
		Map<String, Object> patchResponses = JsonPath.read(json, path + ".patch.responses");
		assertThat(getResponses).containsKeys("200", "401", "403", "404", "500");
		assertThat(patchResponses).containsKeys("200", "400", "401", "403", "404", "500");
		assertThat((String) JsonPath.read(json, path + ".get.operationId")).isEqualTo("getMyStaffProfile");
		assertThat((String) JsonPath.read(json, path + ".patch.operationId")).isEqualTo("updateMyStaffProfile");
		Map<String, Object> getOperation = JsonPath.read(json, path + ".get");
		Map<String, Object> patchOperation = JsonPath.read(json, path + ".patch");
		assertThat(getOperation).doesNotContainKey("parameters");
		assertThat(patchOperation).doesNotContainKey("parameters");
		assertThat((String) JsonPath.read(json, path + ".patch.description"))
			.contains("ACTIVE TRAINER", "Omitted fields stay unchanged", "null names are invalid", "clears it");
		Map<String, Object> requestFields = JsonPath.read(json,
				"$.components.schemas.UpdateStaffProfileRequest.properties");
		assertThat(requestFields).containsOnlyKeys("firstName", "lastName", "phone");
		assertThat((Integer) JsonPath.read(json,
				"$.components.schemas.UpdateStaffProfileRequest.properties.firstName.maxLength")).isEqualTo(80);
		assertThat((Integer) JsonPath.read(json,
				"$.components.schemas.UpdateStaffProfileRequest.properties.lastName.maxLength")).isEqualTo(80);
		assertThat((Integer) JsonPath.read(json,
				"$.components.schemas.UpdateStaffProfileRequest.properties.phone.maxLength")).isEqualTo(20);
		Map<String, Object> responseFields = JsonPath.read(json,
				"$.components.schemas.StaffProfileResponse.properties");
		assertThat(responseFields).containsOnlyKeys("id", "firstName", "lastName", "phone");
	}

	private String apiDocs() throws Exception {
		return this.mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	@Test
	void documentsServiceBookingConfiguration() throws Exception {
		String json = apiDocs();
		Map<String, Object> paths = JsonPath.read(json, "$.paths");
		assertThat(paths).containsKeys("/api/v1/service-types/booking-defaults", "/api/v1/service-types/{serviceTypeId}");
		Map<String, Object> properties = JsonPath.read(json, "$.components.schemas.BookingPolicyValues.properties");
		assertThat(properties).containsKeys("cancellationAllowed", "freeCancelHours", "lateCancelAllowed",
				"lateCancelForfeitsEntry", "autoCancelEnabled", "autoCancelMinParticipants", "autoCancelHoursBefore");
		Map<String, Object> operation = JsonPath.read(json, "$.paths['/api/v1/service-types'].post");
		assertThat(operation).doesNotContainKey("parameters");
	}

	@Test
	void documentsClientGroupClassBooking() throws Exception {
		String json = apiDocs();
		Map<String, Object> paths = JsonPath.read(json, "$.paths");
		String base = "/api/v1/me/profiles/{profileId}";
		assertThat(paths).containsKeys(base + "/group-classes", base + "/group-classes/{eventId}", base + "/group-classes/{eventId}/participants",
				base + "/group-classes/{eventId}/reservations", base + "/reservations",
				base + "/reservations/{reservationId}", base + "/reservations/{reservationId}/cancel",
				base + "/training-history");
		Map<String, Object> createResponses = JsonPath.read(json,
				"$.paths['" + base + "/group-classes/{eventId}/reservations'].post.responses");
		assertThat(createResponses).containsKeys("200", "201", "409", "422");
		Map<String, Object> cancelResponses = JsonPath.read(json,
				"$.paths['" + base + "/reservations/{reservationId}/cancel'].post.responses");
		assertThat(cancelResponses).containsKeys("409", "422");
		Map<String, Object> participantFields = JsonPath.read(json,
				"$.components.schemas.GroupClassParticipant.properties");
		assertThat(participantFields).containsOnlyKeys("displayName", "isSelf");
	}

	@RestController
	static class PublicTestController {

		@GetMapping("/api/v1/test-public")
		@SecurityRequirements
		String publicOperation() {
			return "ok";
		}

	}

}
