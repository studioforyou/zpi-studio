package studioforyou.pl.backend.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.access.AccessManagement;
import studioforyou.pl.backend.identity.access.PermissionDefinition;
import studioforyou.pl.backend.identity.access.RoleDefinition;
import studioforyou.pl.backend.shared.api.ItemsResponse;

/**
 * The role and permission dictionaries, for the administration panel.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "identity")
@PreAuthorize("hasRole('ADMIN')")
public class AccessDictionaryController {

	private final AccessManagement accessManagement;

	public AccessDictionaryController(AccessManagement accessManagement) {
		this.accessManagement = accessManagement;
	}

	@GetMapping("/roles")
	@Operation(summary = "List roles with their default permissions", operationId = "listRoles",
			description = "Only for administrators.")
	public ItemsResponse<RoleDefinition> roles() {
		return new ItemsResponse<>(accessManagement.roles());
	}

	@GetMapping("/permissions")
	@Operation(summary = "List permissions", operationId = "listPermissions",
			description = "Only for administrators.")
	public ItemsResponse<PermissionDefinition> permissions() {
		return new ItemsResponse<>(accessManagement.permissions());
	}

}
