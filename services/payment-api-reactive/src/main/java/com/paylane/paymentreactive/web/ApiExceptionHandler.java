package com.paylane.paymentreactive.web;

import com.paylane.paymentreactive.domain.IllegalStateTransitionException;
import com.paylane.paymentreactive.idempotency.IdempotencyExceptions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IdempotencyExceptions.MissingKey.class)
    public ProblemDetail onMissingKey(IdempotencyExceptions.MissingKey e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IdempotencyExceptions.Conflict.class)
    public ProblemDetail onConflict(IdempotencyExceptions.Conflict e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyExceptions.InProgress.class)
    public ProblemDetail onInProgress(IdempotencyExceptions.InProgress e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ProblemDetail onIllegalTransition(IllegalStateTransitionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail onNotFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
