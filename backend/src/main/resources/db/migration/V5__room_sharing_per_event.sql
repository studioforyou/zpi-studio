-- V5
--
-- Room sharing (scheduling, #133): a room no longer has a capacity in parallel events. Whether
-- an event takes the whole room is decided for each event instead.

ALTER TABLE resource DROP COLUMN capacity_units;

ALTER TABLE service_type_resource DROP COLUMN units_required;
COMMENT ON TABLE service_type_resource IS '[scheduling] Which service types may take place in a room, e.g. pole dance only in a room with poles. is_default = the service''s default room; a service has at most one.';

ALTER TABLE event_resource ADD COLUMN blocks_whole_room BOOLEAN NOT NULL DEFAULT FALSE;
-- units_used NULL meant the whole room; a number meant a part of it.
UPDATE event_resource SET blocks_whole_room = (units_used IS NULL);
ALTER TABLE event_resource DROP COLUMN units_used;
COMMENT ON TABLE event_resource IS '[scheduling] Room used by an event. An event that blocks the whole room cannot overlap any other event in that room; events that do not block it may share the room. Enforced in a transaction with a lock.';
COMMENT ON COLUMN event_resource.blocks_whole_room IS 'TRUE = no other event may use the room at the same time; FALSE = the room can be shared with other non-blocking events.';
