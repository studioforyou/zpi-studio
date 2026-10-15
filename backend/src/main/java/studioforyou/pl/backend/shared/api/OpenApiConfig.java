package studioforyou.pl.backend.shared.api;

import java.util.List;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document of the API: metadata, the global bearer security requirement and the
 * error responses shared by every operation.
 *
 * <p>
 * Every operation requires a bearer token unless it declares an empty security list. Public
 * and token operations (login, refresh, register, invitation accept, password reset request
 * and confirm, the payment provider webhook and the iCal feed) are called before the person
 * has an access token: annotate them with an empty
 * {@code @io.swagger.v3.oas.annotations.security.SecurityRequirements}. They are documented
 * with {@code security: []} and get no 401/403 responses.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String BEARER_AUTH = "bearerAuth";

	private static final String PROBLEM_JSON = "application/problem+json";

	private static final String PROBLEM_SCHEMA_REF = "#/components/schemas/ApiProblem";

	@Bean
	OpenAPI openApi() {
		return new OpenAPI()
			.info(new Info().title("studio4U API")
				.version("v1")
				.description("REST API of the studio4U studio management system, shared by the mobile app and the web panel."))
			.components(new Components().addSecuritySchemes(BEARER_AUTH,
					new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
	}

	@Bean
	OpenApiCustomizer commonErrorResponses() {
		return (openApi) -> {
			if (openApi.getComponents() == null) {
				openApi.setComponents(new Components());
			}
			ModelConverters.getInstance().readAll(ApiProblem.class).forEach(openApi.getComponents()::addSchemas);
			if (openApi.getPaths() == null) {
				return;
			}
			openApi.getPaths()
				.values()
				.forEach((path) -> path.readOperations().forEach(OpenApiConfig::addCommonErrorResponses));
		};
	}

	private static void addCommonErrorResponses(Operation operation) {
		if (operation.getResponses() == null) {
			operation.setResponses(new ApiResponses());
		}
		ApiResponses responses = operation.getResponses();
		if (!isPublic(operation)) {
			addIfAbsent(responses, "401", "Missing, expired or invalid access token.");
			addIfAbsent(responses, "403", "The caller is not permitted to perform this operation.");
		}
		addIfAbsent(responses, "500", "Unexpected server error.");
	}

	private static boolean isPublic(Operation operation) {
		List<SecurityRequirement> security = operation.getSecurity();
		return security != null && security.isEmpty();
	}

	private static void addIfAbsent(ApiResponses responses, String status, String description) {
		if (responses.containsKey(status)) {
			return;
		}
		Schema<?> schema = new Schema<>().$ref(PROBLEM_SCHEMA_REF);
		responses.addApiResponse(status, new ApiResponse().description(description)
			.content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(schema))));
	}

}
