package com.emify.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendVerificationCodeRequest {

    @NotBlank(message = "El email es requerido")
    @Email(message = "Email inválido")
    private String email;
}