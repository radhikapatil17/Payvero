package dev.payvero.api;

import dev.payvero.auth.EmailAlreadyExistsException;
import dev.payvero.auth.InvalidCredentialsException;
import dev.payvero.auth.InvalidRefreshTokenException;
import dev.payvero.fraud.FraudFlagAlreadyReviewedException;
import dev.payvero.fraud.FraudFlagNotFoundException;
import dev.payvero.accounting.AccountNotFoundException;
import dev.payvero.accounting.InsufficientFundsException;
import dev.payvero.accounting.AccountingException;
import dev.payvero.statement.InvalidPeriodException;
import dev.payvero.statement.PeriodNotClosedException;
import dev.payvero.statement.StatementNotFoundException;
import dev.payvero.transfer.IdempotencyConflictException;
import dev.payvero.transfer.RateLimitExceededException;
import dev.payvero.transfer.RequestInProgressException;
import dev.payvero.transfer.TransferNotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException e) {
        return build(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", e.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", e.getMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No such account");
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException e) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_FUNDS", e.getMessage());
    }

    @ExceptionHandler(AccountingException.class)
    ResponseEntity<ErrorResponse> handleLedgerRule(AccountingException e) {
        return build(HttpStatus.BAD_REQUEST, "LEDGER_RULE_VIOLATION", e.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The account changed while this request was in flight; retry");
    }

    @ExceptionHandler(TransferNotFoundException.class)
    ResponseEntity<ErrorResponse> handleTransferNotFound(TransferNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(RequestInProgressException.class)
    ResponseEntity<ErrorResponse> handleRequestInProgress(RequestInProgressException e) {
        return build(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException e) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler(FraudFlagNotFoundException.class)
    ResponseEntity<ErrorResponse> handleFlagNotFound(FraudFlagNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "FRAUD_FLAG_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(FraudFlagAlreadyReviewedException.class)
    ResponseEntity<ErrorResponse> handleFlagAlreadyReviewed(FraudFlagAlreadyReviewedException e) {
        return build(HttpStatus.CONFLICT, "FRAUD_FLAG_ALREADY_REVIEWED", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY",
                "The request body could not be parsed as JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        ErrorResponse body = new ErrorResponse(
                clock.instant(),
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_PARAMETER",
                "One or more parameters are the wrong type",
                Map.of(e.getName(), "is not a valid value"));
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(StatementNotFoundException.class)
    ResponseEntity<ErrorResponse> handleStatementNotFound(StatementNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "STATEMENT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(InvalidPeriodException.class)
    ResponseEntity<ErrorResponse> handleInvalidPeriod(InvalidPeriodException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PERIOD", e.getMessage());
    }

    @ExceptionHandler(PeriodNotClosedException.class)
    ResponseEntity<ErrorResponse> handlePeriodNotClosed(PeriodNotClosedException e) {
        return build(HttpStatus.CONFLICT, "PERIOD_NOT_CLOSED", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ErrorResponse body = new ErrorResponse(
                clock.instant(),
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "One or more fields are invalid",
                fieldErrors);

        return ResponseEntity.badRequest().body(body);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(clock.instant(), status.value(), code, message));
    }
}
