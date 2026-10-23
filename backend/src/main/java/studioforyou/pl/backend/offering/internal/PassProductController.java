package studioforyou.pl.backend.offering.internal;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Pass products offered by the studio. Every active account can read the products on sale;
 * only administrators see archived products and inactive variants, and only they can change the offer.
 */
@RestController
@RequestMapping("/api/v1/pass-products")
@Tag(name = "offering")
public class PassProductController {

	private static final String ADMIN = "hasRole('ADMIN')";

	private final PassProductCatalog catalog;

	public PassProductController(PassProductCatalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	@Operation(summary = "List pass products", operationId = "listPassProducts",
			description = "Clients and trainers get only active products with their active variants.")
	public PassProductListResponse list(
			@Parameter(description = "Administrators only: true for active products, false for archived ones; all when omitted.")
			@RequestParam(required = false) Boolean active,
			Authentication authentication) {
		boolean admin = isAdmin(authentication);
		Boolean filter = admin ? active : Boolean.TRUE;
		return new PassProductListResponse(
				catalog.list(filter).stream().map((product) -> PassProductResponse.from(product, admin)).toList());
	}

	@GetMapping("/{productId}")
	@Operation(summary = "Get a pass product", operationId = "getPassProduct")
	@ApiResponse(responseCode = "200", description = "The pass product.")
	@ApiResponse(responseCode = "404", description = "No such product, or it is archived and the caller is not an administrator.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public PassProductResponse get(@PathVariable UUID productId, Authentication authentication) {
		boolean admin = isAdmin(authentication);
		PassProduct product = catalog.get(productId);
		if (!admin && !product.isActive()) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Pass product not found.");
		}
		return PassProductResponse.from(product, admin);
	}

	@PostMapping
	@PreAuthorize(ADMIN)
	@Operation(summary = "Create a pass product with its variants", operationId = "createPassProduct")
	@ApiResponse(responseCode = "201", description = "The created product.")
	@ApiResponse(responseCode = "400", description = "Validation failed.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "PASS_VARIANT_ALREADY_EXISTS: two variants with the same entries and validity.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "SERVICE_TYPE_UNAVAILABLE: a service type does not exist or is inactive.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<PassProductResponse> create(@Valid @RequestBody CreatePassProductRequest request) {
		PassProduct product = catalog.create(request.toCommand());
		return ResponseEntity.created(URI.create("/api/v1/pass-products/" + product.getId()))
			.body(PassProductResponse.from(product, true));
	}

	@PatchMapping("/{productId}")
	@PreAuthorize(ADMIN)
	@Operation(summary = "Update a pass product", operationId = "updatePassProduct",
			description = "Changes only the fields that are present. Prices change through the variants.")
	@ApiResponse(responseCode = "200", description = "The updated product.")
	@ApiResponse(responseCode = "400", description = "Validation failed.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such product.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "SERVICE_TYPE_UNAVAILABLE: a newly added service type does not exist or is inactive.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public PassProductResponse update(@PathVariable UUID productId,
			@Valid @RequestBody UpdatePassProductRequest request) {
		return PassProductResponse.from(catalog.update(productId, request.toChanges()), true);
	}

	@PostMapping("/{productId}/archive")
	@PreAuthorize(ADMIN)
	@Operation(summary = "Archive a pass product", operationId = "archivePassProduct",
			description = "Takes the product off sale. Passes already bought are not affected.")
	@ApiResponse(responseCode = "200", description = "The archived product.")
	@ApiResponse(responseCode = "404", description = "No such product.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public PassProductResponse archive(@PathVariable UUID productId) {
		return PassProductResponse.from(catalog.archive(productId), true);
	}

	@PostMapping("/{productId}/restore")
	@PreAuthorize(ADMIN)
	@Operation(summary = "Put an archived pass product back on sale", operationId = "restorePassProduct")
	@ApiResponse(responseCode = "200", description = "The restored product.")
	@ApiResponse(responseCode = "404", description = "No such product.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public PassProductResponse restore(@PathVariable UUID productId) {
		return PassProductResponse.from(catalog.restore(productId), true);
	}

	@GetMapping("/{productId}/variants/{variantId}")
	@PreAuthorize(ADMIN)
	@Operation(summary = "Get a pass product variant", operationId = "getPassProductVariant")
	@ApiResponse(responseCode = "200", description = "The variant.")
	@ApiResponse(responseCode = "404", description = "No such product or variant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public PassProductVariantResponse getVariant(@PathVariable UUID productId, @PathVariable UUID variantId) {
		return PassProductVariantResponse.from(catalog.getVariant(productId, variantId));
	}

	@PostMapping("/{productId}/variants")
	@PreAuthorize(ADMIN)
	@Operation(summary = "Add a variant to a pass product", operationId = "addPassProductVariant")
	@ApiResponse(responseCode = "201", description = "The created variant.")
	@ApiResponse(responseCode = "400", description = "Validation failed.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such product.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "PASS_VARIANT_ALREADY_EXISTS: an active variant has the same entries and validity.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "PASS_PRODUCT_ARCHIVED: the product is archived.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<PassProductVariantResponse> addVariant(@PathVariable UUID productId,
			@Valid @RequestBody PassProductVariantRequest request) {
		PassProductVariant variant = catalog.addVariant(productId, request.toCommand());
		return created(productId, variant);
	}

	@PostMapping("/{productId}/variants/{variantId}/deactivate")
	@PreAuthorize(ADMIN)
	@Operation(summary = "Take a variant off sale", operationId = "deactivatePassProductVariant")
	@ApiResponse(responseCode = "200", description = "The deactivated variant.")
	@ApiResponse(responseCode = "404", description = "No such product or variant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public PassProductVariantResponse deactivateVariant(@PathVariable UUID productId, @PathVariable UUID variantId) {
		return PassProductVariantResponse.from(catalog.deactivateVariant(productId, variantId));
	}

	@PostMapping("/{productId}/variants/{variantId}/change-price")
	@PreAuthorize(ADMIN)
	@Operation(summary = "Change the price of a variant", operationId = "changePassProductVariantPrice",
			description = "Deactivates the variant and creates a new one with the same entries and validity and the new price.")
	@ApiResponse(responseCode = "201", description = "The new variant.")
	@ApiResponse(responseCode = "400", description = "Validation failed.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such product or variant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "PASS_PRODUCT_ARCHIVED or PASS_VARIANT_INACTIVE.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<PassProductVariantResponse> changePrice(@PathVariable UUID productId,
			@PathVariable UUID variantId, @Valid @RequestBody ChangeVariantPriceRequest request) {
		PassProductVariant variant = catalog.changePrice(productId, variantId, request.price().toAmount(),
				request.price().currency());
		return created(productId, variant);
	}

	private static ResponseEntity<PassProductVariantResponse> created(UUID productId, PassProductVariant variant) {
		URI location = URI.create("/api/v1/pass-products/" + productId + "/variants/" + variant.getId());
		return ResponseEntity.created(location).body(PassProductVariantResponse.from(variant));
	}

	private static boolean isAdmin(Authentication authentication) {
		return authentication.getAuthorities()
			.stream()
			.anyMatch((authority) -> "ROLE_ADMIN".equals(authority.getAuthority()));
	}

}
