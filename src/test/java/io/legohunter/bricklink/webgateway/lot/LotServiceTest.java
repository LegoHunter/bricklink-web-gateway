package io.legohunter.bricklink.webgateway.lot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.legohunter.bricklink.webgateway.browser.BrowserSession.FetchResult;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.FilePart;
import io.legohunter.bricklink.webgateway.browser.FakeBrowserSession;
import io.legohunter.bricklink.webgateway.browser.FakeBrowserSession.FakeLot;
import io.legohunter.bricklink.webgateway.browser.OperationQueue;
import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.GuardUnavailableException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotGuardRefusedException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotNotFoundException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotNotSupportedException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.SessionUnavailableException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.UnexpectedPageException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.WriteNotAppliedException;
import io.legohunter.bricklink.webgateway.lot.StockroomReader.Stockroom;
import io.legohunter.bricklink.webgateway.session.SessionManager;
import io.legohunter.bricklink.webgateway.session.SessionState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LotServiceTest {

    private static final long OWN = 569454186L;
    private static final long DEV_LOT = 2L;
    private static final long PUBLIC_LOT = 3L;
    private static final FilePart JPEG = new FilePart("lot.jpg", "image/jpeg", new byte[] {1, 2, 3});

    private final FakeBrowserSession browser = new FakeBrowserSession();
    private final Map<Long, Stockroom> api = new HashMap<>();
    private boolean apiDown;
    private final GatewayProperties properties = StockroomGuardTest.properties(false, "C");
    private final OperationQueue queue = new OperationQueue(Duration.ZERO, Duration.ofSeconds(10));
    private final SessionManager sessions =
            new SessionManager(browser, queue, properties, Clock.systemUTC(), new SimpleMeterRegistry());
    private final StockroomReader reader = lotId -> {
        if (apiDown) {
            throw new GuardUnavailableException("The BrickLink API could not be reached");
        }
        return Optional.ofNullable(api.get(lotId));
    };
    private final LotService lots = new LotService(sessions, queue, new LotPageClient(browser, properties),
            new StockroomGuard(reader, properties));

    @BeforeEach
    void signedInWithThreeLots() {
        browser.lots.put(OWN, new FakeLot());
        api.put(OWN, new Stockroom(true, "C"));

        FakeLot dev = new FakeLot();
        dev.stockroomId = "B";
        browser.lots.put(DEV_LOT, dev);
        api.put(DEV_LOT, new Stockroom(true, "B"));

        FakeLot forSale = new FakeLot();
        forSale.inStockroom = false;
        forSale.stockroomId = "";
        browser.lots.put(PUBLIC_LOT, forSale);
        api.put(PUBLIC_LOT, new Stockroom(false, ""));

        sessions.probe();
        browser.calls.clear();
    }

    @AfterEach
    void stop() {
        queue.shutdown(() -> { });
    }

    @Test
    void readsALotFromTheWebsitesDataCall() {
        browser.lots.get(OWN).extendedDescription = "<b>Complete</b> with box";

        LotState lot = lots.read(OWN);

        assertThat(lot.inStockroom()).isTrue();
        assertThat(lot.stockroomId()).isEqualTo("C");
        assertThat(lot.customImage()).isFalse();
        assertThat(lot.extendedDescription()).isEqualTo("<b>Complete</b> with box");
        assertThat(browser.calls).containsExactly("postForm /ajax/renovate/storeInventoryDetail/list.ajax");
    }

    @Test
    void setsAnImageOnALotWithoutOneAndVerifiesByReadingBack() {
        LotState lot = lots.putImage(OWN, JPEG);

        assertThat(lot.customImage()).isTrue();
        assertThat(browser.lots.get(OWN).uploaded).isEqualTo(JPEG);
        assertThat(browser.calls).containsExactly(
                "postForm /ajax/renovate/storeInventoryDetail/list.ajax",
                "get /imgAdd.asp",
                "postFile /imgAdd.asp FILE",
                "postForm /ajax/renovate/storeInventoryDetail/list.ajax");
    }

    @Test
    void replacingAnImageDeletesTheOldOneFirst() {
        browser.lots.get(OWN).customImage = true;
        FilePart replacement = new FilePart("lot.gif", "image/gif", new byte[] {9});

        LotState lot = lots.putImage(OWN, replacement);

        assertThat(lot.customImage()).isTrue();
        assertThat(browser.lots.get(OWN).uploaded).isEqualTo(replacement);
        assertThat(browser.calls.get(1)).startsWith("submitPage /v2/inventory_detail.page").contains("#delImg");
        assertThat(browser.calls).contains("postFile /imgAdd.asp FILE");
    }

    @Test
    void sendingTheSameImageTwiceEndsInTheSameState() {
        lots.putImage(OWN, JPEG);
        LotState second = lots.putImage(OWN, JPEG);

        assertThat(second.customImage()).isTrue();
        assertThat(browser.lots.get(OWN).uploaded).isEqualTo(JPEG);
    }

    @Test
    void deletesAnImageAndDoesNothingWhenThereIsNone() {
        browser.lots.get(OWN).customImage = true;

        assertThat(lots.deleteImage(OWN).customImage()).isFalse();
        assertThat(browser.lots.get(OWN).customImage).isFalse();

        browser.calls.clear();
        assertThat(lots.deleteImage(OWN).customImage()).isFalse();
        assertThat(browser.calls).containsExactly("postForm /ajax/renovate/storeInventoryDetail/list.ajax");
    }

    @Test
    void setsTheExtendedDescriptionAndSkipsTheWriteWhenItIsAlreadyThere() {
        LotState lot = lots.putExtendedDescription(OWN, "Complete, with instructions");

        assertThat(lot.extendedDescription()).isEqualTo("Complete, with instructions");
        assertThat(browser.lots.get(OWN).extendedDescription).isEqualTo("Complete, with instructions");

        browser.calls.clear();
        lots.putExtendedDescription(OWN, "Complete, with instructions\r\n");
        assertThat(browser.calls).containsExactly("postForm /ajax/renovate/storeInventoryDetail/list.ajax");
    }

    @Test
    void textThatBricklinkStoresAsHtmlStillCountsAsAppliedAndIsNotWrittenTwice() {
        LotState lot = lots.putExtendedDescription(OWN, "Sealed & \"mint\", box &amp; manual");

        // What BrickLink keeps, and what a later read returns.
        assertThat(lot.extendedDescription()).isEqualTo("Sealed &amp; &quot;mint&quot;, box &amp; manual");

        browser.calls.clear();
        lots.putExtendedDescription(OWN, "Sealed & \"mint\", box &amp; manual");
        assertThat(browser.calls).containsExactly("postForm /ajax/renovate/storeInventoryDetail/list.ajax");
    }

    @Test
    void anEmptyTextClearsTheExtendedDescription() {
        browser.lots.get(OWN).extendedDescription = "old text";

        assertThat(lots.putExtendedDescription(OWN, "").extendedDescription()).isEmpty();
    }

    @Test
    void aLotWhosePageHasNoExtendedDescriptionFieldIsNotSupported() {
        browser.lots.get(OWN).hasExtendedField = false;

        assertThatThrownBy(() -> lots.putExtendedDescription(OWN, "text")).isInstanceOf(LotNotSupportedException.class);
    }

    @Test
    void theGuardRefusesAnotherEnvironmentsLotBeforeAnyWebsiteRequest() {
        assertThatThrownBy(() -> lots.putImage(DEV_LOT, JPEG)).isInstanceOf(LotGuardRefusedException.class);
        assertThatThrownBy(() -> lots.deleteImage(DEV_LOT)).isInstanceOf(LotGuardRefusedException.class);
        assertThatThrownBy(() -> lots.putExtendedDescription(DEV_LOT, "x")).isInstanceOf(LotGuardRefusedException.class);
        assertThatThrownBy(() -> lots.putImage(PUBLIC_LOT, JPEG)).isInstanceOf(LotGuardRefusedException.class);

        assertThat(browser.calls).isEmpty();
        assertThat(browser.lots.get(DEV_LOT).customImage).isFalse();
        assertThat(browser.lots.get(DEV_LOT).extendedDescription).isEmpty();
    }

    @Test
    void theWebsiteCheckStillRefusesWhenTheApiAndTheWebsiteDisagree() {
        // The API says stockroom C, but the website shows the lot for sale: no write.
        browser.lots.get(OWN).inStockroom = false;

        assertThatThrownBy(() -> lots.putImage(OWN, JPEG)).isInstanceOf(LotGuardRefusedException.class)
                .hasMessageContaining("website");
        assertThatThrownBy(() -> lots.putExtendedDescription(OWN, "x")).isInstanceOf(LotGuardRefusedException.class);

        assertThat(browser.calls).allMatch(call -> call.startsWith("postForm"));
        assertThat(browser.lots.get(OWN).customImage).isFalse();
    }

    @Test
    void noWriteWhenTheGuardCannotAskTheApi() {
        apiDown = true;

        assertThatThrownBy(() -> lots.putImage(OWN, JPEG)).isInstanceOf(GuardUnavailableException.class);
        assertThat(browser.calls).isEmpty();
    }

    @Test
    void aLotUnknownToBricklinkIsNotFound() {
        assertThatThrownBy(() -> lots.read(404L)).isInstanceOf(LotNotFoundException.class);
        assertThatThrownBy(() -> lots.putImage(404L, JPEG)).isInstanceOf(LotNotFoundException.class);
    }

    @Test
    void reportsWhenBricklinkAcceptsAChangeButDoesNotApplyIt() {
        browser.ignoreWrites = true;

        assertThatThrownBy(() -> lots.putImage(OWN, JPEG)).isInstanceOf(WriteNotAppliedException.class);
        assertThatThrownBy(() -> lots.putExtendedDescription(OWN, "x")).isInstanceOf(WriteNotAppliedException.class);
    }

    @Test
    void nothingRunsUnlessTheSessionIsSignedIn() {
        browser.nextResult = new FetchResult(200, "https://identity.lego.com/en-US/login");
        sessions.probe();
        browser.calls.clear();

        assertThatThrownBy(() -> lots.read(OWN)).isInstanceOfSatisfying(SessionUnavailableException.class,
                e -> assertThat(e.state()).isEqualTo(SessionState.SIGNED_OUT));
        assertThatThrownBy(() -> lots.putImage(OWN, JPEG)).isInstanceOf(SessionUnavailableException.class);
        assertThat(browser.calls).isEmpty();
    }

    @Test
    void aLoginPageInsteadOfLotDataIsReportedAndTheSessionIsReprobed() {
        browser.lotPagesSignedOut = true;
        browser.nextResult = new FetchResult(200, "https://identity.lego.com/en-US/login");

        assertThatThrownBy(() -> lots.read(OWN)).isInstanceOf(UnexpectedPageException.class)
                .hasMessageContaining("identity.lego.com/en-US/login").hasMessageNotContaining("SECRET");

        // The probe that follows the failure has corrected the state for the next caller.
        assertThat(sessions.snapshot().state()).isEqualTo(SessionState.SIGNED_OUT);
    }
}
