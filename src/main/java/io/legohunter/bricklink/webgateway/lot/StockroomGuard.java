package io.legohunter.bricklink.webgateway.lot;

import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotGuardRefusedException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotNotFoundException;
import io.legohunter.bricklink.webgateway.lot.StockroomReader.Stockroom;
import org.springframework.stereotype.Component;

/**
 * Keeps a lower environment from touching lots it does not own.
 *
 * <p>Every environment shares one BrickLink account. A lower environment may only change lots that
 * are in a stockroom (so buyers cannot see them) and in its own stockroom letter. Production has no
 * stockroom restriction. An environment that is neither production nor given a letter may change
 * nothing.
 *
 * <p>The lot is checked twice before a write, from two independent sources: the official REST API,
 * and the website's own lot data read just before the change.
 */
@Component
public class StockroomGuard {

    private final StockroomReader reader;
    private final boolean production;
    private final String stockroomId;

    public StockroomGuard(StockroomReader reader, GatewayProperties properties) {
        this.reader = reader;
        this.production = properties.guard().production();
        String letter = properties.guard().stockroomId();
        this.stockroomId = letter == null ? "" : letter.trim().toUpperCase();
    }

    /**
     * First check, from the official REST API.
     *
     * @throws LotNotFoundException     if the API shows no such lot
     * @throws LotGuardRefusedException if the lot is not this environment's to change
     */
    public void checkAgainstApi(long lotId) {
        if (production) {
            return;
        }
        requireConfigured();
        Stockroom stockroom = reader.read(lotId).orElseThrow(() -> new LotNotFoundException(lotId));
        require(lotId, stockroom.inStockroom(), stockroom.stockroomId(), "the BrickLink API");
    }

    /** Second check, from the website's lot data read just before the change. */
    public void checkAgainstPage(LotState lot) {
        if (production) {
            return;
        }
        requireConfigured();
        require(lot.lotId(), lot.inStockroom(), lot.stockroomId(), "the BrickLink website");
    }

    private void requireConfigured() {
        if (stockroomId.isEmpty()) {
            throw new LotGuardRefusedException("This environment has no stockroom configured, so it may change no lot");
        }
    }

    private void require(long lotId, boolean inStockroom, String lotStockroom, String source) {
        if (!inStockroom) {
            throw new LotGuardRefusedException("Lot " + lotId + " is not in a stockroom according to " + source);
        }
        if (!stockroomId.equalsIgnoreCase(lotStockroom == null ? "" : lotStockroom.trim())) {
            throw new LotGuardRefusedException("Lot " + lotId + " is in stockroom "
                    + (lotStockroom == null || lotStockroom.isBlank() ? "<none>" : lotStockroom)
                    + " according to " + source + "; this environment may only change stockroom " + stockroomId);
        }
    }
}
