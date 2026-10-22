package com.portfolio.controller;

import com.portfolio.service.GalleryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class GalleryController {
    private final GalleryService gallery;

    public GalleryController(GalleryService gallery) {
        this.gallery = gallery;
    }

    @GetMapping("/api/gallery")
    public ResponseEntity<?> photos() {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(gallery.photos());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .header("Retry-After", "30")
                .body(Map.of("message", "The gallery is temporarily unavailable. Please try again shortly."));
        }
    }
}
