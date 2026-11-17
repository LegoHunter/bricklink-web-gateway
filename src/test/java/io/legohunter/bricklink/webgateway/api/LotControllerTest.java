package io.legohunter.bricklink.webgateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.legohunter.bricklink.webgateway.browser.BrowserSession;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.FetchResult;
import io.legohunter.bricklink.webgateway.browser.FakeBrowserSession;
import io.legohunter.bricklink.webgateway.browser.FakeBrowserSession.FakeLot;
import io.legohunter.bricklink.webgateway.lot.StockroomReader;
import io.legohunter.bricklink.webgateway.lot.StockroomReader.Stockroom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "bricklink.web-gateway.keep-alive.enabled=false",
        "bricklink.web-gateway.min-request-gap=0s",
        "bricklink.web-gateway.admin-token=test-admin-token",
        "bricklink.web-gateway.caller-token=test-caller-token",
        "bricklink.web-gateway.guard.stockroom-id=C"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LotControllerTest {

    private static final long OWN = 569454186L;
    private static final long DEV_LOT = 2L;
    private static final String CALLER = "Bearer test-caller-token";
    private static final Map<Long, Stockroom> API = new HashMap<>();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeBrowserSession fakeBrowserSession() {
            return new FakeBrowserSession();
        }

        @Bean
        @Primary
        StockroomReader fakeStockroomReader() {
            return lotId -> Optional.ofNullable(API.get(lotId));
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrowserSession browserBean;

    private FakeBrowserSession browser;

    @BeforeEach
    void signedInWithLots() throws Exception {
        browser = (FakeBrowserSession) browserBean;
        API.clear();
        browser.lots.put(OWN, new FakeLot());
        API.put(OWN, new Stockroom(true, "C"));
        FakeLot dev = new FakeLot();
        dev.stockroomId = "B";
        browser.lots.put(DEV_LOT, dev);
        API.put(DEV_LOT, new Stockroom(true, "B"));
        mvc.perform(post("/session/probe").header("Authorization", CALLER)).andExpect(status().isOk());
    }

    @Test
    void everyLotEndpointNeedsTheCallerToken() throws Exception {
        mvc.perform(get("/lots/{id}/image", OWN)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CALLER_TOKEN_REQUIRED"));
        mvc.perform(putImage(OWN, jpeg())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/lots/{id}/image", OWN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/lots/{id}/extended-description", OWN)).andExpect(status().isUnauthorized());
        mvc.perform(put("/lots/{id}/extended-description", OWN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"extendedDescription\":\"x\"}")).andExpect(status().isUnauthorized());

        assertThat(browser.lots.get(OWN).customImage).isFalse();
        assertThat(browser.lots.get(OWN).extendedDescription).isEmpty();
    }

    @Test
    void imageCanBeSetReadAndDeleted() throws Exception {
        mvc.perform(putImage(OWN, jpeg()).header("Authorization", CALLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lotId").value(OWN))
                .andExpect(jsonPath("$.customImage").value(true));
        mvc.perform(get("/lots/{id}/image", OWN).header("Authorization", CALLER))
                .andExpect(jsonPath("$.customImage").value(true))
                .andExpect(jsonPath("$.thumbnailUrl").isNotEmpty());
        mvc.perform(delete("/lots/{id}/image", OWN).header("Authorization", CALLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customImage").value(false));
    }

    @Test
    void extendedDescriptionCanBeSetAndRead() throws Exception {
        mvc.perform(put("/lots/{id}/extended-description", OWN).header("Authorization", CALLER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extendedDescription\":\"Complete with <b>box</b>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extendedDescription").value("Complete with <b>box</b>"));
        mvc.perform(get("/lots/{id}/extended-description", OWN).header("Authorization", CALLER))
                .andExpect(jsonPath("$.extendedDescription").value("Complete with <b>box</b>"));
    }

    @Test
    void anotherEnvironmentsLotIsRefusedWith409AndLeftAlone() throws Exception {
        mvc.perform(putImage(DEV_LOT, jpeg()).header("Authorization", CALLER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOT_GUARD_REFUSED"));
        mvc.perform(put("/lots/{id}/extended-description", DEV_LOT).header("Authorization", CALLER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"extendedDescription\":\"x\"}"))
                .andExpect(status().isConflict());

        assertThat(browser.lots.get(DEV_LOT).customImage).isFalse();
        assertThat(browser.lots.get(DEV_LOT).extendedDescription).isEmpty();
    }

    @Test
    void anUnknownLotIs404() throws Exception {
        mvc.perform(get("/lots/{id}/image", 404L).header("Authorization", CALLER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOT_NOT_FOUND"));
    }

    @Test
    void aSignedOutGatewayAnswers503SoCallersStop() throws Exception {
        browser.nextResult = new FetchResult(200, "https://identity.lego.com/en-US/login");
        mvc.perform(post("/session/probe").header("Authorization", CALLER));

        mvc.perform(putImage(OWN, jpeg()).header("Authorization", CALLER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SESSION_SIGNED_OUT"));
    }

    @Test
    void onlyImagesAreAccepted() throws Exception {
        MockMultipartFile text = new MockMultipartFile("image", "notes.txt", "text/plain", "hello".getBytes());
        MockMultipartFile empty = new MockMultipartFile("image", "empty.jpg", "image/jpeg", new byte[0]);

        mvc.perform(putImage(OWN, text).header("Authorization", CALLER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(putImage(OWN, empty).header("Authorization", CALLER)).andExpect(status().isBadRequest());
        // BrickLink's upload form takes JPEG and GIF only, whatever the request claims the file is.
        MockMultipartFile png = new MockMultipartFile("image", "photo.jpg", "image/jpeg",
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        mvc.perform(putImage(OWN, png).header("Authorization", CALLER)).andExpect(status().isBadRequest());
        mvc.perform(put("/lots/{id}/extended-description", OWN).header("Authorization", CALLER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(browser.lots.get(OWN).customImage).isFalse();
    }

    @Test
    void anImageAboveTheSizeLimitIsRefusedBeforeTheOldOneIsDeleted() throws Exception {
        browser.lots.get(OWN).customImage = true;
        byte[] tooLarge = new byte[2_500_001];
        tooLarge[0] = (byte) 0xFF;
        tooLarge[1] = (byte) 0xD8;
        tooLarge[2] = (byte) 0xFF;
        byte[] largestAllowed = java.util.Arrays.copyOf(tooLarge, 2_500_000);

        mvc.perform(putImage(OWN, new MockMultipartFile("image", "big.jpg", "image/jpeg", tooLarge))
                        .header("Authorization", CALLER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(browser.lots.get(OWN).customImage).isTrue();
        assertThat(browser.calls).noneMatch(call -> call.contains("#delImg"));

        mvc.perform(putImage(OWN, new MockMultipartFile("image", "ok.jpg", "image/jpeg", largestAllowed))
                        .header("Authorization", CALLER))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder putImage(long lotId, MockMultipartFile file) {
        return multipart(HttpMethod.PUT, "/lots/{id}/image", lotId).file(file);
    }

    private static MockMultipartFile jpeg() {
        return new MockMultipartFile("image", "photo.jpg", "image/jpeg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3});
    }
}
