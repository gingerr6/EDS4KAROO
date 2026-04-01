package com.valterc.ki2.ble;

import java.util.UUID;

/**
 * EDS (Electronic Derailleur System) BLE protocol implementation.
 *
 * Transport: Nordic UART Service (NUS) over BLE GATT.
 *
 * Packet structure (client → device):
 *   [0]     0xFE       fixed prefix
 *   [1]     0x32       client version byte; u8 = (0x32 - 50) & 0xFF = 0 → no XOR for client packets
 *   [2]     sessionKey session key obtained from getKey handshake
 *   [3]     cmd        command byte
 *   [4]     len        payload length
 *   [5..N]  payload    payload bytes
 *   [N+1]   CRC16 high
 *   [N+2]   CRC16 low
 *
 * Packet structure (device → client):
 *   [0]     0xFE       fixed prefix
 *   [1]     versionByte  u8 = (byte[1] - 50) & 0xFF  (varies per packet for obfuscation)
 *   [2]     key ^ u8   session key XOR'd with u8
 *   [3]     cmd ^ u8   command XOR'd with u8
 *   [4]     len ^ u8   length XOR'd with u8
 *   [5..N]  payload[i] ^ u8 for each byte
 *   [N+1]   CRC16 high
 *   [N+2]   CRC16 low
 *
 * ASCII data blocks (device info, config) do NOT use this structure —
 * they are raw 20-byte chunks with a 4-byte header and 16-byte ASCII content.
 */
public final class EdsProtocol {

    // Nordic UART Service UUIDs
    public static final UUID UUID_NUS_SERVICE =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    /** Write characteristic (client → device). */
    public static final UUID UUID_NUS_RX =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    /** Notify characteristic (device → client). */
    public static final UUID UUID_NUS_TX =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    /** Client Characteristic Configuration Descriptor — used to enable notifications. */
    public static final UUID UUID_CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** All EDS devices advertise a name starting with this prefix. */
    public static final String DEVICE_NAME_PREFIX = "EDS";

    // -------------------------------------------------------------------------
    // Command constants
    // -------------------------------------------------------------------------
    public static final int CMD_GET_KEY                    = 0x11;
    public static final int CMD_GET_LOCK_INFO              = 0x31;
    public static final int CMD_GET_POWER_INFO             = 0x42;
    public static final int CMD_GET_TRANSMISSION_VERSION   = 0x60;
    public static final int CMD_GET_FRONT_REAR_GEAR_VALUES = 0x61;
    public static final int CMD_BACK_DIAL_SLEEP            = 0x63;
    public static final int CMD_BACK_SETTING_2P            = 0x66;
    public static final int CMD_BACK_SETTING_1P            = 0x67;
    public static final int CMD_SET_FRONT_GEAR_LIMIT       = 0x82;
    public static final int CMD_FINE_TUNE_FRONT_GEAR       = 0x85;
    public static final int CMD_GET_DEVICE_MAC             = 0x86;
    /** Front derailleur gear position notification (device → client). */
    public static final int CMD_FRONT_STATUS_REPORT        = 0x88;
    /** Shift front derailleur (client → device). */
    public static final int CMD_FRONT_LIFTING              = 0x89;
    public static final int CMD_SHUTDOWN                   = 0x90;
    public static final int CMD_SET_TOTAL_GEAR             = 0x91;
    public static final int CMD_SET_GEAR_UP_VALUE          = 0x92;
    public static final int CMD_SET_PROTECTION_THRESHOLD   = 0x93;
    public static final int CMD_FINE_TUNE_GEAR             = 0x95;
    public static final int CMD_GET_CURRENT_GEAR           = 0x97;
    /** Rear derailleur gear change notification (device → client). */
    public static final int CMD_SERVER_REPORT_GEAR         = 0x98;
    /** Shift rear derailleur (client → device). */
    public static final int CMD_REAR_LIFTING               = 0x99;
    public static final int CMD_SWITCH_FINGER_ORDER        = 0x9C;
    /** Begin ASCII data block read session. */
    public static final int CMD_START_READ                 = 0xFA;
    /** Read one ASCII data block. */
    public static final int CMD_READ                       = 0xFB;

