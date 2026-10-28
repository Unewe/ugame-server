package com.ugame.meta.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ошибки в формате RFC 9457 (application/problem+json) с дополнительным полем "code". */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        pd.setProperty("code", e.code());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Некорректные данные");
        pd.setProperty("code", "validation_failed");
        pd.setProperty("fields", e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList());
        return pd;
    }
}
