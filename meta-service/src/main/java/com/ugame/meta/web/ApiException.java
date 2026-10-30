package com.ugame.meta.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Ошибка API с машиночитаемым кодом — клиенту в Unity удобно разбирать поле "code". */
@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message, null, false, false); // стектрейс не нужен: это ожидаемый ответ, а не сбой
        this.status = status;
        this.code = code;
    }
}