    // Shift direction
    public static final int SHIFT_DOWN = 0x01;
    public static final int SHIFT_UP   = 0x02;

    // -------------------------------------------------------------------------
    // Packet framing constants
    // -------------------------------------------------------------------------
    private static final int PACKET_PREFIX  = 0xFE;
    private static final int CLIENT_VERSION = 0x32; // (0x32 - 50) & 0xFF == 0 → u8 = 0

    /**
     * Hardcoded getKey request packet. The payload is the fixed challenge string "yOTmK50z".
     * This is the very first packet sent on every connection.
     */
    private static final byte[] GET_KEY_PACKET;

    static {
        byte[] raw = {
            (byte) 0xfe, (byte) 0x32, (byte) 0x29, (byte) 0x11, (byte) 0x08,
            (byte) 0x79, (byte) 0x4f, (byte) 0x54, (byte) 0x6d, (byte) 0x4b,
            (byte) 0x35, (byte) 0x30, (byte) 0x7a, (byte) 0x00, (byte) 0x00
        };
        applyCrc16(raw);
        GET_KEY_PACKET = raw;
    }

    private EdsProtocol() {}

    // -------------------------------------------------------------------------
    // Packet builders
    // -------------------------------------------------------------------------

    /** Returns a copy of the hardcoded getKey request packet. */
    public static byte[] buildGetKeyPacket() {
        return GET_KEY_PACKET.clone();
    }

    /** Build a startRead packet to begin the ASCII data block read session. */
    public static byte[] buildStartReadPacket(int sessionKey) {
        return buildPacket(sessionKey, CMD_START_READ, null);
    }

    /**
     * Build a read-block packet to request one 20-byte ASCII data chunk.
     *
     * @param sessionKey  Established session key.
     * @param blockIndex  Zero-based block index to request.
     */
    public static byte[] buildReadBlockPacket(int sessionKey, int blockIndex) {
        return buildPacket(sessionKey, CMD_READ,
                new byte[]{0x00, (byte) blockIndex, 0x51});
    }

    /**
     * Build a rear derailleur shift packet.
     *
     * @param sessionKey Established session key.
     * @param direction  {@link #SHIFT_UP} or {@link #SHIFT_DOWN}.
     */
    public static byte[] buildRearShiftPacket(int sessionKey, int direction) {
        return buildPacket(sessionKey, CMD_REAR_LIFTING, new byte[]{(byte) direction});
    }

    /**
     * Build a front derailleur shift packet.
     *
     * @param sessionKey Established session key.
     * @param direction  {@link #SHIFT_UP} or {@link #SHIFT_DOWN}.
     */
    public static byte[] buildFrontShiftPacket(int sessionKey, int direction) {
        return buildPacket(sessionKey, CMD_FRONT_LIFTING, new byte[]{(byte) direction});
    }

    /**
     * Build a generic packet (unobfuscated, u8 = 0).
     *
     * @param sessionKey Session key (0 if not yet established).
     * @param cmd        Command byte.
     * @param payload    Payload bytes, or null for empty payload.
     */
    public static byte[] buildPacket(int sessionKey, int cmd, byte[] payload) {
        int len = payload != null ? payload.length : 0;
        byte[] packet = new byte[5 + len + 2];
        packet[0] = (byte) PACKET_PREFIX;
        packet[1] = (byte) CLIENT_VERSION; // u8 = 0, so all fields are unmasked
        packet[2] = (byte) sessionKey;
        packet[3] = (byte) cmd;
        packet[4] = (byte) len;
        if (len > 0) {
            System.arraycopy(payload, 0, packet, 5, len);
        }
        applyCrc16(packet);
        return packet;
    }

    // -------------------------------------------------------------------------
    // Packet decoder
    // -------------------------------------------------------------------------

