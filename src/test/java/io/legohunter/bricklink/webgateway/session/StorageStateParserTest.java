package io.legohunter.bricklink.webgateway.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.legohunter.bricklink.webgateway.browser.ImportedCookie;
import io.legohunter.bricklink.webgateway.session.StorageStateParser.InvalidSessionStateException;
import org.junit.jupiter.api.Test;

class StorageStateParserTest {

    private static final String STATE = """
            {"cookies":[
              {"name":"bricklink.lego-account.refresh-token","value":"TOKEN-VALUE","domain":".bricklink.com","path":"/","expires":1800000000.5,"httpOnly":true,"secure":true,"sameSite":"Lax"},
              {"name":"ASPSESSIONID","value":"ASP-VALUE","domain":"www.bricklink.com","path":"/","expires":-1,"httpOnly":true,"secure":true,"sameSite":"None"},
              {"name":"id.a","value":"LEGO-VALUE","domain":"identity.lego.com","path":"/","expires":1810000000,"httpOnly":true,"secure":true,"sameSite":"Lax"},
              {"name":"_ga","value":"TRACKER","domain":".google-analytics.com","path":"/","expires":1800000000,"httpOnly":false,"secure":false,"sameSite":"Lax"},
              {"name":"spoof","value":"X","domain":"bricklink.com.evil.example","path":"/","expires":-1,"httpOnly":false,"secure":false}
            ],"origins":[]}""";

    @Test
    void keepsBricklinkAndLegoCookiesOnly() {
        List<ImportedCookie> cookies = StorageStateParser.parse(bytes(STATE));

        assertThat(cookies).extracting(ImportedCookie::name)
                .containsExactly("bricklink.lego-account.refresh-token", "ASPSESSIONID", "id.a");
        ImportedCookie token = cookies.get(0);
        assertThat(token.value()).isEqualTo("TOKEN-VALUE");
        assertThat(token.domain()).isEqualTo(".bricklink.com");
        assertThat(token.expiresEpochSeconds()).isEqualTo(1800000000.5);
        assertThat(token.httpOnly()).isTrue();
        assertThat(token.secure()).isTrue();
        assertThat(token.sameSite()).isEqualTo("Lax");
        assertThat(cookies.get(1).expiresEpochSeconds()).isLessThanOrEqualTo(0);
    }

    @Test
    void cookieTextNeverShowsTheValue() {
        List<ImportedCookie> cookies = StorageStateParser.parse(bytes(STATE));

        assertThat(cookies.toString()).doesNotContain("TOKEN-VALUE", "ASP-VALUE", "LEGO-VALUE");
    }

    @Test
    void rejectsAFileWithoutABricklinkSignIn() {
        String signedOut = """
                {"cookies":[{"name":"aws-waf-token","value":"W","domain":"www.bricklink.com","path":"/","expires":-1}]}""";

        assertThatThrownBy(() -> StorageStateParser.parse(bytes(signedOut)))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageContaining("no BrickLink sign-in cookies");
    }

    @Test
    void rejectsWhatIsNotAStorageStateWithoutQuotingIt() {
        assertThatThrownBy(() -> StorageStateParser.parse(bytes("{\"cookies\": \"SECRET-TEXT\"}")))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageNotContaining("SECRET-TEXT");
        assertThatThrownBy(() -> StorageStateParser.parse(bytes("SECRET-TEXT not json")))
                .isInstanceOf(InvalidSessionStateException.class)
                .hasMessageNotContaining("SECRET-TEXT")
                .hasNoCause();
        assertThatThrownBy(() -> StorageStateParser.parse(new byte[0]))
                .isInstanceOf(InvalidSessionStateException.class);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
