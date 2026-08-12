package com.emify.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateRoleRequest {

    @NotBlank(message = "El rol es requerido")
    @Pattern(regexp = "client|owner", message = "El rol debe ser client u owner")
    private String role;
}