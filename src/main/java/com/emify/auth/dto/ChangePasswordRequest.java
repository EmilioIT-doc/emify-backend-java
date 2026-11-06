package com.emify.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Escribe tu contraseña actual")
    @JsonProperty("current_password")
    private String currentPassword;

    @NotBlank(message = "Escribe la contraseña nueva")
    @JsonProperty("new_password")
    private String newPassword;
}
