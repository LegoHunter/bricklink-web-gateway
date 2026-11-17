package io.legohunter.bricklink.webgateway.lot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.GuardUnavailableException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotGuardRefusedException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotNotFoundException;
import io.legohunter.bricklink.webgateway.lot.StockroomReader.Stockroom;
import org.junit.jupiter.api.Test;

class StockroomGuardTest {

    private final Map<Long, Stockroom> api = new HashMap<>();
    private final StockroomReader reader = lotId -> Optional.ofNullable(api.get(lotId));

    @Test
    void lowerEnvironmentMayChangeOnlyLotsInItsOwnStockroom() {
        StockroomGuard sandbox = guard(false, "C");
        api.put(1L, new Stockroom(true, "C"));
        api.put(2L, new Stockroom(true, "B"));
        api.put(3L, new Stockroom(true, "A"));
        api.put(4L, new Stockroom(false, ""));
        // Marked with the right letter but visible to buyers: still refused.
        api.put(5L, new Stockroom(false, "C"));

        assertThatCode(() -> sandbox.checkAgainstApi(1L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> sandbox.checkAgainstApi(2L)).isInstanceOf(LotGuardRefusedException.class)
                .hasMessageContaining("stockroom B").hasMessageContaining("stockroom C");
        assertThatThrownBy(() -> sandbox.checkAgainstApi(3L)).isInstanceOf(LotGuardRefusedException.class);
        assertThatThrownBy(() -> sandbox.checkAgainstApi(4L)).isInstanceOf(LotGuardRefusedException.class)
                .hasMessageContaining("not in a stockroom");
        assertThatThrownBy(() -> sandbox.checkAgainstApi(5L)).isInstanceOf(LotGuardRefusedException.class);
    }

    @Test
    void theLetterIsComparedWithoutRegardToCaseOrSpaces() {
        api.put(1L, new Stockroom(true, " c "));

        assertThatCode(() -> guard(false, "C").checkAgainstApi(1L)).doesNotThrowAnyException();
        assertThatCode(() -> guard(false, " c").checkAgainstApi(1L)).doesNotThrowAnyException();
    }

    @Test
    void aLotTheApiDoesNotKnowIsNotFound() {
        assertThatThrownBy(() -> guard(false, "C").checkAgainstApi(99L)).isInstanceOf(LotNotFoundException.class);
    }

    @Test
    void anEnvironmentWithoutAStockroomMayChangeNothing() {
        api.put(1L, new Stockroom(true, "C"));

        for (String unset : new String[] {"", "  ", null}) {
            assertThatThrownBy(() -> guard(false, unset).checkAgainstApi(1L))
                    .isInstanceOf(LotGuardRefusedException.class).hasMessageContaining("no stockroom configured");
            assertThatThrownBy(() -> guard(false, unset).checkAgainstPage(lot(1L, true, "C")))
                    .isInstanceOf(LotGuardRefusedException.class);
        }
    }

    @Test
    void productionHasNoStockroomRestrictionAndDoesNotAskTheApi() {
        StockroomReader failing = lotId -> {
            throw new GuardUnavailableException("must not be called");
        };
        StockroomGuard production = new StockroomGuard(failing, properties(true, ""));

        assertThatCode(() -> production.checkAgainstApi(1L)).doesNotThrowAnyException();
        assertThatCode(() -> production.checkAgainstPage(lot(1L, false, ""))).doesNotThrowAnyException();
    }

    @Test
    void anUnreachableApiStopsTheWrite() {
        StockroomReader failing = lotId -> {
            throw new GuardUnavailableException("The BrickLink API could not be reached");
        };

        assertThatThrownBy(() -> new StockroomGuard(failing, properties(false, "C")).checkAgainstApi(1L))
                .isInstanceOf(GuardUnavailableException.class);
    }

    @Test
    void theWebsitesOwnDataIsCheckedTheSameWay() {
        StockroomGuard sandbox = guard(false, "C");

        assertThatCode(() -> sandbox.checkAgainstPage(lot(1L, true, "C"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> sandbox.checkAgainstPage(lot(1L, true, "B"))).isInstanceOf(LotGuardRefusedException.class);
        assertThatThrownBy(() -> sandbox.checkAgainstPage(lot(1L, false, "C"))).isInstanceOf(LotGuardRefusedException.class);
    }

    private StockroomGuard guard(boolean production, String stockroomId) {
        return new StockroomGuard(reader, properties(production, stockroomId));
    }

    private static LotState lot(long lotId, boolean inStockroom, String stockroomId) {
        return new LotState(lotId, inStockroom, stockroomId, false, null, "");
    }

    static GatewayProperties properties(boolean production, String stockroomId) {
        return new GatewayProperties("https://www.bricklink.com", "/my.asp", Path.of("unused"), "", true,
                Duration.ZERO, Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ofSeconds(10), "", "",
                new GatewayProperties.KeepAlive(false, Duration.ofHours(1), Duration.ZERO),
                new GatewayProperties.Guard(production, stockroomId));
    }
}
