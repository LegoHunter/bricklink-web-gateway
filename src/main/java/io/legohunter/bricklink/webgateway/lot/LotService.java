package io.legohunter.bricklink.webgateway.lot;

import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.legohunter.bricklink.webgateway.browser.BrowserException;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.FilePart;
import io.legohunter.bricklink.webgateway.browser.OperationQueue;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotNotFoundException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.SessionUnavailableException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.UnexpectedPageException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.WriteNotAppliedException;
import io.legohunter.bricklink.webgateway.session.SessionManager;
import io.legohunter.bricklink.webgateway.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The lot operations: the two updates the official BrickLink REST API cannot do (custom image,
 * Extended Description) and the reads they need.
 *
 * <p>Every write follows the same order: session signed in, guard against the REST API, read the
 * lot from the website, guard against that, change, read back and compare. Nothing is changed
 * unless both guard checks pass, and success is only reported when the read-back shows the change.
 */
@Service
public class LotService {

    private static final Pattern LOOSE_AMPERSAND = Pattern.compile("&(?!(?:[a-zA-Z]+|#[0-9]+);)");

    private static final Logger log = LoggerFactory.getLogger(LotService.class);

    private final SessionManager sessions;
    private final OperationQueue queue;
    private final LotPageClient pages;
    private final StockroomGuard guard;

    public LotService(SessionManager sessions, OperationQueue queue, LotPageClient pages, StockroomGuard guard) {
        this.sessions = sessions;
        this.queue = queue;
        this.pages = pages;
        this.guard = guard;
    }

    public LotState read(long lotId) {
        requireSignedIn();
        return onBrowser(() -> readOrFail(lotId));
    }

    /** Sets the custom image, replacing an existing one (BrickLink needs delete, then upload). */
    public LotState putImage(long lotId, FilePart image) {
        requireSignedIn();
        guard.checkAgainstApi(lotId);
        LotState result = onBrowser(() -> {
            LotState before = readOrFail(lotId);
            guard.checkAgainstPage(before);
            if (before.customImage()) {
                pages.deleteImage(lotId);
                if (readOrFail(lotId).customImage()) {
                    throw new WriteNotAppliedException("The existing custom image is still there after deleting it");
                }
            }
            pages.uploadImage(lotId, image);
            LotState after = readOrFail(lotId);
            if (!after.customImage()) {
                throw new WriteNotAppliedException("The lot shows no custom image after the upload");
            }
            return after;
        });
        log.info("Lot {} custom image set", lotId);
        return result;
    }

    public LotState deleteImage(long lotId) {
        requireSignedIn();
        guard.checkAgainstApi(lotId);
        LotState result = onBrowser(() -> {
            LotState before = readOrFail(lotId);
            guard.checkAgainstPage(before);
            if (!before.customImage()) {
                return before;
            }
            pages.deleteImage(lotId);
            LotState after = readOrFail(lotId);
            if (after.customImage()) {
                throw new WriteNotAppliedException("The lot still shows a custom image after deleting it");
            }
            return after;
        });
        log.info("Lot {} custom image removed", lotId);
        return result;
    }

    public LotState putExtendedDescription(long lotId, String text) {
        String wanted = text == null ? "" : text;
        requireSignedIn();
        guard.checkAgainstApi(lotId);
        LotState result = onBrowser(() -> {
            LotState before = readOrFail(lotId);
            guard.checkAgainstPage(before);
            if (stores(before.extendedDescription(), wanted)) {
                return before;
            }
            pages.setExtendedDescription(lotId, wanted);
            LotState after = readOrFail(lotId);
            // BrickLink stores the text as HTML and tidies it in ways not all modelled here, so a
            // text that differs from the one sent still counts as applied if it changed at all.
            // The caller gets the stored text back.
            if (!stores(after.extendedDescription(), wanted)
                    && same(after.extendedDescription(), before.extendedDescription())) {
                throw new WriteNotAppliedException("The Extended Description is unchanged after the update");
            }
            return after;
        });
        log.info("Lot {} Extended Description set ({} characters)", lotId, result.extendedDescription().length());
        return result;
    }

    private LotState readOrFail(long lotId) {
        return pages.read(lotId).orElseThrow(() -> new LotNotFoundException(lotId));
    }

    private void requireSignedIn() {
        SessionState state = sessions.snapshot().state();
        if (state != SessionState.SIGNED_IN) {
            throw new SessionUnavailableException(state);
        }
    }

    /**
     * Runs on the browser thread. If BrickLink answers with something unexpected, the session is
     * probed afterwards, so a lost session shows up as SIGNED_OUT for the next caller.
     */
    private <T> T onBrowser(Supplier<T> operation) {
        try {
            return queue.run(operation);
        } catch (UnexpectedPageException | BrowserException e) {
            try {
                sessions.probe();
            } catch (RuntimeException probeFailure) {
                log.warn("Probe after a failed lot operation did not run: {}", probeFailure.getClass().getSimpleName());
            }
            throw e;
        }
    }

    /** Whether the stored text is what BrickLink keeps for the wanted text. */
    private static boolean stores(String stored, String wanted) {
        return same(stored, wanted) || same(stored, asBricklinkStores(wanted));
    }

    /**
     * BrickLink keeps the Extended Description as HTML: it stores a double quote as {@code &quot;}
     * and an ampersand that does not start an entity as {@code &amp;}, and it leaves tags alone.
     * (It also escapes a loose {@code <} or {@code >}; that depends on the text around it and is
     * not reproduced here.)
     */
    static String asBricklinkStores(String text) {
        return LOOSE_AMPERSAND.matcher(text == null ? "" : text).replaceAll("&amp;").replace("\"", "&quot;");
    }

    private static boolean same(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").strip();
    }
}
