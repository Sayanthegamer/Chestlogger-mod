package com.chestlogger.container;

/**
 * Supported container categorizations.
 */
public enum ContainerType {
    CHEST,
    DOUBLE_CHEST,
    TRAPPED_CHEST,
    BARREL,
    SHULKER_BOX,
    HOPPER,
    HOPPER_MINECART,
    DISPENSER,
    DROPPER,
    GENERIC_CONTAINER;

    public boolean isHopper() {
        return this == HOPPER || this == HOPPER_MINECART;
    }
}
