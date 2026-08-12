package com.emify.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @NotBlank(message = "El nombre es requerido")
    private String name;

    @NotBlank(message = "El teléfono es requerido")
    private String phone;

    // Opcional — si se manda, debe ser uno de los avatares disponibles (ver AuthService.AVATARS).
    // El email NUNCA se recibe aquí a propósito: no es editable.
    @JsonProperty("avatar_url")
    private String avatarUrl;
}
