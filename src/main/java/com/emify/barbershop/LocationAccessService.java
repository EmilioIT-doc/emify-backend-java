package com.emify.barbershop;

import com.emify.common.ApiException;
import com.emify.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Permisos sobre una sucursal (antes cualquier usuario logeado podía editar servicios de cualquier sucursal
 * con solo cambiar el id en la URL).
 *
 * Niveles (V11):
 * - OWNER: el dueño del negocio. Todo, en todas sus sucursales.
 * - RECEPTIONIST: personal activo marcado como recepcionista de ESA sucursal (lo elige el dueño). Maneja
 *   calendario general, productos, catálogo, clientes e inicio (métricas) de su sucursal. No toca equipo,
 *   ajustes, horario ni sucursales.
 * - STAFF: barbero activo de la sucursal. Su calendario (y el del equipo sin datos privados), y en solo lectura
 *   productos, catálogo y clientes de la sucursal.
 *
 * Negocio suspendido por el super admin: se puede leer, pero cualquier escritura responde 423 BUSINESS_SUSPENDED
 * (los métodos *ForWrite y {@link #ensureWritable}).
 */
@Service
@RequiredArgsConstructor
public class LocationAccessService {

    public static final String BUSINESS_SUSPENDED = "BUSINESS_SUSPENDED";

    private final BarbershopLocationRepository locationRepository;
    private final BarbershopStaffRepository staffRepository;

    public enum Level {
        OWNER, RECEPTIONIST, STAFF
    }

    /** Quién es el usuario en esta sucursal. {@code membership} = su registro de personal activo (null si no atiende). */
    public record LocationAccess(BarbershopLocation location, Level level, BarbershopStaff membership) {
        public boolean isOwner() {
            return level == Level.OWNER;
        }

        /** Dueño o recepcionista: calendario completo, productos, catálogo y métricas. */
        public boolean canManage() {
            return level == Level.OWNER || level == Level.RECEPTIONIST;
        }

        public boolean suspended() {
            return location.getBarbershop().isSuspended();
        }
    }

    /** Dueño o personal activo de la sucursal (no borrada); si no, 404 (no revela que existe). */
    public LocationAccess resolve(User user, Long locationId) {
        Optional<BarbershopStaff> membership = staffRepository.findByUserIdAndLocationId(user.getId(), locationId)
                .filter(BarbershopStaff::isActive)
                .filter(s -> s.getLocation().getDeletedAt() == null);
        Optional<BarbershopLocation> owned = locationRepository.findByIdAndOwnerId(locationId, user.getId());
        if (owned.isPresent()) {
            return new LocationAccess(owned.get(), Level.OWNER, membership.orElse(null));
        }
        BarbershopStaff staff = membership.orElseThrow(() -> ApiException.notFound("Sucursal no encontrada"));
        return new LocationAccess(staff.getLocation(), staff.isReceptionist() ? Level.RECEPTIONIST : Level.STAFF, staff);
    }

    /** Sucursal (no borrada) cuyo dueño es el usuario. Solo lectura: no revisa la suspensión. */
    public BarbershopLocation requireOwner(User user, Long locationId) {
        return locationRepository.findByIdAndOwnerId(locationId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Sucursal no encontrada"));
    }

    /** Como {@link #requireOwner} y además el negocio no está suspendido (para modificar). */
    public BarbershopLocation requireOwnerForWrite(User user, Long locationId) {
        BarbershopLocation location = requireOwner(user, locationId);
        ensureWritable(location.getBarbershop());
        return location;
    }

    /** El dueño o alguien activo del equipo de esa sucursal (p. ej. el barbero viendo su calendario). */
    public BarbershopLocation requireOwnerOrStaff(User user, Long locationId) {
        return resolve(user, locationId).location();
    }

    /** Dueño o recepcionista de la sucursal (lectura). El barbero recibe 403. */
    public BarbershopLocation requireManager(User user, Long locationId) {
        LocationAccess access = resolve(user, locationId);
        if (!access.canManage()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE",
                    "Solo el dueño o la recepción de la sucursal pueden hacer esto");
        }
        return access.location();
    }

    /** Dueño o recepcionista y el negocio no está suspendido (para modificar productos, catálogo, citas…). */
    public BarbershopLocation requireManagerForWrite(User user, Long locationId) {
        BarbershopLocation location = requireManager(user, locationId);
        ensureWritable(location.getBarbershop());
        return location;
    }

    public boolean isOwner(User user, BarbershopLocation location) {
        return location.getBarbershop().getOwner().getId().equals(user.getId());
    }

    /** Dueño o recepcionista activo de esa sucursal (sin lanzar). */
    public boolean canManage(User user, BarbershopLocation location) {
        if (isOwner(user, location)) return true;
        return staffRepository.findByUserIdAndLocationId(user.getId(), location.getId())
                .filter(BarbershopStaff::isActive)
                .map(BarbershopStaff::isReceptionist)
                .orElse(false);
    }

    /** Negocio suspendido por el super admin → 423: el panel queda en solo lectura. */
    public void ensureWritable(Barbershop barbershop) {
        if (barbershop.isSuspended()) {
            throw new ApiException(HttpStatus.LOCKED, BUSINESS_SUSPENDED,
                    "Este negocio está suspendido: el panel está en solo lectura. Escríbenos para reactivarlo.");
        }
    }
}
