package studioforyou.pl.backend.configuration;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ErrorCode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/studio")
@Tag(name = "configuration")
public class StudioController {

	private final StudioRepository studioRepository;

	public StudioController(StudioRepository studioRepository) {
		this.studioRepository = studioRepository;
	}

	// TODO(auth): requires authentication once authentication is implemented
	@GetMapping
	@Operation(summary = "Get studio settings", operationId = "getStudio")
	@ApiResponse(responseCode = "200", description = "Studio settings.",
			content = @Content(schema = @Schema(implementation = StudioResponse.class)))
	@ApiResponse(responseCode = "404", description = "The studio settings are not configured.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
					
	public StudioResponse getStudio() {
		return this.studioRepository.findFirstBy()
			.map(StudioResponse::from)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Studio settings are not configured."));
	}

}
