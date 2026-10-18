-- Creates an ACTIVE account linked to an existing Firebase user, for local development.
-- The Firebase user (e-mail + password) is created first in the Firebase console
-- (Authentication > Users); its "User UID" goes into firebase_uid.
--
-- Variables (psql -v):
--   email         the Firebase user's e-mail
--   firebase_uid  the Firebase user's UID
--   first_name, last_name
--   roles         comma-separated role codes, e.g. CLIENT or CLIENT,TRAINER
--
-- A CLIENT gets a customer profile, a TRAINER a staff profile.
-- See wiki, section "Authentication", for the full command.

\set ON_ERROR_STOP on

WITH new_account AS (
    INSERT INTO account (email, status, activated_at)
    VALUES (:'email', 'ACTIVE', now())
    RETURNING id
), link AS (
    INSERT INTO identity_link (account_id, provider, external_uid)
    SELECT id, 'FIREBASE', :'firebase_uid' FROM new_account
), granted_roles AS (
    INSERT INTO account_role (account_id, role_id)
    SELECT new_account.id, role.id
    FROM new_account
    JOIN role ON role.code = ANY (string_to_array(:'roles', ','))
), customer AS (
    INSERT INTO customer_profile (account_id, first_name, last_name)
    SELECT id, :'first_name', :'last_name' FROM new_account
    WHERE 'CLIENT' = ANY (string_to_array(:'roles', ','))
), staff AS (
    INSERT INTO staff_profile (account_id, first_name, last_name)
    SELECT id, :'first_name', :'last_name' FROM new_account
    WHERE 'TRAINER' = ANY (string_to_array(:'roles', ','))
)
SELECT id AS account_id FROM new_account;
