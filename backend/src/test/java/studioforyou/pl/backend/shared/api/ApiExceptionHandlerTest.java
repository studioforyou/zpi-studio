package studioforyou.pl.backend.shared.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ApiExceptionHandlerTest.ErrorTestController.class)
@Import({ ApiExceptionHandlerTest.ErrorTestController.class, ValidationConfig.class })
class ApiExceptionHandlerTest {

	private static final String SECRET_MESSAGE = "secret internal failure";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void apiExceptionUsesItsCodeAndStatus() throws Exception {
		this.mockMvc.perform(get("/api/v1/test-errors/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.detail").value("Thing not found."))
			.andExpect(jsonPath("$.instance").value("/api/v1/test-errors/not-found"));
	}

	@Test
	void invalidBodyListsFailedFields() throws Exception {
		this.mockMvc
			.perform(post("/api/v1/test-errors/validated").contentType(MediaType.APPLICATION_JSON)
				.header("Accept-Language", "pl")
				.content("{\"name\": \"\", \"code\": \"ABCD\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(2))
			.andExpect(jsonPath("$.errors", everyItem(hasKey("field"))))
			.andExpect(jsonPath("$.errors", everyItem(hasKey("code"))))
			.andExpect(jsonPath("$.errors", everyItem(hasKey("message"))))
			.andExpect(jsonPath("$.errors[?(@.field == 'name')].code").value("REQUIRED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'name')].message").value("must not be blank"))
			.andExpect(jsonPath("$.errors[?(@.field == 'code')].code").value("TOO_LONG"));
	}

	@Test
	void invalidParameterListsFailedFields() throws Exception {
		this.mockMvc.perform(get("/api/v1/test-errors/items").param("size", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("size"))
			.andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
	}

	@Test
	void unexpectedExceptionExposesNoDetails() throws Exception {
		this.mockMvc.perform(get("/api/v1/test-errors/crash"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.detail").value(ApiExceptionHandler.INTERNAL_ERROR_DETAIL))
			.andExpect(content().string(not(containsString(SECRET_MESSAGE))))
			.andExpect(content().string(not(containsString("RuntimeException"))))
			.andExpect(content().string(not(containsString("at studioforyou."))))
			.andExpect(jsonPath("$", not(hasKey("trace"))));
	}

	@Test
	void unknownPathIsNotFound() throws Exception {
		this.mockMvc.perform(get("/api/v1/does-not-exist"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void frameworkErrorsGetACode() throws Exception {
		this.mockMvc.perform(post("/api/v1/test-errors/not-found"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@RestController
	@RequestMapping("/api/v1/test-errors")
	static class ErrorTestController {

		@GetMapping("/not-found")
		String notFound() {
			throw new ApiException(ErrorCode.NOT_FOUND, "Thing not found.");
		}

		@PostMapping("/validated")
		String validated(@Valid @RequestBody TestRequest request) {
			return "ok";
		}

		@GetMapping("/items")
		String items(@RequestParam @Min(1) int size) {
			return "ok";
		}

		@GetMapping("/crash")
		String crash() {
			throw new RuntimeException(SECRET_MESSAGE);
		}

	}

	record TestRequest(@NotBlank String name, @Size(max = 3) String code) {
	}

}
