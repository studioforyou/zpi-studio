package studioforyou.pl.backend.communication.messaging;

import java.util.UUID;

/**
 * Published when a message is sent, so the other participants get a notification right after the
 * message is saved.
 *
 * @param conversationId the conversation
 * @param messageId the new message
 * @param senderId the account that wrote it
 */
public record MessageSent(UUID conversationId, UUID messageId, UUID senderId) {
}
