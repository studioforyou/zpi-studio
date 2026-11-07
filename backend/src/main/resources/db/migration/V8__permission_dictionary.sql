-- V8
--
-- Permission dictionary (identity)
-- Permission codes are granted to the caller as Spring Security authorities next to
-- ROLE_<role code>, so they never start with ROLE_.
-- Which roles get which permissions by default (role_permission) is set up per studio,
-- not here; ADMIN always has every permission.

INSERT INTO permission (id, code, category, description) VALUES
    (1, 'ACCOUNT_APPROVE',         'IDENTITY',   'Approve or reject self-registered client accounts'),
    (2, 'CUSTOMER_ACCOUNT_CREATE', 'IDENTITY',   'Create client accounts on behalf of clients'),
    (3, 'SCHEDULE_MANAGE_ALL',     'SCHEDULING', 'Create and edit all classes in the schedule'),
    (4, 'SCHEDULE_EDIT_OWN',       'SCHEDULING', 'Edit the classes the staff member runs'),
    (5, 'AVAILABILITY_EDIT_OWN',   'STAFF',      'Edit own availability and time off'),
    (6, 'CUSTOMER_HEALTH_READ',    'CUSTOMER',   'Read health data of assigned clients who consented to it'),
    (7, 'PASS_ADJUST',             'MEMBERSHIP', 'Manually adjust passes and their entries'),
    (8, 'ATTENDANCE_CORRECT',      'BOOKING',    'Correct attendance after a class');
