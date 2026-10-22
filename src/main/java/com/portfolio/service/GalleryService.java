package com.portfolio.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class GalleryService {
    public record Photo(String id, String url, String alt, int width, int height) {}
    private record Entry(Photo photo, double order, String created) {}
    private final String cloud;
    private final String key;
    private final String secret;
    private final String tag;
    private final RestClient client;
    private final Clock clock;
    private final List<Photo> localPhotos;
    private List<Photo> cached;
    private Instant refreshAt = Instant.MIN;
    private Instant lastSuccess = Instant.MIN;

    @org.springframework.beans.factory.annotation.Autowired
    public GalleryService(@Value("${CLOUDINARY_CLOUD_NAME:}") String cloud,
                          @Value("${CLOUDINARY_API_KEY:}") String key,
                          @Value("${CLOUDINARY_API_SECRET:}") String secret,
                          @Value("${CLOUDINARY_GALLERY_TAG:portfolio-photography}") String tag,
                          ObjectMapper mapper) throws IOException {
        this(cloud, key, secret, tag, createClient(), Clock.systemUTC(), readLocal(mapper));
    }

    GalleryService(String cloud, String key, String secret, String tag,
                   RestClient client, Clock clock, List<Photo> localPhotos) {
        this.cloud = cloud.trim();
        this.key = key.trim();
        this.secret = secret.trim();
        this.tag = tag.trim();
        this.client = client;
        this.clock = clock;
        this.localPhotos = List.copyOf(localPhotos);
    }

    private static RestClient createClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return RestClient.builder().requestFactory(factory).build();
    }

    private static List<Photo> readLocal(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("photography-seed.json").getInputStream()) {
            return mapper.readValue(stream, new TypeReference<List<Photo>>() {});
        }
    }

    // One refresh at a time; visitors share the cache instead of exhausting Admin API quotas.
    public synchronized List<Photo> photos() {
        if (cloud.isEmpty() && key.isEmpty() && secret.isEmpty()) return localPhotos;
        if (!cloud.matches("[a-zA-Z0-9_-]+") || key.isEmpty() || secret.isEmpty() || tag.isEmpty()) {
            throw new IllegalStateException("Cloudinary configuration is incomplete");
        }
        Instant now = clock.instant();
        if (now.isBefore(refreshAt)) return cachedOrThrow(now);
        try {
            List<Photo> fresh = fetchPhotos();
            cached = fresh;
            lastSuccess = clock.instant();
            refreshAt = lastSuccess.plusSeconds(300);
            return cached;
        } catch (RuntimeException ex) {
            refreshAt = clock.instant().plusSeconds(30);
            // Never log the upstream body or authorization credentials.
            LoggerFactory.getLogger(GalleryService.class).warn("Cloudinary gallery refresh failed ({})", ex.getClass().getSimpleName());
            return cachedOrThrow(clock.instant());
        }
    }

    public synchronized void invalidate() {
        refreshAt = Instant.MIN;
    }

    public synchronized void remove(String id) {
        if (cached != null) cached = cached.stream().filter(photo -> !photo.id().equals(id)).toList();
        refreshAt = Instant.MIN;
    }

    private List<Photo> cachedOrThrow(Instant now) {
        if (cached != null && now.isBefore(lastSuccess.plusSeconds(3600))) return cached;
        throw new IllegalStateException("Gallery is temporarily unavailable");
    }

    private List<Photo> fetchPhotos() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = "";
        do {
            var uri = UriComponentsBuilder.fromHttpUrl("https://api.cloudinary.com")
                .pathSegment("v1_1", cloud, "resources", "image", "tags", tag)
                .queryParam("max_results", 500).queryParam("context", true)
                .queryParam("direction", "desc");
            if (!cursor.isEmpty()) uri.queryParam("next_cursor", cursor);
            JsonNode body = client.get().uri(uri.build().encode().toUri())
                .headers(headers -> headers.setBasicAuth(key, secret))
                .retrieve().body(JsonNode.class);
            if (body == null || !body.path("resources").isArray()) {
                throw new IllegalStateException("Invalid Cloudinary response");
            }
            for (JsonNode resource : body.path("resources")) {
                // Tagged private/authenticated assets must never appear in the public gallery.
                if (!"upload".equals(resource.path("type").asText())
                    || !"image".equals(resource.path("resource_type").asText())) continue;
                String id = resource.path("public_id").asText();
                long version = resource.path("version").asLong();
                if (id.isBlank() || version <= 0) continue;
                JsonNode context = resource.path("context").path("custom");
                String alt = context.path("alt").asText("").trim();
                if (alt.isEmpty()) alt = context.path("caption").asText("").trim();
                if (alt.isEmpty()) alt = "Photography by Yogesh Muppiri";
                String encoded = Arrays.stream(id.split("/", -1))
                    .map(part -> UriUtils.encodePathSegment(part, StandardCharsets.UTF_8))
                    .collect(java.util.stream.Collectors.joining("/"));
                String url = "https://res.cloudinary.com/" + cloud
                    + "/image/upload/f_auto,q_auto,c_limit,w_1600/v" + version + "/" + encoded;
                Photo photo = new Photo(id, url, alt, resource.path("width").asInt(), resource.path("height").asInt());
                double order = Double.POSITIVE_INFINITY;
                try {
                    double value = Double.parseDouble(context.path("gallery_order").asText());
                    if (Double.isFinite(value)) order = value;
                } catch (NumberFormatException ignored) { }
                entries.put(id, new Entry(photo, order, resource.path("created_at").asText()));
            }
            cursor = body.path("next_cursor").asText("");
            if (!cursor.isEmpty() && (!cursors.add(cursor) || cursors.size() > 100)) {
                throw new IllegalStateException("Invalid Cloudinary pagination");
            }
        } while (!cursor.isEmpty());
        return entries.values().stream()
            .sorted(Comparator.comparingDouble(Entry::order)
                .thenComparing(Entry::created, Comparator.reverseOrder())
                .thenComparing(entry -> entry.photo().id()))
            .map(Entry::photo).toList();
    }
}
