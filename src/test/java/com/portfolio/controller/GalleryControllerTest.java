package com.portfolio.controller;

import com.portfolio.service.GalleryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GalleryControllerTest {
    @Test void returnsPhotosAndSanitizesFailures() throws Exception {
        var service = mock(GalleryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GalleryController(service)).build();
        when(service.photos()).thenReturn(List.of(new GalleryService.Photo("one", "/one.jpg", "Lake", 10, 20)));
        mvc.perform(get("/api/gallery")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].alt").value("Lake"))
            .andExpect(header().string("Cache-Control", "no-store"));
        when(service.photos()).thenThrow(new IllegalStateException("private provider details"));
        mvc.perform(get("/api/gallery")).andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "30"))
            .andExpect(jsonPath("$.message").value("The gallery is temporarily unavailable. Please try again shortly."));
    }
}
