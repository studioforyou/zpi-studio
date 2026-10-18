-- V3
--
-- Role dictionary (identity)
-- Their default permissions (role_permission) come later with the permission model.

INSERT INTO role (id, code, display_name) VALUES
    (1, 'CLIENT',       'Klient'),
    (2, 'TRAINER',      'Trener');