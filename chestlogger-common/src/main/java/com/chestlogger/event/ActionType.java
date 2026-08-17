package com.chestlogger.event;

/**
 * Enumeration of discrete container interaction actions.
 */
public enum ActionType {
    CONTAINER_OPEN((byte) 0x00),
    CONTAINER_CLOSE((byte) 0x01),
    PICKUP((byte) 0x02),
    PLACE((byte) 0x03),
    SHIFT_CLICK_EXTRACT((byte) 0x04),
    SHIFT_CLICK_INSERT((byte) 0x05),
    HOTBAR_SWAP((byte) 0x06),
    DRAG_SPLIT((byte) 0x07),
    DOUBLE_CLICK_COLLECT((byte) 0x08),
    HOPPER_EXTRACT((byte) 0x09),
    HOPPER_INSERT((byte) 0x0A),
    DROP_FROM_SLOT((byte) 0x0B),
    ROLLBACK_COMPENSATION((byte) 0x0C),
    CONTAINER_BREAK((byte) 0x0D),
    CONTAINER_PLACE((byte) 0x0E),
    CRAFTER_CRAFT((byte) 0x0F);

    private final byte wireId;

    ActionType(byte wireId) {
        this.wireId = wireId;
    }

    public byte getWireId() {
        return wireId;
    }

    public static ActionType fromWireId(byte id) {
        for (ActionType type : values()) {
            if (type.wireId == id) {
                return type;
            }
        }
        return PICKUP;
    }
}
