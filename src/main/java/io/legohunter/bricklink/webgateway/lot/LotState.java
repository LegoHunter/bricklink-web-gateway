package io.legohunter.bricklink.webgateway.lot;

/**
 * One store lot as the BrickLink website reports it.
 *
 * @param inStockroom         the lot is in a stockroom, so buyers cannot see it
 * @param stockroomId         {@code A}, {@code B} or {@code C}; empty when not in a stockroom
 * @param customImage         the lot has a custom image (as opposed to the catalog picture)
 * @param thumbnailUrl        the thumbnail the website shows for the lot; may be {@code null}
 * @param extendedDescription the lot's Extended Description; empty when it has none
 */
public record LotState(long lotId, boolean inStockroom, String stockroomId, boolean customImage,
                       String thumbnailUrl, String extendedDescription) {
}
