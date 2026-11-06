package com.emify.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean status;
    private String message;
    private T data;
    /** Código estable para el frontend en errores (SESSION_EXPIRED, TOO_MANY_ATTEMPTS…). Solo sale si hay. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;

    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .status(true)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> fail(String message) {
        return fail(null, message);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return ApiResponse.<T>builder()
                .status(false)
                .code(code)
                .message(message)
                .data(null)
                .build();
    }
}
