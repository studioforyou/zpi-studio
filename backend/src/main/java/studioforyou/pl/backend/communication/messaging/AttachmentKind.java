package studioforyou.pl.backend.communication.messaging;

/** Which attachments of a conversation are listed: its photos, or its other files. */
enum AttachmentKind {

	/** JPEG, PNG and HEIC photos. */
	PHOTO,
	/** Documents: PDF, DOCX, XLSX and TXT. */
	FILE

}
