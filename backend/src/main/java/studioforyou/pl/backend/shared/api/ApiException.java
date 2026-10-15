package studioforyou.pl.backend.shared.api;

/**
 * Exception turned into a Problem Details response with the status and code of its
 * {@link ErrorCode}. The message is the English {@code detail} sent to the client.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	public ApiException(ErrorCode errorCode, String detail) {
		super(detail);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return this.errorCode;
	}

}
