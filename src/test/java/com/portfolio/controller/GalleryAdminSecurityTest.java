package com.portfolio.controller;

import com.portfolio.config.GallerySecurityConfig;
import com.portfolio.service.GalleryUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GalleryAdminController.class)
@Import(GallerySecurityConfig.class)
@TestPropertySource(properties = "GALLERY_ADMIN_PASSWORD=test-password-for-gallery")
class GalleryAdminSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean GalleryUploadService uploads;
    private final MockMultipartFile image = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2});

    @Test void anonymousVisitorsCannotUpload() throws Exception {
        mvc.perform(multipart("/api/admin/photos").file(image).param("alt", "Lake").with(csrf()))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(uploads);
    }

    @Test void sessionLoginUploadAndLogout() throws Exception {
        mvc.perform(get("/api/admin/session")).andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false)).andExpect(jsonPath("$.csrfToken").isNotEmpty());
        mvc.perform(post("/api/admin/login").param("username", "admin").param("password", "wrong").with(csrf()))
            .andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/admin/login").param("username", "admin")
            .param("password", "test-password-for-gallery").with(csrf()))
            .andExpect(status().isNoContent()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/admin/session").session(session)).andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(multipart("/api/admin/photos").file(image).param("alt", "Lake").session(session))
            .andExpect(status().isForbidden());
        verifyNoInteractions(uploads);
        when(uploads.upload(any(), eq("Lake"), isNull())).thenReturn("portfolio/photography/example");
        mvc.perform(multipart("/api/admin/photos").file(image).param("alt", "Lake").session(session).with(csrf()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("Photo published."));
        mvc.perform(post("/api/admin/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/session")).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test void duplicateUploadReturnsConflictMessage() throws Exception {
        when(uploads.upload(any(), anyString(), any())).thenThrow(new GalleryUploadService.DuplicatePhotoException());
        mvc.perform(multipart("/api/admin/photos").file(image)
            .with(user("admin").roles("GALLERY_ADMIN")).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_IN_GALLERY"))
            .andExpect(jsonPath("$.message").value("Already in gallery. This photo has already been uploaded."));
    }

    @Test void loginRequiresCsrfAndProviderErrorsAreNotExposed() throws Exception {
        mvc.perform(post("/api/admin/login").param("username", "admin").param("password", "test-password-for-gallery"))
            .andExpect(status().isForbidden());
        when(uploads.upload(any(), anyString(), any())).thenThrow(new RuntimeException("secret provider response"));
        mvc.perform(multipart("/api/admin/photos").file(image).param("alt", "Lake")
            .with(user("admin").roles("GALLERY_ADMIN")).with(csrf()))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.message").value("Cloudinary could not confirm the upload. Check the gallery before retrying."));
    }
}
