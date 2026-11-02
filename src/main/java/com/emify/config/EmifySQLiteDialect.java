package com.emify.config;

import org.hibernate.boot.model.TypeContributions;
import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType;

/**
 * Dialecto SQLite para el perfil local "sqlite".
 *
 * Las entidades mapean los ENUM nativos de PostgreSQL con @JdbcTypeCode(SqlTypes.NAMED_ENUM)
 * (users.role, appointments.status, barbershop_staff.role, ...). SQLite no tiene ENUM y
 * Hibernate, al no encontrar un tipo para NAMED_ENUM, terminaría guardando el enum
 * serializado como bytes. Aquí se le dice que en SQLite un NAMED_ENUM es un VARCHAR con el
 * nombre del valor ('pending', 'owner', ...), que es justo lo que valida el CHECK del esquema
 * en db/sqlite. En PostgreSQL no cambia nada.
 */
public class EmifySQLiteDialect extends SQLiteDialect {

    public EmifySQLiteDialect() {
        super();
    }

    public EmifySQLiteDialect(DialectResolutionInfo info) {
        super(info);
    }

    public EmifySQLiteDialect(DatabaseVersion version) {
        super(version);
    }

    @Override
    public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
        super.contributeTypes(typeContributions, serviceRegistry);
        typeContributions.getTypeConfiguration()
                .getJdbcTypeRegistry()
                .addDescriptor(SqlTypes.NAMED_ENUM, VarcharJdbcType.INSTANCE);
    }
}
