package com.emify.barbershop;

import com.emify.common.ApiException;
import com.emify.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Permisos sobre una sucursal: antes cualquier usuario logeado podía editar servicios de
 * cualquier sucursal con solo cambiar el id en la URL.
 */
@Service
@RequiredArgsConstructor
public class LocationAccessService {

    private final BarbershopLocationRepository locationRepository;
    private final BarbershopStaffRepository staffRepository;

    /** Sucursal (no borrada) cuyo dueño es el usuario. */
    public BarbershopLocation requireOwner(User user, Long locationId) {
        return locationRepository.findByIdAndOwnerId(locationId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Sucursal no encontrada"));
    }

    /** El dueño o alguien activo del equipo de esa sucursal (p. ej. el barbero viendo su calendario). */
    public BarbershopLocation requireOwnerOrStaff(User user, Long locationId) {
        Optional<BarbershopLocation> owned = locationRepository.findByIdAndOwnerId(locationId, user.getId());
        if (owned.isPresent()) {
            return owned.get();
        }
        return staffRepository.findByUserIdAndLocationId(user.getId(), locationId)
                .filter(BarbershopStaff::isActive)
                .map(BarbershopStaff::getLocation)
                .filter(location -> location.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Sucursal no encontrada"));
    }

    public boolean isOwner(User user, BarbershopLocation location) {
        return location.getBarbershop().getOwner().getId().equals(user.getId());
    }
}
