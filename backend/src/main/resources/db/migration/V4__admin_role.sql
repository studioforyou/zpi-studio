-- V4
--
-- ADMIN role (identity): studio staff who manage accounts, e.g. send invitations.
-- Which permissions a role has (role_permission) is studio configuration, not part of the schema.

INSERT INTO role (id, code, display_name) VALUES
    (3, 'ADMIN',        'Administrator');
