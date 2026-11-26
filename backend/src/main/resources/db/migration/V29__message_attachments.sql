-- V29
--
-- Message attachments (communication, files; #149, #152)
-- Clients and trainers attach photos and documents to messages in every kind of conversation.
-- The content lives in file storage (a local directory or a Cloud Storage bucket); stored_file
-- keeps its metadata and message_attachment links it to the message in the order it was sent.
-- Deleting a message deletes its files: stored_file.deleted_at is set and the content removed.

ALTER TABLE message_attachment
    ADD COLUMN position SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE stored_file
    ADD COLUMN image_width  INTEGER,
    ADD COLUMN image_height INTEGER;

COMMENT ON COLUMN message_attachment.position IS 'Order of the attachment within its message, from 0.';
COMMENT ON COLUMN stored_file.image_width IS 'Width of an image as displayed (EXIF orientation applied), in pixels; NULL for other files or when unknown.';
COMMENT ON COLUMN stored_file.image_height IS 'Height of an image as displayed (EXIF orientation applied), in pixels; NULL for other files or when unknown.';