    /**
     * Decode an incoming device notification.
     *
     * <p>Returns null for ASCII data blocks (which are handled separately via
     * {@link #parseAsciiBlock}) or for malformed / CRC-failed packets.
     *
     * @param raw Raw bytes received on the NUS TX (notify) characteristic.
     */
    public static EdsPacket decode(byte[] raw) {
        if (raw == null || raw.length < 7) return null;
        if ((raw[0] & 0xFF) != PACKET_PREFIX) return null;

        int u8  = ((raw[1] & 0xFF) - 50) & 0xFF;
        int cmd = (raw[3] & 0xFF) ^ u8;
        int len = (raw[4] & 0xFF) ^ u8;

        if (5 + len + 2 != raw.length) return null;

        int expected = crc16(raw, raw.length - 2);
        int actual   = ((raw[raw.length - 2] & 0xFF) << 8) | (raw[raw.length - 1] & 0xFF);
        if (expected != actual) return null;

        byte[] payload = new byte[len];
        for (int i = 0; i < len; i++) {
            payload[i] = (byte) ((raw[5 + i] & 0xFF) ^ u8);
        }
        return new EdsPacket(cmd, payload);
    }

    // -------------------------------------------------------------------------
    // ASCII block handling
    // -------------------------------------------------------------------------

    /**
     * Structure of raw 20-byte ASCII data blocks returned by the device:
     *   [0..1]  2-byte checksum (not verified, matches CRC pattern)
     *   [2]     0x00 (constant)
     *   [3]     block index (0-based)
     *   [4..19] up to 16 bytes of ASCII text (may be null-padded)
     */
    public static final int ASCII_BLOCK_HEADER_SIZE = 4;
    public static final int ASCII_BLOCK_CONTENT_SIZE = 16;
    public static final int ASCII_BLOCK_TOTAL_SIZE = 20;

    /**
     * Returns true if the raw bytes look like an ASCII data block rather than a structured packet.
     * ASCII blocks don't start with 0xFE.
     */
    public static boolean isAsciiBlock(byte[] raw) {
        if (raw == null || raw.length < ASCII_BLOCK_TOTAL_SIZE) return false;
        return (raw[0] & 0xFF) != PACKET_PREFIX;
    }

    /**
     * Extract the ASCII text content from a raw 20-byte data block.
     *
     * @return The trimmed ASCII string (16 bytes max, null-padded bytes stripped).
     */
    public static String parseAsciiBlock(byte[] raw) {
        if (raw == null || raw.length < ASCII_BLOCK_TOTAL_SIZE) return "";
        int end = ASCII_BLOCK_HEADER_SIZE + ASCII_BLOCK_CONTENT_SIZE;
        int len = 0;
        for (int i = ASCII_BLOCK_HEADER_SIZE; i < end; i++) {
            if (raw[i] == 0) break;
            len++;
        }
        return new String(raw, ASCII_BLOCK_HEADER_SIZE, len,
                java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Extract the block index from a raw ASCII data block header.
     */
    public static int parseAsciiBlockIndex(byte[] raw) {
        if (raw == null || raw.length < 4) return -1;
        return raw[3] & 0xFF;
    }

    // -------------------------------------------------------------------------
    // Device info string parsing
    // -------------------------------------------------------------------------

    /**
     * Parse a device info value from the concatenated ASCII config string.
     *
     * <p>Format: {@code KEY:value,KEY:value,...}
     *
     * @param info The concatenated ASCII config string.
     * @param key  The key to look up (e.g. "L_Ver", "R_Ver", "L_POWER", "R_POWER").
     * @return The value string, or null if not found.
     */
    public static String parseInfoValue(String info, String key) {
        if (info == null || key == null) return null;
        String search = key + ":";
        int start = info.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = info.indexOf(',', start);
        return end < 0 ? info.substring(start) : info.substring(start, end);
    }

    // -------------------------------------------------------------------------
    // CRC16-Modbus (polynomial 0x8005, reflected input/output, init 0xFFFF)
    // -------------------------------------------------------------------------

    private static int crc16(byte[] data, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    /** Write the CRC16 of bytes [0..length-3] into the last two bytes of {@code packet}. */
    private static void applyCrc16(byte[] packet) {
        int crc = crc16(packet, packet.length - 2);
        packet[packet.length - 2] = (byte) ((crc >> 8) & 0xFF);
        packet[packet.length - 1] = (byte) (crc & 0xFF);
    }
}
