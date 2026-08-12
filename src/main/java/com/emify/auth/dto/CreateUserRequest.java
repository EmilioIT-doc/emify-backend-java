package com.emify.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotBlank(message = "El nombre es requerido")
    private String name;

    @NotBlank(message = "El email es requerido")
    @Email(message = "Email inválido")
    private String email;

    @NotBlank(message = "El teléfono es requerido")
    private String phone;

    @NotBlank(message = "El código de país es requerido")
    @JsonProperty("phone_code")
    private String phoneCode;

    @NotBlank(message = "La contraseña es requerida")
    @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
    private String password;

    @NotBlank(message = "El código de verificación es requerido")
    @Size(min = 6, max = 6, message = "El código debe tener 6 dígitos")
    private String code;

    @AssertTrue(message = "Debes aceptar los términos")
    private boolean terms;
}