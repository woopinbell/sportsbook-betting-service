package com.sportsbook.betting.api;

import com.sportsbook.betting.error.BetNotFoundException;
import com.sportsbook.betting.error.BetPlacementException;
import com.sportsbook.protocol.error.ErrorCode;
import com.sportsbook.protocol.error.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Renders every error as an RFC 7807 {@code application/problem+json} body (ADR-0004), reusing the
 * shared-protocol {@link ErrorCode} catalog so the type URI / title / machine code stay consistent
 * across services. A {@link BetPlacementException} already carries its {@link ErrorCode} (so the
 * one handler covers ODDS_DRIFT / LIMIT_EXCEEDED / INSUFFICIENT_BALANCE / EVENT_CLOSED /
 * DUPLICATE_BET / fail-closed INTERNAL_ERROR); framework/binding failures map to VALIDATION_FAILED.
 *
 * <p>{@code correlationId} is the current trace id from the logging MDC when present.
 */
@RestControllerAdvice
public class BetExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(BetExceptionHandler.class);
  private static final URI NOT_FOUND_TYPE = URI.create("https://sportsbook/errors/not-found");

  @ExceptionHandler(BetPlacementException.class)
  public ResponseEntity<ProblemDetail> handlePlacement(
      BetPlacementException e, HttpServletRequest request) {
    return problem(e.errorCode(), e.getMessage(), request);
  }

  @ExceptionHandler(BetNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(
      BetNotFoundException e, HttpServletRequest request) {
    ProblemDetail body =
        new ProblemDetail(
            NOT_FOUND_TYPE,
            "Bet not found",
            HttpStatus.NOT_FOUND.value(),
            "BET_NOT_FOUND",
            e.getMessage(),
            URI.create(request.getRequestURI()),
            MDC.get("traceId"));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MissingRequestHeaderException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<ProblemDetail> handleBadRequest(Exception e, HttpServletRequest request) {
    return problem(ErrorCode.VALIDATION_FAILED, e.getMessage(), request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
    log.error("Unhandled error on {}", request.getRequestURI(), e);
    return problem(ErrorCode.INTERNAL_ERROR, "Internal server error", request);
  }

  private static ResponseEntity<ProblemDetail> problem(
      ErrorCode code, String detail, HttpServletRequest request) {
    ProblemDetail body =
        code.toProblemDetail(detail, URI.create(request.getRequestURI()), MDC.get("traceId"));
    return ResponseEntity.status(code.httpStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
