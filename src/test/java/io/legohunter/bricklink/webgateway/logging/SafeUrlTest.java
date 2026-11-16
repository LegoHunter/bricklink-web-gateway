package io.legohunter.bricklink.webgateway.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeUrlTest {

    @Test
    void keepsHostAndPathOnly() {
        assertThat(SafeUrl.of("https://identity.lego.com/en-US/login?code=SECRET&state=abc#frag"))
                .isEqualTo("identity.lego.com/en-US/login");
        assertThat(SafeUrl.of("https://www.bricklink.com/my.asp")).isEqualTo("www.bricklink.com/my.asp");
    }

    @Test
    void neverEchoesWhatItCannotParse() {
        assertThat(SafeUrl.of(null)).isEqualTo(SafeUrl.UNPARSEABLE);
        assertThat(SafeUrl.of("  ")).isEqualTo(SafeUrl.UNPARSEABLE);
        assertThat(SafeUrl.of("token=SECRET value")).isEqualTo(SafeUrl.UNPARSEABLE);
        assertThat(SafeUrl.of("/path?code=SECRET")).isEqualTo(SafeUrl.UNPARSEABLE);
    }
}
