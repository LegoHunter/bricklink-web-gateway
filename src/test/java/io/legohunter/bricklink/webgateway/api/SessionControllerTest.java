package io.legohunter.bricklink.webgateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import io.legohunter.bricklink.webgateway.browser.BrowserSession;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.FetchResult;
import io.legohunter.bricklink.webgateway.browser.FakeBrowserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "bricklink.web-gateway.keep-alive.enabled=false",
        "bricklink.web-gateway.min-request-gap=0s",
        "bricklink.web-gateway.admin-token=test-admin-token",
        "bricklink.web-gateway.caller-token=test-caller-token"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SessionControllerTest {

    private static final String STATE = """
            {"cookies":[{"name":"bricklink.lego-account.refresh-token","value":"TOKEN-VALUE","domain":".bricklink.com","path":"/","expires":1800000000,"httpOnly":true,"secure":true,"sameSite":"Lax"}]}""";

    @TestConfiguration
    static class FakeBrowser {
        @Bean
        @Primary
        FakeBrowserSession fakeBrowserSession() {
            return new FakeBrowserSession();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrowserSession browserBean;

    private FakeBrowserSession browser;

    @BeforeEach
    void setUp() {
        browser = (FakeBrowserSession) browserBean;
    }

    @Test
    void sessionReportsTheStateWithoutContactingBricklink() throws Exception {
        mvc.perform(get("/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.layers.length()").value(3));

        assertThat(browser.fetches).isZero();
    }

    @Test
    void probeVisitsBricklinkAndReportsTheResult() throws Exception {
        mvc.perform(post("/session/probe").header("Authorization", "Bearer test-caller-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SIGNED_IN"))
                .andExpect(jsonPath("$.lastProbeLocation").value("www.bricklink.com/my.asp"));

        mvc.perform(get("/session")).andExpect(jsonPath("$.state").value("SIGNED_IN"));
        assertThat(browser.fetches).isEqualTo(1);
    }

    @Test
    void probeNeedsATokenAndTheAdminTokenAlsoPasses() throws Exception {
        mvc.perform(post("/session/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CALLER_TOKEN_REQUIRED"));
        mvc.perform(post("/session/probe").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        assertThat(browser.fetches).isZero();

        mvc.perform(post("/session/probe").header("Authorization", "Bearer test-admin-token"))
                .andExpect(status().isOk());
        assertThat(browser.fetches).isEqualTo(1);
    }

    @Test
    void theCallerTokenCannotReplaceTheSession() throws Exception {
        mvc.perform(multipart("/admin/session/import").file(stateFile(STATE))
                        .header("Authorization", "Bearer test-caller-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_TOKEN_REQUIRED"));

        assertThat(browser.imported).isNull();
    }

    @Test
    void importNeedsTheAdminToken() throws Exception {
        mvc.perform(multipart("/admin/session/import").file(stateFile(STATE)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_TOKEN_REQUIRED"));
        mvc.perform(multipart("/admin/session/import").file(stateFile(STATE))
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/admin/session/import").file(stateFile(STATE))
                        .header("Authorization", "test-admin-token"))
                .andExpect(status().isUnauthorized());

        assertThat(browser.imported).isNull();
    }

    @Test
    void importWithTheTokenReplacesTheSessionAndNeverEchoesCookieValues() throws Exception {
        mvc.perform(multipart("/admin/session/import").file(stateFile(STATE))
                        .header("Authorization", "Bearer test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SIGNED_IN"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("TOKEN-VALUE"))));

        assertThat(browser.imported).hasSize(1);
        assertThat(browser.imported.get(0).value()).isEqualTo("TOKEN-VALUE");
    }

    @Test
    void importOfASessionThatDoesNotSignInIsRejectedWith422() throws Exception {
        browser.nextResult = new FetchResult(200, "https://identity.lego.com/en-US/login");

        mvc.perform(multipart("/admin/session/import").file(stateFile(STATE))
                        .header("Authorization", "Bearer test-admin-token"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.state").value("SIGNED_OUT"));
    }

    @Test
    void importOfAnUnusableFileIsABadRequest() throws Exception {
        mvc.perform(multipart("/admin/session/import").file(stateFile("{\"cookies\":[]}"))
                        .header("Authorization", "Bearer test-admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_STATE"));

        assertThat(browser.imported).isNull();
    }

    private static MockMultipartFile stateFile(String content) {
        return new MockMultipartFile("state", "portable-state.json", "application/json",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
