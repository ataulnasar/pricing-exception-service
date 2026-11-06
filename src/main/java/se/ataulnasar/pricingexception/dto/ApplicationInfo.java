package se.ataulnasar.pricingexception.dto;

/** A mortgage application as shown to the UI. */
public record ApplicationInfo(String id, String customerRef, int standardRateBps, String status, String ownerRmId) {
}
