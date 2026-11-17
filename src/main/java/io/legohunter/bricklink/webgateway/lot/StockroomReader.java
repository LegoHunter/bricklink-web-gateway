package io.legohunter.bricklink.webgateway.lot;

import java.util.Optional;

/** Reads a lot's stockroom from an authoritative source, for the environment guard. */
public interface StockroomReader {

    /**
     * @return the lot's stockroom state, or empty if the lot does not exist for the account
     * @throws LotExceptions.GuardUnavailableException if the source could not be asked
     */
    Optional<Stockroom> read(long lotId);

    /** @param stockroomId {@code A}, {@code B} or {@code C}; may be empty when not in a stockroom */
    record Stockroom(boolean inStockroom, String stockroomId) {
    }
}
