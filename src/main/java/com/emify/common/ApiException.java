package com.emify.common;

import org.springframework.http.HttpStatus;

/**
 * Error de negocio que se convierte en {@code {status:false, message}} con el código HTTP indicado
 * (ver {@link GlobalExceptionHandler}). Sirve para cortar el flujo desde cualquier capa sin
 * tener que regresar ApiResponse.fail(...) manualmente en cada paso.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
