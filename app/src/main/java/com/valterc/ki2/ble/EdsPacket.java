package com.valterc.ki2.ble;

public final class EdsPacket {

    public final int cmd;
    public final byte[] payload;

    public EdsPacket(int cmd, byte[] payload) {
        this.cmd = cmd;
        this.payload = payload != null ? payload.clone() : new byte[0];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("EdsPacket{cmd=0x%02X, payload=[", cmd));
        for (int i = 0; i < payload.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", payload[i]));
        }
        sb.append("]}");
        return sb.toString();
    }
}
