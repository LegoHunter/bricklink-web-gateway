package io.legohunter.bricklink.webgateway.browser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaywrightBrowserSessionTest {

    @Test
    void failureTextCutsQueryStringsAndLaterLines() {
        RuntimeException failure = new RuntimeException(
                "net::ERR_FAILED at https://identity.lego.com/login?code=SECRET-CODE&state=x\nCall log:\n  - navigating to https://x/?token=OTHER-SECRET");

        String text = PlaywrightBrowserSession.describe(failure);

        assertThat(text).contains("RuntimeException", "identity.lego.com/login")
                .doesNotContain("SECRET-CODE", "OTHER-SECRET", "Call log");
    }

    @Test
    void browserCacheHasACeilingSoTheProfileVolumeStaysSmall() {
        assertThat(PlaywrightBrowserSession.LAUNCH_ARGS).contains("--disk-cache-size=52428800", "--disable-dev-shm-usage");
    }

    @Test
    void failureTextCopesWithNoMessage() {
        assertThat(PlaywrightBrowserSession.describe(new IllegalStateException())).isEqualTo("IllegalStateException");
    }
}
