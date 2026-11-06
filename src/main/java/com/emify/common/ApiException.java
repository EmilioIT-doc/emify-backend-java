package com.emify.common;

import org.springframework.http.HttpStatus;

/**
 * Error de negocio que se convierte en {@code {status:false, message}} con el código HTTP indicado
 * (ver {@link GlobalExceptionHandler}). Sirve para cortar el flujo desde cualquier capa sin
 * tener que regresar ApiResponse.fail(...) manualmente en cada paso.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    /** Código estable para el frontend (p. ej. TOO_MANY_ATTEMPTS, INVALID_CREDENTIALS); puede ser null. */
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, null, message);
    }

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
