-- V11
--
-- Conversation settings (communication)
-- A group or class chat is created by a trainer, who decides whether clients may reply.
-- Participants stay in conversation_participant after they leave the group or cancel the
-- booking, so they keep the history up to left_at; hidden_at removes the chat from their list.

ALTER TABLE conversation
    ADD COLUMN clients_can_reply BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE conversation_participant
    ADD COLUMN hidden_at TIMESTAMPTZ;

CREATE UNIQUE INDEX ux_conversation_group ON conversation (training_group_id) WHERE kind = 'GROUP';
CREATE UNIQUE INDEX ux_conversation_event ON conversation (event_id) WHERE kind = 'EVENT';
CREATE INDEX ix_conversation_participant_account ON conversation_participant (account_id);

COMMENT ON COLUMN conversation.clients_can_reply IS 'Group and class chats: whether clients may write; the staff running the classes always may. Always TRUE for 1:1 conversations.';
COMMENT ON COLUMN conversation_participant.hidden_at IS 'When the participant removed the conversation from their own list; it comes back with a newer message they can see.';
