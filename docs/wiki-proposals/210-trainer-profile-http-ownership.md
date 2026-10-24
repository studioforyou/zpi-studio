# Wiki proposal: trainer profile HTTP ownership

Local proposal for the review of PR #210; no Wiki changes have been published.

## Backend-modules

Document the trainer profile HTTP adapter and request/response DTOs under `staff.internal`.
The `staff` module owns `GET` and `PATCH /api/v1/me/staff-profile`, profile validation,
and persistence. The URL and JSON contract remain unchanged.

The adapter reads the authenticated account ID through `shared.api.AccountAuthentication`.
Its implementation in `identity.security.AccountAuthenticationToken` returns the ID resolved
from the backend account only when the token is authenticated and the account is active.
Firebase token claims and request parameters cannot select another profile. Endpoint role
checks still require the backend `TRAINER` role.

`identity` continues to use the public `StaffProfiles` summary operation when composing
`GET /api/v1/me`. Both modules use the narrow technical authentication contract from `shared`;
`staff` does not depend on `identity`. Keep persistence and identity implementation types
out of that shared contract. Table ownership and database migrations are unchanged.

## API-Convention

The existing OpenAPI contract for trainer profile editing still applies. Only the internal
HTTP adapter location changes; mobile clients need no endpoint or payload changes.
