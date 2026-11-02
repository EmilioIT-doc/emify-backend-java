package com.emify.common;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Sirve las imágenes guardadas por {@link FileStorageService} en /uploads/**. */
@Configuration
@RequiredArgsConstructor
public class UploadsWebConfig implements WebMvcConfigurer {

    private final FileStorageService storage;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!storage.isEnabled()) {
            return;
        }
        String location = storage.getBaseDir().toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
