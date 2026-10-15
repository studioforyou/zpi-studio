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

@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
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

	private String apiDocs() throws Exception {
		return this.mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
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
