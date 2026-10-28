package studioforyou.pl.backend.shared.api;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.Range;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an {@code application/problem+json} response with a stable code.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred.";

	private static final String VALIDATION_FAILED_DETAIL = "Request validation failed.";

	private static final String UNAUTHENTICATED_DETAIL = "Missing, expired or invalid access token.";

	private static final String ACCESS_DENIED_DETAIL = "The caller is not permitted to perform this operation.";

	private static final String VERSION_CONFLICT_DETAIL = "The resource was modified by another request. Reload it and try again.";

	private static final Set<Class<? extends Annotation>> REQUIRED_CONSTRAINTS = Set.of(NotNull.class,
			NotBlank.class, NotEmpty.class);

	private static final Set<Class<? extends Annotation>> RANGE_CONSTRAINTS = Set.of(Min.class, Max.class,
			Positive.class, Range.class);

	private static final Set<Class<? extends Annotation>> FORMAT_CONSTRAINTS = Set.of(Pattern.class, Email.class);

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
		ErrorCode code = ex.getErrorCode();
		if (code == ErrorCode.VALIDATION_FAILED) {
			return validationFailed(ex, ex.getFieldErrors(), new HttpHeaders(), request);
		}
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.getStatus(), ex.getMessage());
		ex.getProperties().forEach(problem::setProperty);
		problem.setProperty("code", code.name());
		return handleExceptionInternal(ex, problem, new HttpHeaders(), code.getStatus(), request);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
		return problem(ex, ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_DETAIL, new HttpHeaders(), request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
		return problem(ex, ErrorCode.ACCESS_DENIED, ACCESS_DENIED_DETAIL, new HttpHeaders(), request);
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ResponseEntity<Object> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex,
			WebRequest request) {
		return problem(ex, ErrorCode.VERSION_CONFLICT, VERSION_CONFLICT_DETAIL, new HttpHeaders(), request);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
		List<ApiProblem.FieldError> errors = ex.getConstraintViolations()
			.stream()
			.map((violation) -> fieldError(lastNodeName(violation.getPropertyPath()), violation))
			.toList();
		return validationFailed(ex, errors, new HttpHeaders(), request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unexpected error while handling {}", path(request), ex);
		return problem(ex, ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_DETAIL, new HttpHeaders(), request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return validationFailed(ex, fieldErrors(ex.getBindingResult()), headers, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ApiProblem.FieldError> errors = new ArrayList<>();
		for (ParameterValidationResult result : ex.getParameterValidationResults()) {
			if (result instanceof ParameterErrors parameterErrors) {
				errors.addAll(fieldErrors(parameterErrors));
				continue;
			}
			String parameterName = result.getMethodParameter().getParameterName();
			for (MessageSourceResolvable error : result.getResolvableErrors()) {
				errors.add(fieldError(parameterName, unwrapViolation(result, error), error));
			}
		}
		return validationFailed(ex, errors, headers, request);
	}

	/**
	 * Central point every response of this handler passes through: makes sure the body is a
	 * {@link ProblemDetail} with a {@code code} and an {@code instance}, and that 5xx responses
	 * expose no internal details.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		ProblemDetail problem = toProblemDetail(ex, body, statusCode);
		Map<String, Object> properties = problem.getProperties();
		if (properties == null || !properties.containsKey("code")) {
			problem.setProperty("code", codeForStatus(statusCode).name());
		}
		if (statusCode.is5xxServerError()) {
			problem.setDetail(INTERNAL_ERROR_DETAIL);
		}
		if (problem.getInstance() == null) {
			problem.setInstance(URI.create(path(request)));
		}
		return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
	}

	private ProblemDetail toProblemDetail(Exception ex, @Nullable Object body, HttpStatusCode statusCode) {
		if (body instanceof ProblemDetail problem) {
			return problem;
		}
		if (ex instanceof ErrorResponse errorResponse) {
			return errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
		}
		return ProblemDetail.forStatus(statusCode);
	}

	private ResponseEntity<Object> problem(Exception ex, ErrorCode code, String detail, HttpHeaders headers,
			WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.getStatus(), detail);
		problem.setProperty("code", code.name());
		return handleExceptionInternal(ex, problem, headers, code.getStatus(), request);
	}

	private ResponseEntity<Object> validationFailed(Exception ex, List<ApiProblem.FieldError> errors,
			HttpHeaders headers, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_DETAIL);
		problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}

	private static ErrorCode codeForStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> ErrorCode.MALFORMED_REQUEST;
			case 401 -> ErrorCode.UNAUTHENTICATED;
			case 403 -> ErrorCode.ACCESS_DENIED;
			case 404 -> ErrorCode.NOT_FOUND;
			case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
			case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
			default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.MALFORMED_REQUEST;
		};
	}

	private static List<ApiProblem.FieldError> fieldErrors(Errors errors) {
		List<ApiProblem.FieldError> result = new ArrayList<>();
		for (ObjectError error : errors.getAllErrors()) {
			String field = (error instanceof FieldError fieldError) ? fieldError.getField() : error.getObjectName();
			result.add(fieldError(field, unwrapViolation(error), error));
		}
		return result;
	}

	private static @Nullable ConstraintViolation<?> unwrapViolation(ObjectError error) {
		try {
			return error.unwrap(ConstraintViolation.class);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static @Nullable ConstraintViolation<?> unwrapViolation(ParameterValidationResult result,
			MessageSourceResolvable error) {
		try {
			return result.unwrap(error, ConstraintViolation.class);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static ApiProblem.FieldError fieldError(String field, ConstraintViolation<?> violation) {
		return new ApiProblem.FieldError(field, fieldErrorCode(violation), violation.getMessage());
	}

	private static ApiProblem.FieldError fieldError(String field, @Nullable ConstraintViolation<?> violation,
			MessageSourceResolvable error) {
		if (violation != null) {
			return fieldError(field, violation);
		}
		return new ApiProblem.FieldError(field, FieldErrorCode.INVALID_VALUE, error.getDefaultMessage());
	}

	private static FieldErrorCode fieldErrorCode(ConstraintViolation<?> violation) {
		Annotation constraint = violation.getConstraintDescriptor().getAnnotation();
		Class<? extends Annotation> type = constraint.annotationType();
		if (REQUIRED_CONSTRAINTS.contains(type)) {
			return FieldErrorCode.REQUIRED;
		}
		if (RANGE_CONSTRAINTS.contains(type)) {
			return FieldErrorCode.OUT_OF_RANGE;
		}
		if (FORMAT_CONSTRAINTS.contains(type)) {
			return FieldErrorCode.INVALID_FORMAT;
		}
		if (constraint instanceof Size size) {
			return lengthCode(violation.getInvalidValue(), size.min());
		}
		if (constraint instanceof Length length) {
			return lengthCode(violation.getInvalidValue(), length.min());
		}
		return FieldErrorCode.INVALID_VALUE;
	}

	private static FieldErrorCode lengthCode(@Nullable Object value, int min) {
		int length = switch (value) {
			case CharSequence text -> text.length();
			case Collection<?> collection -> collection.size();
			case Map<?, ?> map -> map.size();
			case null -> 0;
			default -> value.getClass().isArray() ? Array.getLength(value) : 0;
		};
		return (length < min) ? FieldErrorCode.TOO_SHORT : FieldErrorCode.TOO_LONG;
	}

	private static String lastNodeName(Path path) {
		String name = null;
		for (Path.Node node : path) {
			name = node.getName();
		}
		return (name != null) ? name : path.toString();
	}

	private static String path(WebRequest request) {
		if (request instanceof ServletWebRequest servletRequest) {
			return servletRequest.getRequest().getRequestURI();
		}
		return request.getDescription(false).replaceFirst("^uri=", "");
	}

}
