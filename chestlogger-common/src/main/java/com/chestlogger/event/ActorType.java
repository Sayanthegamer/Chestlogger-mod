package com.chestlogger.event;

/**
 * Enumeration of actors initiating container interactions.
 */
public enum ActorType {
    PLAYER((byte) 0x00),
    HOPPER_BLOCK((byte) 0x01),
    HOPPER_MINECART((byte) 0x02),
    DROPPER_DISPENSER((byte) 0x03),
    AUTOMATION((byte) 0x04),
    ADMIN_COMMAND((byte) 0x05);

    private final byte wireId;

    ActorType(byte wireId) {
        this.wireId = wireId;
    }

    public byte getWireId() {
        return wireId;
    }

    public static ActorType fromWireId(byte id) {
        for (ActorType type : values()) {
            if (type.wireId == id) {
                return type;
            }
        }
        return PLAYER;
    }
}
