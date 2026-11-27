-- V30
--
-- Message attachments (communication, files; #149, #152)
-- Clients and trainers attach photos and documents to messages in every kind of conversation.
-- The content lives in file storage (a local directory or a Cloud Storage bucket); stored_file
-- keeps its metadata and message_attachment links it to the message in the order it was sent.
-- Deleting a message deletes its files: stored_file.deleted_at is set and the content removed.
-- The details of a conversation show all its photos, and apart from them all its other files.
-- Whether an attachment is a photo is kept with its link to the message, so the communication
-- module lists them from its own tables; the type of a stored file never changes.
-- A JPEG or PNG photo gets a thumbnail, a small JPEG kept next to it in file storage, which the
-- app shows in the conversation and in its photos instead of the whole photo.

ALTER TABLE message_attachment
    ADD COLUMN position SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN is_image BOOLEAN  NOT NULL DEFAULT FALSE;

ALTER TABLE stored_file
    ADD COLUMN image_width          INTEGER,
    ADD COLUMN image_height         INTEGER,
    ADD COLUMN thumbnail_key        VARCHAR(500),
    ADD COLUMN thumbnail_size_bytes INTEGER;

COMMENT ON COLUMN message_attachment.position IS 'Order of the attachment within its message, from 0.';
COMMENT ON COLUMN message_attachment.is_image IS 'Whether the file is a photo (JPEG, PNG or HEIC), as its stored_file.mime_type says.';
COMMENT ON COLUMN stored_file.image_width IS 'Width of an image as displayed (EXIF orientation applied), in pixels; NULL for other files or when unknown.';
COMMENT ON COLUMN stored_file.image_height IS 'Height of an image as displayed (EXIF orientation applied), in pixels; NULL for other files or when unknown.';
COMMENT ON COLUMN stored_file.thumbnail_key IS 'Key of the thumbnail in the same bucket: a JPEG of at most 1024 px on its longer side, upright and without metadata; NULL when the file has none.';
COMMENT ON COLUMN stored_file.thumbnail_size_bytes IS 'Size of the thumbnail in bytes; NULL when the file has none.';
