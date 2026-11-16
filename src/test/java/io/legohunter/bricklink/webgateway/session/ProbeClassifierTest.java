package io.legohunter.bricklink.webgateway.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProbeClassifierTest {

    @Test
    void signedInWhenTheChainEndsOnABricklinkPage() {
        assertThat(ProbeClassifier.classify(200, "https://www.bricklink.com/my.asp")).isEqualTo(SessionState.SIGNED_IN);
        assertThat(ProbeClassifier.classify(200, "https://www.bricklink.com/v2/my/dashboard.page?x=1"))
                .isEqualTo(SessionState.SIGNED_IN);
    }

    @ParameterizedTest
    @CsvSource({
            "200, https://identity.lego.com/en-US/login?returnUrl=abc",
            "200, https://www.bricklink.com/v2/login.page",
            "200, https://www.bricklink.com/auth/sign-in",
            "200, https://www.bricklink.com/v3/Sign-In/start",
            // LEGO's bot filter answers 403 on its own pages: the redirect there still means signed out.
            "403, https://identity.lego.com/en-US/login",
    })
    void signedOutWhenTheChainEndsOnTheSignInRoute(int status, String url) {
        assertThat(ProbeClassifier.classify(status, url)).isEqualTo(SessionState.SIGNED_OUT);
    }

    @ParameterizedTest
    @CsvSource({
            // The firewall's challenge page: answered while signed in, so not a sign-out.
            "202, https://www.bricklink.com/my.asp",
            "403, https://www.bricklink.com/my.asp",
            "429, https://www.bricklink.com/my.asp",
            // An outage on either site says nothing about the session.
            "500, https://www.bricklink.com/my.asp",
            "503, https://www.bricklink.com/v2/login.page",
            "503, https://identity.lego.com/en-US/login",
            // Somewhere unexpected.
            "200, https://example.com/",
            "200, https://bricklink.com.evil.example/my.asp",
    })
    void unknownWhenTheAnswerSaysNothingAboutTheSession(int status, String url) {
        assertThat(ProbeClassifier.classify(status, url)).isEqualTo(SessionState.UNKNOWN);
    }

    @Test
    void unknownWhenThereIsNoUsableUrl() {
        assertThat(ProbeClassifier.classify(200, null)).isEqualTo(SessionState.UNKNOWN);
        assertThat(ProbeClassifier.classify(200, "")).isEqualTo(SessionState.UNKNOWN);
        assertThat(ProbeClassifier.classify(200, "not a url")).isEqualTo(SessionState.UNKNOWN);
        assertThat(ProbeClassifier.classify(200, "/relative/path")).isEqualTo(SessionState.UNKNOWN);
    }
}
