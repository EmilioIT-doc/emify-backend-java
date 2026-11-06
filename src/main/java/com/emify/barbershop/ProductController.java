package com.emify.barbershop;

import com.emify.auth.dto.ApiResponse;
import com.emify.common.ApiException;
import com.emify.common.CurrentUser;
import com.emify.common.FileStorageService;
import com.emify.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Productos de una sucursal (pestaña "Products" del dashboard).
 * El frontend manda multipart/form-data: name, description, price, is_active (1/0), image (opcional).
 */
@RestController
@RequestMapping("/api/authBarberShop/location/{locationId}/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;
    private final LocationAccessService access;
    private final FileStorageService storage;

    @GetMapping
    public ResponseEntity<ApiResponse<?>> list(@PathVariable Long locationId, Authentication authentication) {
        access.requireOwnerOrStaff(CurrentUser.require(authentication), locationId);
        var products = productRepository.findByLocationIdOrderByCreatedAtDesc(locationId).stream()
                .map(this::toMap)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok("Productos", Map.of("products", products)));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<?>> create(@PathVariable Long locationId,
                                                 @RequestParam String name,
                                                 @RequestParam(required = false) String description,
                                                 @RequestParam String price,
                                                 @RequestParam(name = "is_active", required = false) String isActive,
                                                 @RequestParam(required = false) MultipartFile image,
                                                 Authentication authentication) {
        BarbershopLocation location = access.requireManagerForWrite(CurrentUser.require(authentication), locationId);
        Product product = Product.builder()
                .location(location)
                .name(requireName(name))
                .description(description)
                .price(parsePrice(price))
                .isActive(parseActive(isActive, true))
                .imageUrl(storage.storeImage(image, "products"))
                .build();
        productRepository.save(product);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Producto creado", Map.of("product", toMap(product))));
    }

    @PostMapping("/{productId}")
    @Transactional
    public ResponseEntity<ApiResponse<?>> update(@PathVariable Long locationId,
                                                 @PathVariable Long productId,
                                                 @RequestParam(required = false) String name,
                                                 @RequestParam(required = false) String description,
                                                 @RequestParam(required = false) String price,
                                                 @RequestParam(name = "is_active", required = false) String isActive,
                                                 @RequestParam(required = false) MultipartFile image,
                                                 Authentication authentication) {
        access.requireManagerForWrite(CurrentUser.require(authentication), locationId);
        Product product = productRepository.findByIdAndLocationId(productId, locationId)
                .orElseThrow(() -> ApiException.notFound("Producto no encontrado"));

        if (name != null) product.setName(requireName(name));
        if (description != null) product.setDescription(description);
        if (price != null) product.setPrice(parsePrice(price));
        if (isActive != null) product.setActive(parseActive(isActive, product.isActive()));
        String imageUrl = storage.storeImage(image, "products");
        if (imageUrl != null) product.setImageUrl(imageUrl);

        productRepository.save(product);
        return ResponseEntity.ok(ApiResponse.ok("Producto actualizado", Map.of("product", toMap(product))));
    }

    @PostMapping("/{productId}/delete")
    @Transactional
    public ResponseEntity<ApiResponse<?>> delete(@PathVariable Long locationId,
                                                 @PathVariable Long productId,
                                                 Authentication authentication) {
        access.requireManagerForWrite(CurrentUser.require(authentication), locationId);
        Product product = productRepository.findByIdAndLocationId(productId, locationId)
                .orElseThrow(() -> ApiException.notFound("Producto no encontrado"));
        productRepository.delete(product);
        return ResponseEntity.ok(ApiResponse.ok("Producto eliminado", null));
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) throw ApiException.unprocessable("El nombre es requerido");
        return name.trim();
    }

    private BigDecimal parsePrice(String price) {
        try {
            BigDecimal value = new BigDecimal(price.trim());
            if (value.signum() < 0) throw ApiException.unprocessable("El precio debe ser mayor o igual a 0");
            return value;
        } catch (NumberFormatException | NullPointerException e) {
            throw ApiException.unprocessable("Precio inválido");
        }
    }

    private boolean parseActive(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        return value.equals("1") || value.equalsIgnoreCase("true");
    }

    private Map<String, Object> toMap(Product p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("location_id", p.getLocation().getId());
        map.put("name", p.getName());
        map.put("description", p.getDescription() != null ? p.getDescription() : "");
        map.put("price", p.getPrice());
        map.put("image_url", p.getImageUrl());
        map.put("is_active", p.isActive());
        return map;
    }
}
