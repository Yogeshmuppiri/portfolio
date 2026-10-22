package com.portfolio.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GalleryUploadServiceTest {
    private static final String LIST_URL = "https://api.cloudinary.com/v1_1/bfj0lu5i/resources/image/tags/portfolio-photography?max_results=500";
    private static final byte[] PHOTO = {(byte)255, (byte)216, (byte)255, 1};
    private final GalleryService gallery = mock(GalleryService.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GalleryUploadService uploads = new GalleryUploadService("bfj0lu5i", "key", "secret",
        "portfolio-photography", gallery, builder.build());

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"   ", "Lake|sunset"})
    void signedUploadAutomaticallyPublishesAndInvalidatesCache(String description) throws Exception {
        server.expect(requestTo(LIST_URL)).andRespond(withSuccess("{\"resources\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.cloudinary.com/v1_1/bfj0lu5i/image/upload"))
            .andRespond(request -> {
                String body = ((MockClientHttpRequest) request).getBodyAsString();
                var matcher = Pattern.compile("Content-Disposition: form-data; name=\"([^\"]+)\"\\r\\n(?:[^\\r]+\\r\\n)*\\r\\n([^\\r]*)").matcher(body);
                var fields = new TreeMap<String, String>();
                while (matcher.find()) fields.put(matcher.group(1), matcher.group(2));
                assertThat(fields.remove("api_key")).isEqualTo("key");
                String signature = fields.remove("signature");
                assertThat(fields.get("tags")).isEqualTo("portfolio-photography");
                String expectedAlt = description == null || description.isBlank()
                    ? "Photography by Yogesh Muppiri" : "Lake\\|sunset";
                assertThat(fields.get("context")).isEqualTo("alt=" + expectedAlt + "|gallery_order=5");
                assertThat(fields.get("overwrite")).isEqualTo("false");
                try {
                    assertThat(fields.get("public_id")).isEqualTo("portfolio/photography/sha256_"
                        + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PHOTO)));
                } catch (java.security.NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
                try {
                    String signed = fields.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("&")) + "secret";
                    assertThat(signature).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                        .digest(signed.getBytes(StandardCharsets.UTF_8))));
                } catch (java.security.NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
                var response = new MockClientHttpResponse(("{\"public_id\":\"" + fields.get("public_id") + "\"}")
                    .getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return response;
            });
        var file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", PHOTO);
        assertThat(uploads.upload(file, description, 5)).startsWith("portfolio/photography/");
        verify(gallery).invalidate();
        server.verify();
    }

    @Test void rejectsRenamedLegacyPhotoOnLaterPageBeforeUploading() throws Exception {
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(PHOTO));
        server.expect(requestTo(LIST_URL)).andRespond(withSuccess("{\"resources\":[],\"next_cursor\":\"page2\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(LIST_URL + "&next_cursor=page2")).andRespond(withSuccess("""
            {"resources":[{"public_id":"portfolio/photography/old-random-id","asset_id":"legacy-id",
            "version":1,"type":"upload","resource_type":"image"}]}
            """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.cloudinary.com/v1_1/bfj0lu5i/resources/legacy-id?media_metadata=true"))
            .andExpect(header("Authorization", "Basic a2V5OnNlY3JldA=="))
            .andRespond(withSuccess("{\"etag\":\"" + md5 + "\"}", MediaType.APPLICATION_JSON));
        var file = new MockMultipartFile("file", "renamed.jpg", "image/jpeg", PHOTO);
        assertThatThrownBy(() -> uploads.upload(file, "New description", null))
            .isInstanceOf(GalleryUploadService.DuplicatePhotoException.class).hasMessageContaining("Already in gallery");
        verifyNoInteractions(gallery);
        server.verify();
    }

    @Test void duplicateCreateResponseHandlesConcurrentUploads() throws Exception {
        String id = "portfolio/photography/sha256_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PHOTO));
        server.expect(requestTo(LIST_URL)).andRespond(withSuccess("{\"resources\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.cloudinary.com/v1_1/bfj0lu5i/image/upload"))
            .andRespond(withSuccess("{\"public_id\":\"" + id + "\",\"existing\":true}", MediaType.APPLICATION_JSON));
        var file = new MockMultipartFile("file", "renamed.jpg", "image/jpeg", PHOTO);
        assertThatThrownBy(() -> uploads.upload(file, "", null)).isInstanceOf(GalleryUploadService.DuplicatePhotoException.class);
        verifyNoInteractions(gallery);
        server.verify();
    }

    @Test void hashBasedExistingPhotoIsRejectedWithoutMetadataLookup() throws Exception {
        String id = "portfolio/photography/sha256_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PHOTO));
        server.expect(requestTo(LIST_URL)).andRespond(withSuccess("{\"resources\":[{\"public_id\":\"" + id
            + "\",\"type\":\"upload\",\"resource_type\":\"image\"}]}", MediaType.APPLICATION_JSON));
        var file = new MockMultipartFile("file", "again.jpg", "image/jpeg", PHOTO);
        assertThatThrownBy(() -> uploads.upload(file, "", null)).isInstanceOf(GalleryUploadService.DuplicatePhotoException.class);
        server.verify();
    }

    @Test void failedDuplicateCheckDoesNotUpload() {
        server.expect(requestTo(LIST_URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", PHOTO);
        assertThatThrownBy(() -> uploads.upload(file, "", null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(gallery);
        server.verify();
    }

    @Test void rejectsUnsupportedContentAndOverlongDescriptionsBeforeUploading() {
        var fake = new MockMultipartFile("file", "fake.jpg", "image/jpeg", "not an image".getBytes());
        assertThatThrownBy(() -> uploads.upload(fake, "Lake", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> uploads.upload(fake, "x".repeat(501), null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gallery);
        server.verify();
    }
}
