# -------------------------------------------------------
# Stage 1: Build
# -------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copiar pom.xml primero para cachear dependencias
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copiar el resto del código y compilar
COPY src ./src
RUN mvn clean package -DskipTests -B

# -------------------------------------------------------
# Stage 2: Runtime
# -------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copiar el JAR del stage anterior
COPY --from=builder /app/target/*.jar app.jar

# Puerto que expone Spring Boot
EXPOSE 8080

# Variables de entorno con defaults
ENV SPRING_PROFILES_ACTIVE=prod
ENV DB_USERNAME=postgres
ENV DB_PASSWORD=postgres123
ENV JWT_SECRET=emify-super-secret-key-para-jwt-2026

# Arrancar la app
ENTRYPOINT ["java", "-jar", "app.jar"]