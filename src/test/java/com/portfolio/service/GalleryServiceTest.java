package com.portfolio.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GalleryServiceTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final MutableClock clock = new MutableClock();
    private final GalleryService service = new GalleryService("demo", "key", "secret",
        "portfolio-photography", builder.build(), clock, List.of());
    private static final String URL = "https://api.cloudinary.com/v1_1/demo/resources/image/tags/portfolio-photography?max_results=500&context=true&direction=desc";

    @Test void paginatesFiltersPrivateAssetsAndSortsAndCaches() {
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Basic a2V5OnNlY3JldA=="))
            .andRespond(withSuccess("""
                {"resources":[
                  {"public_id":"folder/a space","version":7,"resource_type":"image","type":"upload","width":1200,"height":800,"context":{"custom":{"alt":"Lake","gallery_order":"20"}}},
                  {"public_id":"private","version":1,"resource_type":"image","type":"authenticated"}
                ],"next_cursor":"page2"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL + "&next_cursor=page2")).andRespond(withSuccess("""
            {"resources":[{"public_id":"first","version":8,"resource_type":"image","type":"upload","context":{"custom":{"caption":"Mountain","gallery_order":"10"}}}]}
            """, MediaType.APPLICATION_JSON));
        var photos = service.photos();
        assertThat(photos).extracting(GalleryService.Photo::id).containsExactly("first", "folder/a space");
        assertThat(photos.get(0).alt()).isEqualTo("Mountain");
        assertThat(photos.get(1).url()).isEqualTo("https://res.cloudinary.com/demo/image/upload/f_auto,q_auto,c_limit,w_1600/v7/folder/a%20space");
        assertThat(service.photos()).isEqualTo(photos);
        server.verify();
    }

    @Test void keepsEmptyCloudinaryGalleryEmpty() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"resources\":[]}", MediaType.APPLICATION_JSON));
        assertThat(service.photos()).isEmpty();
        assertThat(service.photos()).isEmpty();
        server.verify();
    }

    @Test void staleCacheExpiresAndFailureIsThrottled() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
            {"resources":[{"public_id":"lake","version":1,"resource_type":"image","type":"upload"}]}
            """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withServerError());
        var photos = service.photos();
        clock.now = clock.now.plusSeconds(301);
        assertThat(service.photos()).isEqualTo(photos);
        assertThat(service.photos()).isEqualTo(photos);
        clock.now = clock.now.plusSeconds(3600);
        assertThatThrownBy(service::photos).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::photos).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test void missingCredentialsUseSeedButPartialConfigurationFails() {
        var seed = List.of(new GalleryService.Photo("local", "/local.jpg", "Local", 0, 0));
        var local = new GalleryService("", "", "", "tag", builder.build(), clock, seed);
        assertThat(local.photos()).isEqualTo(seed);
        var partial = new GalleryService("demo", "", "", "tag", builder.build(), clock, seed);
        assertThatThrownBy(partial::photos).isInstanceOf(IllegalStateException.class);
    }

    @Test void deletedPhotoDoesNotReappearFromStaleCacheDuringOutage() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
            {"resources":[{"public_id":"lake","version":1,"resource_type":"image","type":"upload"}]}
            """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThat(service.photos()).hasSize(1);
        service.remove("lake");
        assertThat(service.photos()).isEmpty();
        server.verify();
    }

    @Test void malformedResponseDoesNotBecomeAnEmptyGallery() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(service::photos).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
