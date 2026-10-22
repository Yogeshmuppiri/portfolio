package com.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GalleryManagementService {
    public record ManagedPhoto(String assetId, String id, String url, String alt) {}
    private final String cloud, key, secret, tag;
    private final RestClient client;
    private final GalleryService gallery;

    @org.springframework.beans.factory.annotation.Autowired
    public GalleryManagementService(@Value("${CLOUDINARY_CLOUD_NAME:}") String cloud,
                                    @Value("${CLOUDINARY_API_KEY:}") String key,
                                    @Value("${CLOUDINARY_API_SECRET:}") String secret,
                                    @Value("${CLOUDINARY_GALLERY_TAG:portfolio-photography}") String tag,
                                    GalleryService gallery) {
        this(cloud, key, secret, tag, gallery, createClient());
    }

    GalleryManagementService(String cloud, String key, String secret, String tag, GalleryService gallery, RestClient client) {
        this.cloud = cloud.trim(); this.key = key.trim(); this.secret = secret.trim();
        this.tag = tag.trim(); this.gallery = gallery; this.client = client;
    }

    private static RestClient createClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); factory.setReadTimeout(30000);
        return RestClient.builder().requestFactory(factory).build();
    }

    private void requireConfiguration() {
        if (!cloud.matches("[a-zA-Z0-9_-]+") || key.isEmpty() || secret.isEmpty() || tag.isEmpty()) {
            throw new IllegalStateException("Configure Cloudinary credentials in Render to manage published photos.");
        }
    }

    private UriComponentsBuilder api(String... segments) {
        return UriComponentsBuilder.fromHttpUrl("https://api.cloudinary.com")
            .pathSegment("v1_1", cloud).pathSegment(segments);
    }

    private JsonNode get(UriComponentsBuilder uri) {
        return client.get().uri(uri.build().encode().toUri())
            .headers(headers -> headers.setBasicAuth(key, secret)).retrieve().body(JsonNode.class);
    }

    public List<ManagedPhoto> photos() {
        requireConfiguration();
        Map<String, ManagedPhoto> photos = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = "";
        do {
            var uri = api("resources", "image", "tags", tag).queryParam("max_results", 500)
                .queryParam("context", true).queryParam("direction", "desc");
            if (!cursor.isEmpty()) uri.queryParam("next_cursor", cursor);
            JsonNode body = get(uri);
            if (body == null || !body.path("resources").isArray()) throw new IllegalStateException("Could not load published photos.");
            for (JsonNode photo : body.path("resources")) {
                if (!"upload".equals(photo.path("type").asText()) || !"image".equals(photo.path("resource_type").asText())) continue;
                String id = photo.path("public_id").asText();
                String assetId = photo.path("asset_id").asText();
                long version = photo.path("version").asLong();
                if (id.isBlank() || assetId.isBlank() || version <= 0) continue;
                String encoded = Arrays.stream(id.split("/", -1))
                    .map(part -> UriUtils.encodePathSegment(part, StandardCharsets.UTF_8)).collect(Collectors.joining("/"));
                String alt = photo.path("context").path("custom").path("alt").asText("Photography by Yogesh Muppiri");
                photos.put(assetId, new ManagedPhoto(assetId, id,
                    "https://res.cloudinary.com/" + cloud + "/image/upload/f_auto,q_auto,c_limit,w_600/v" + version + "/" + encoded, alt));
            }
            cursor = body.path("next_cursor").asText("");
            if (!cursor.isEmpty() && (!cursors.add(cursor) || cursors.size() > 100)) throw new IllegalStateException("Could not finish loading published photos.");
        } while (!cursor.isEmpty());
        return List.copyOf(photos.values());
    }

    public synchronized void delete(String assetId) {
        requireConfiguration();
        if (assetId == null || !assetId.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid photo identifier.");
        JsonNode photo = get(api("resources", assetId).queryParam("versions", true));
        boolean tagged = false;
        if (photo != null) for (JsonNode value : photo.path("tags")) tagged |= tag.equals(value.asText());
        if (photo == null || !assetId.equals(photo.path("asset_id").asText()) || !tagged
            || !"image".equals(photo.path("resource_type").asText()) || !"upload".equals(photo.path("type").asText())) {
            throw new IllegalArgumentException("This photo does not belong to the public photography gallery.");
        }
        String publicId = photo.path("public_id").asText();
        if (publicId.isBlank()) throw new IllegalStateException("Could not verify the photo to delete.");
        // Remove backups first so a backup API failure leaves the active image available to retry.
        List<String> versions = backupVersions(photo);
        for (int start = 0; start < versions.size(); start += 100) {
            var uri = api("resources", "backup", assetId)
                .queryParam("version_ids[]", versions.subList(start, Math.min(start + 100, versions.size())).toArray());
            client.delete().uri(uri.build().encode().toUri()).headers(headers -> headers.setBasicAuth(key, secret))
                .retrieve().toBodilessEntity();
        }
        if (!versions.isEmpty()) {
            JsonNode verified = get(api("resources", assetId).queryParam("versions", true));
            if (verified == null || !assetId.equals(verified.path("asset_id").asText()) || !backupVersions(verified).isEmpty()) {
                throw new IllegalStateException("Backup deletion could not be confirmed. Retry deletion to finish removing this photo.");
            }
        }
        var params = new TreeMap<String, String>();
        params.put("asset_id", assetId);
        params.put("invalidate", "true");
        params.put("timestamp", Long.toString(Instant.now().getEpochSecond()));
        String signatureInput = params.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("&")) + secret;
        try {
            params.put("signature", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(signatureInput.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("Deletion signing unavailable."); }
        params.put("api_key", key);
        var form = new LinkedMultiValueMap<String, String>();
        params.forEach(form::add);
        try {
            JsonNode result = client.post().uri(api("image", "destroy").build().encode().toUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
            if (result == null || !Set.of("ok", "not found").contains(result.path("result").asText())) {
                throw new IllegalStateException("Deletion could not be confirmed. Refresh the list before retrying.");
            }
            gallery.remove(publicId);
        } finally {
            // A lost response may still mean Cloudinary deleted it. Force the next list to refresh.
            gallery.invalidate();
        }
    }

    private List<String> backupVersions(JsonNode photo) {
        JsonNode versions = photo.path("versions");
        if (versions.isMissingNode() && photo.path("backup_bytes").asLong() > 0) {
            throw new IllegalStateException("Could not verify photo backups.");
        }
        if (!versions.isMissingNode() && !versions.isArray()) throw new IllegalStateException("Could not verify photo backups.");
        var ids = new ArrayList<String>();
        for (JsonNode version : versions) {
            String id = version.path("version_id").asText();
            if (id.isBlank()) throw new IllegalStateException("Could not verify photo backups.");
            ids.add(id);
        }
        return ids;
    }
}
