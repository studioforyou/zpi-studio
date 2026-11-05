package studioforyou.pl.backend.communication.messaging;

/** Kind of a conversation ({@code conversation.kind}). */
enum ConversationKind {

	/** A 1:1 conversation between a client and a trainer. */
	DIRECT,
	/** The chat of a fixed group; its members change with the group and its bookings. */
	GROUP,
	/** The chat of one class or workshop; its members are the people booked on it. */
	EVENT

}
