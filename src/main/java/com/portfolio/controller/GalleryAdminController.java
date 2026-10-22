package com.portfolio.controller;

import com.portfolio.service.GalleryUploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class GalleryAdminController {
    private final GalleryUploadService uploads;
    public GalleryAdminController(GalleryUploadService uploads) { this.uploads = uploads; }

    @GetMapping("/session")
    public Map<String, Object> session(Authentication authentication, CsrfToken csrf) {
        boolean loggedIn = authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_GALLERY_ADMIN"));
        return Map.of("authenticated", loggedIn, "csrfToken", csrf.getToken(), "csrfHeader", csrf.getHeaderName());
    }

    @PostMapping(value = "/photos", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "alt", defaultValue = "") String alt,
                                    @RequestParam(value = "order", required = false) Integer order) {
        try {
            return ResponseEntity.status(201).body(Map.of("id", uploads.upload(file, alt, order), "message", "Photo published."));
        } catch (GalleryUploadService.DuplicatePhotoException ex) {
            return ResponseEntity.status(409).body(Map.of("code", "ALREADY_IN_GALLERY", "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(503).body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(502).body(Map.of("message", "Cloudinary could not confirm the upload. Check the gallery before retrying."));
        }
    }
}
