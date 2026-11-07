package studioforyou.pl.backend.communication.push;

/**
 * The outcome of sending one {@link PushMessage}.
 *
 * @param outcome what happened
 * @param messageId the provider's message ID when sent
 * @param error the provider's error when not sent
 */
public record PushResult(Outcome outcome, String messageId, String error) {

	public enum Outcome {

		/** Accepted by the provider. */
		SENT,

		/** The token no longer belongs to an app installation (e.g. the app was removed); do not use it again. */
		INVALID_TOKEN,

		/** Not sent for another reason, e.g. the provider is unavailable; can be tried again. */
		FAILED

	}

	public static PushResult sent(String messageId) {
		return new PushResult(Outcome.SENT, messageId, null);
	}

	public static PushResult invalidToken(String error) {
		return new PushResult(Outcome.INVALID_TOKEN, null, error);
	}

	public static PushResult failed(String error) {
		return new PushResult(Outcome.FAILED, null, error);
	}

}
