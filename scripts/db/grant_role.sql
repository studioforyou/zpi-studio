-- Grants a role to an existing account, e.g. ADMIN to the first administrator of a new
-- environment (after that, administrators manage roles in the API / web panel).
-- The grant is recorded in audit_log with no actor (a system change).
--
-- Variables (psql -v):
--   email   the account's e-mail
--   role    role code: CLIENT, TRAINER or ADMIN
--
-- Fails when there is no such account or role, or the account already has the role.

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE target ON COMMIT DROP AS
SELECT a.id AS account_id, r.id AS role_id, r.code AS role_code
FROM account a, role r
WHERE lower(a.email) = lower(:'email') AND r.code = :'role';

DO $$
BEGIN
    IF (SELECT count(*) FROM target) <> 1 THEN
        RAISE EXCEPTION 'No account with this e-mail, or no such role.';
    END IF;
END
$$;

INSERT INTO audit_log (actor_account_id, module, entity_type, entity_id, action, changes, reason)
SELECT NULL, 'identity', 'account', account_id, 'ROLE_GRANTED',
       jsonb_build_object('roles', jsonb_build_object(
           'old', (SELECT coalesce(jsonb_agg(r.code ORDER BY r.code), '[]'::jsonb)
                   FROM account_role ar JOIN role r ON r.id = ar.role_id
                   WHERE ar.account_id = target.account_id),
           'new', (SELECT jsonb_agg(code ORDER BY code)
                   FROM (SELECT r.code FROM account_role ar JOIN role r ON r.id = ar.role_id
                         WHERE ar.account_id = target.account_id
                         UNION SELECT role_code) codes))),
       'Granted with scripts/db/grant_role.sql'
FROM target;

INSERT INTO account_role (account_id, role_id)
SELECT account_id, role_id FROM target;

COMMIT;
