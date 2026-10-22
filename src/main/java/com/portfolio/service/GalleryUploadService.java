package com.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GalleryUploadService {
    private final String cloud, key, secret, tag;
    private final RestClient client;
    private final GalleryService gallery;

    @org.springframework.beans.factory.annotation.Autowired
    public GalleryUploadService(@Value("${CLOUDINARY_CLOUD_NAME:}") String cloud,
                                @Value("${CLOUDINARY_API_KEY:}") String key,
                                @Value("${CLOUDINARY_API_SECRET:}") String secret,
                                @Value("${CLOUDINARY_GALLERY_TAG:portfolio-photography}") String tag,
                                GalleryService gallery) {
        this(cloud, key, secret, tag, gallery, createClient());
    }

    GalleryUploadService(String cloud, String key, String secret, String tag, GalleryService gallery, RestClient client) {
        this.cloud = cloud.trim(); this.key = key.trim(); this.secret = secret.trim();
        this.tag = tag.trim(); this.gallery = gallery; this.client = client;
    }

    private static RestClient createClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); factory.setReadTimeout(60000);
        return RestClient.builder().requestFactory(factory).build();
    }

    public String upload(MultipartFile file, String alt, Integer order) throws IOException {
        if (!cloud.matches("[a-zA-Z0-9_-]+") || key.isEmpty() || secret.isEmpty() || tag.isEmpty()) {
            throw new IllegalStateException("Configure Cloudinary credentials in Render before uploading.");
        }
        if (file.isEmpty() || file.getSize() > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("Choose an image between 1 byte and 20 MB.");
        }
        alt = alt == null ? "" : alt.trim();
        if (alt.length() > 500 || alt.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Photo descriptions must be at most 500 characters without line breaks.");
        }
        if (alt.isEmpty()) alt = "Photography by Yogesh Muppiri";
        byte[] bytes = file.getBytes();
        String extension = imageExtension(bytes);
        String publicId = "portfolio/photography/" + UUID.randomUUID();
        var params = new TreeMap<String, String>();
        params.put("public_id", publicId);
        params.put("timestamp", Long.toString(Instant.now().getEpochSecond()));
        params.put("tags", tag);
        params.put("overwrite", "false");
        params.put("allowed_formats", "jpg,png,webp");
        String context = "alt=" + alt.replace("\\", "\\\\").replace("=", "\\=").replace("|", "\\|");
        if (order != null) context += "|gallery_order=" + order;
        params.put("context", context);
        String signatureInput = params.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("&")) + secret;
        String signature;
        try {
            signature = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(signatureInput.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Upload signing unavailable");
        }
        var body = new LinkedMultiValueMap<String, Object>();
        params.forEach(body::add);
        body.add("api_key", key); body.add("signature", signature);
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "photo." + extension; }
        });
        JsonNode result = client.post().uri("https://api.cloudinary.com/v1_1/" + cloud + "/image/upload")
            .contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(JsonNode.class);
        if (result == null || !publicId.equals(result.path("public_id").asText())) {
            throw new IllegalStateException("Upload could not be confirmed. Check the gallery before retrying.");
        }
        gallery.invalidate();
        return publicId;
    }

    static String imageExtension(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) return "jpg";
        byte[] png = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length >= 8 && java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, 8), png)) return "png";
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
            && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "webp";
        throw new IllegalArgumentException("Choose a JPEG, PNG or WebP image. Convert HEIC images to JPEG first.");
    }
}
