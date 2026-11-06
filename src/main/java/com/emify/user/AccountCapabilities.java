package com.emify.user;

import com.emify.barbershop.BarbershopRepository;
import com.emify.barbershop.BarbershopStaff;
import com.emify.barbershop.BarbershopStaffRepository;
import com.emify.common.ApiException;
import com.emify.restaurant.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Qué puede hacer una cuenta según lo que ES (no según users.role, que solo dice cómo se registró).
 *
 * Agregar un negocio (decidido con Emilio, 5-nov-2026): solo el dueño (ya tiene alguno) o quien usa emify para agendar
 * citas. El personal (barbero o recepción en el negocio de otro dueño) NO: para abrir su propio negocio tiene que dejar
 * de ser personal (el dueño lo da de baja) o usar otra cuenta.
 */
@Service
@RequiredArgsConstructor
public class AccountCapabilities {

    public static final String STAFF_CANNOT_CREATE_BUSINESS = "STAFF_CANNOT_CREATE_BUSINESS";

    private final BarbershopRepository barbershopRepository;
    private final RestaurantRepository restaurantRepository;
    private final BarbershopStaffRepository staffRepository;

    public boolean ownsAnyBusiness(User user) {
        return !barbershopRepository.findByOwnerIdOrderByIdAsc(user.getId()).isEmpty()
                || !restaurantRepository.findByOwnerIdOrderByIdAsc(user.getId()).isEmpty();
    }

    /** Personal activo en una sucursal (no borrada) de un negocio que no es suyo. */
    public boolean worksForOthers(User user) {
        return staffRepository.findByUserIdAndIsActiveTrueOrderByIdAsc(user.getId()).stream()
                .map(BarbershopStaff::getLocation)
                .anyMatch(l -> l != null && l.getDeletedAt() == null
                        && !l.getBarbershop().getOwner().getId().equals(user.getId()));
    }

    public boolean canCreateBusiness(User user) {
        return ownsAnyBusiness(user) || !worksForOthers(user);
    }

    public void requireCanCreateBusiness(User user) {
        if (!canCreateBusiness(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, STAFF_CANNOT_CREATE_BUSINESS,
                    "Tu cuenta es de personal de un negocio, así que no puede agregar negocios. Para abrir el tuyo usa otra cuenta.");
        }
    }
}
