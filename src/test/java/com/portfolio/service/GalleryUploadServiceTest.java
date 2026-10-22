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

class GalleryUploadServiceTest {
    private final GalleryService gallery = mock(GalleryService.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GalleryUploadService uploads = new GalleryUploadService("bfj0lu5i", "key", "secret",
        "portfolio-photography", gallery, builder.build());

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"   ", "Lake|sunset"})
    void signedUploadAutomaticallyPublishesAndInvalidatesCache(String description) throws Exception {
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
        var file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{(byte)255, (byte)216, (byte)255, 1});
        assertThat(uploads.upload(file, description, 5)).startsWith("portfolio/photography/");
        verify(gallery).invalidate();
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
