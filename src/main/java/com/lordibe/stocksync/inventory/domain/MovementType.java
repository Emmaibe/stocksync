package com.lordibe.stocksync.inventory.domain;

/**
 * The reason a quantity changed, and with it the sign the quantity must carry.
 *
 * <p>Enforced identically by a check constraint on {@code stock_movement}, so a
 * bad sign cannot reach the ledger even by direct SQL.
 */
public enum MovementType {

    /** Stock arriving from a supplier or production. */
    RECEIPT(Sign.POSITIVE),

    /** Stock leaving to a customer. */
    SALE(Sign.NEGATIVE),

    /** Count correction, damage, or shrinkage. May go either way. */
    ADJUSTMENT(Sign.EITHER),

    /** Receiving leg of an internal transfer. */
    TRANSFER_IN(Sign.POSITIVE),

    /** Sending leg of an internal transfer. */
    TRANSFER_OUT(Sign.NEGATIVE);

    private final Sign sign;

    MovementType(Sign sign) {
        this.sign = sign;
    }

    public boolean isTransferLeg() {
        return this == TRANSFER_IN || this == TRANSFER_OUT;
    }

    /** True when {@code quantity} carries a sign this movement type allows. */
    public boolean permits(java.math.BigDecimal quantity) {
        if (quantity == null || quantity.signum() == 0) {
            return false;
        }
        return switch (sign) {
            case POSITIVE -> quantity.signum() > 0;
            case NEGATIVE -> quantity.signum() < 0;
            case EITHER -> true;
        };
    }

    private enum Sign {
        POSITIVE, NEGATIVE, EITHER
    }
}
