package com.emify.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Guarda imágenes en disco (emify.uploads.dir) y las expone en /uploads/**.
 * Es para pruebas locales mientras no haya S3: si está apagado o algo falla, regresa null
 * y el flujo sigue sin imagen (nunca debe tumbar una reserva).
 */
@Slf4j
@Service
public class FileStorageService {

    private final boolean enabled;
    private final Path baseDir;

    public FileStorageService(@Value("${emify.uploads.enabled:false}") boolean enabled,
                              @Value("${emify.uploads.dir:./uploads}") String dir) {
        this.enabled = enabled;
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Path getBaseDir() {
        return baseDir;
    }

    /** @return URL pública de la imagen, o null si no se guardó. */
    public String storeImage(MultipartFile file, String folder) {
        if (!enabled || file == null || file.isEmpty()) {
            return null;
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            log.warn("Archivo ignorado: no es una imagen ({})", contentType);
            return null;
        }
        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
        try {
            Path dir = baseDir.resolve(folder);
            Files.createDirectories(dir);
            String filename = UUID.randomUUID().toString().replace("-", "") + extension;
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            }
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/uploads/{folder}/{file}")
                    .buildAndExpand(folder, filename)
                    .toUriString();
        } catch (Exception e) {
            log.warn("No se pudo guardar la imagen: {}", e.getMessage());
            return null;
        }
    }
}
