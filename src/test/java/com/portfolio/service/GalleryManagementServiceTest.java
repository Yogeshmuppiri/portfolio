package com.portfolio.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GalleryManagementServiceTest {
    private static final String API = "https://api.cloudinary.com/v1_1/demo/";
    private final GalleryService gallery = mock(GalleryService.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GalleryManagementService management = new GalleryManagementService("demo", "key", "secret",
        "portfolio-photography", gallery, builder.build());

    private String photo(String tags, String versions) {
        return "{\"asset_id\":\"asset1\",\"public_id\":\"portfolio/photography/one\",\"resource_type\":\"image\","
            + "\"type\":\"upload\",\"tags\":" + tags + ",\"versions\":" + versions + "}";
    }

    private void expectDetails(String response) {
        server.expect(requestTo(API + "resources/asset1?versions=true"))
            .andExpect(header("Authorization", "Basic a2V5OnNlY3JldA=="))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void expectDestroy(String response) {
        server.expect(requestTo(API + "image/destroy")).andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("asset_id=asset1")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("invalidate=true")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("signature=")))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    @Test void deletesStoredPhotoAndInvalidatesPublicGallery() {
        expectDetails(photo("[\"portfolio-photography\"]", "[]"));
        expectDestroy("{\"result\":\"ok\"}");
        management.delete("asset1");
        verify(gallery).remove("portfolio/photography/one");
        verify(gallery).invalidate();
        server.verify();
    }

    @Test void purgesBackupsBeforeDestroyingActivePhoto() {
        expectDetails(photo("[\"portfolio-photography\"]", "[{\"version_id\":\"backup1\"}]"));
        server.expect(request -> {
            assertThat(request.getMethod()).isEqualTo(HttpMethod.DELETE);
            assertThat(request.getURI().getPath()).isEqualTo("/v1_1/demo/resources/backup/asset1");
            assertThat(request.getURI().getQuery()).isEqualTo("version_ids[]=backup1");
        }).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expectDetails(photo("[\"portfolio-photography\"]", "[]"));
        expectDestroy("{\"result\":\"ok\"}");
        management.delete("asset1");
        verify(gallery).remove("portfolio/photography/one");
        server.verify();
    }

    @Test void cannotDeletePhotosOutsideGallery() {
        expectDetails(photo("[\"other-project\"]", "[]"));
        assertThatThrownBy(() -> management.delete("asset1")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gallery);
        server.verify();
    }

    @Test void backupFailureLeavesActiveImageForRetry() {
        expectDetails(photo("[\"portfolio-photography\"]", "[{\"version_id\":\"backup1\"}]"));
        server.expect(method(HttpMethod.DELETE)).andRespond(withServerError());
        assertThatThrownBy(() -> management.delete("asset1")).isInstanceOf(org.springframework.web.client.RestClientException.class);
        verifyNoInteractions(gallery);
        server.verify();
    }

    @Test void unconfirmedDestroyDoesNotClaimSuccess() {
        expectDetails(photo("[\"portfolio-photography\"]", "[]"));
        expectDestroy("{\"result\":\"error\"}");
        assertThatThrownBy(() -> management.delete("asset1")).isInstanceOf(IllegalStateException.class);
        verify(gallery, never()).remove(anyString());
        verify(gallery).invalidate();
        server.verify();
    }

    @Test void listingPaginatesAndExcludesPrivateAssets() {
        String url = API + "resources/image/tags/portfolio-photography?max_results=500&context=true&direction=desc";
        server.expect(requestTo(url)).andRespond(withSuccess("""
            {"resources":[{"asset_id":"one","public_id":"folder/a space","version":1,"type":"upload","resource_type":"image"},
            {"asset_id":"private","public_id":"private","version":1,"type":"authenticated","resource_type":"image"}],"next_cursor":"page2"}
            """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url + "&next_cursor=page2")).andRespond(withSuccess("{\"resources\":[]}", MediaType.APPLICATION_JSON));
        var photos = management.photos();
        assertThat(photos).hasSize(1);
        assertThat(photos.get(0).assetId()).isEqualTo("one");
        assertThat(photos.get(0).url()).endsWith("/v1/folder/a%20space");
        server.verify();
    }
}
