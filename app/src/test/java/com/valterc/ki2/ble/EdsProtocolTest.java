package com.valterc.ki2.ble;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests EdsProtocol packet decoding and building using real captured packets
 * from the eds-ox BLE tool log.
 */
public class EdsProtocolTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static byte[] hex(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    // -------------------------------------------------------------------------
    // Decode — real captured packets
    // -------------------------------------------------------------------------

    /**
     * Log line: "Send: getKey" → "Payload: fe 21 00 fe ee 00 92 85"
     * Parsed: u8=0xEF, key=0xEF, cmd=0x11, length=1, payload=[EF]
     */
    @Test
    public void decode_getKeyResponse() {
        byte[] raw = hex(0xfe, 0x21, 0x00, 0xfe, 0xee, 0x00, 0x92, 0x85);
        EdsPacket p = EdsProtocol.decode(raw);
        Assertions.assertNotNull(p, "Should decode successfully");
        Assertions.assertEquals(EdsProtocol.CMD_GET_KEY, p.cmd);
        Assertions.assertEquals(1, p.payload.length);
        Assertions.assertEquals(0xEF, p.payload[0] & 0xFF, "Session key should be 0xEF");
    }

    /**
     * Log line: "Send: startRead" → "Payload: fe 6e d3 c6 35 3c 1d 14 b9 6d 3c 3c 3c 3c 77 08"
     * Parsed: u8=0x3C, cmd=0xFA, length=9, payload=[00 21 28 85 51 00 00 00 00]
     */
    @Test
    public void decode_startReadResponse() {
        byte[] raw = hex(0xfe, 0x6e, 0xd3, 0xc6, 0x35, 0x3c, 0x1d, 0x14,
                         0xb9, 0x6d, 0x3c, 0x3c, 0x3c, 0x3c, 0x77, 0x08);
        EdsPacket p = EdsProtocol.decode(raw);
        Assertions.assertNotNull(p);
        Assertions.assertEquals(EdsProtocol.CMD_START_READ, p.cmd);
        Assertions.assertEquals(9, p.payload.length);
        byte[] expected = hex(0x00, 0x21, 0x28, 0x85, 0x51, 0x00, 0x00, 0x00, 0x00);
        Assertions.assertArrayEquals(expected, p.payload);
    }

    /**
     * Log line: "read block 0" → "Payload: fe 8c b5 d2 5c 15 5a 40 5a 59 5a bf 39"
     * Parsed: cmd=0x88 (frontStatusReport), length=6, payload=[4f 00 1a 00 03 00]
     * Logged as: FD gear 1
     */
    @Test
    public void decode_frontStatusReport() {
        byte[] raw = hex(0xfe, 0x8c, 0xb5, 0xd2, 0x5c, 0x15, 0x5a, 0x40,
                         0x5a, 0x59, 0x5a, 0xbf, 0x39);
        EdsPacket p = EdsProtocol.decode(raw);
        Assertions.assertNotNull(p);
        Assertions.assertEquals(EdsProtocol.CMD_FRONT_STATUS_REPORT, p.cmd);
        Assertions.assertEquals(6, p.payload.length);
        byte[] expected = hex(0x4f, 0x00, 0x1a, 0x00, 0x03, 0x00);
        Assertions.assertArrayEquals(expected, p.payload);
    }

    // -------------------------------------------------------------------------
    // Decode — error cases
    // -------------------------------------------------------------------------

    @Test
    public void decode_nullInput() {
        Assertions.assertNull(EdsProtocol.decode(null));
    }

    @Test
    public void decode_tooShort() {
        Assertions.assertNull(EdsProtocol.decode(hex(0xfe, 0x32, 0x00)));
    }

    @Test
    public void decode_wrongPrefix() {
        // Prefix must be 0xFE
        byte[] raw = hex(0x00, 0x32, 0x00, 0x11, 0x00, 0xab, 0xcd);
        Assertions.assertNull(EdsProtocol.decode(raw));
    }

    @Test
    public void decode_badCrc() {
        // getKey response with last byte flipped
        byte[] raw = hex(0xfe, 0x21, 0x00, 0xfe, 0xee, 0x00, 0x92, 0x00);
        Assertions.assertNull(EdsProtocol.decode(raw));
    }

    // -------------------------------------------------------------------------
    // Packet building — round-trip
    // -------------------------------------------------------------------------

    @Test
    public void buildPacket_roundTrip_emptyPayload() {
        int sessionKey = 0xEF;
        byte[] packet = EdsProtocol.buildStartReadPacket(sessionKey);
        // With CLIENT_VERSION=0x32, u8=0 on the receiving side when looped back,
        // but the device responds with a different version byte.
        // Just verify the structure here: FE 32 [key] [cmd] [len=0] [CRC16]
        Assertions.assertEquals(7, packet.length);
        Assertions.assertEquals(0xFE, packet[0] & 0xFF);
        Assertions.assertEquals(0x32, packet[1] & 0xFF);
        Assertions.assertEquals(sessionKey, packet[2] & 0xFF);
        Assertions.assertEquals(EdsProtocol.CMD_START_READ, packet[3] & 0xFF);
        Assertions.assertEquals(0, packet[4] & 0xFF); // len = 0
    }

    @Test
    public void buildPacket_rearShiftUp() {
        int sessionKey = 0xEF;
        byte[] packet = EdsProtocol.buildRearShiftPacket(sessionKey, EdsProtocol.SHIFT_UP);
        // FE 32 EF 99 01 02 [CRC16]
        Assertions.assertEquals(8, packet.length);
        Assertions.assertEquals(EdsProtocol.CMD_REAR_LIFTING, packet[3] & 0xFF);
        Assertions.assertEquals(1, packet[4] & 0xFF);        // len = 1
        Assertions.assertEquals(EdsProtocol.SHIFT_UP, packet[5] & 0xFF);
    }

    @Test
    public void buildPacket_rearShiftDown() {
        int sessionKey = 0xEF;
        byte[] packet = EdsProtocol.buildRearShiftPacket(sessionKey, EdsProtocol.SHIFT_DOWN);
        Assertions.assertEquals(EdsProtocol.SHIFT_DOWN, packet[5] & 0xFF);
    }

    @Test
    public void buildPacket_readBlock() {
        int sessionKey = 0xEF;
        byte[] packet = EdsProtocol.buildReadBlockPacket(sessionKey, 3);
        // FE 32 EF FB 03 00 03 51 [CRC16]
        Assertions.assertEquals(10, packet.length);
        Assertions.assertEquals(EdsProtocol.CMD_READ, packet[3] & 0xFF);
        Assertions.assertEquals(3, packet[4] & 0xFF);        // len = 3
        Assertions.assertEquals(0x00, packet[5] & 0xFF);
        Assertions.assertEquals(3, packet[6] & 0xFF);        // block index
        Assertions.assertEquals(0x51, packet[7] & 0xFF);
    }

    @Test
    public void buildGetKeyPacket_hasCorrectStructure() {
        byte[] packet = EdsProtocol.buildGetKeyPacket();
        Assertions.assertEquals(15, packet.length);
        Assertions.assertEquals(0xFE, packet[0] & 0xFF);
        Assertions.assertEquals(0x32, packet[1] & 0xFF);
        Assertions.assertEquals(0x29, packet[2] & 0xFF);
        Assertions.assertEquals(EdsProtocol.CMD_GET_KEY, packet[3] & 0xFF);
        Assertions.assertEquals(8, packet[4] & 0xFF); // payload length = 8
        // Challenge: "yOTmK50z"
        Assertions.assertEquals('y', (char) (packet[5] & 0xFF));
        Assertions.assertEquals('O', (char) (packet[6] & 0xFF));
        Assertions.assertEquals('T', (char) (packet[7] & 0xFF));
    }

    // -------------------------------------------------------------------------
    // ASCII block handling
    // -------------------------------------------------------------------------

    /**
     * Log: "Payload: 8c 54 00 00 4c 5f 56 65 72 3a 33 2e 32 35 2c 4c 5f 50 4f 57"
     * Block 0, content = "L_Ver:3.25,L_POW"
     */
    @Test
    public void parseAsciiBlock_block0() {
        byte[] raw = hex(0x8c, 0x54, 0x00, 0x00,
                         'L', '_', 'V', 'e', 'r', ':', '3', '.', '2', '5', ',', 'L', '_', 'P', 'O', 'W');
        Assertions.assertTrue(EdsProtocol.isAsciiBlock(raw));
        Assertions.assertEquals(0, EdsProtocol.parseAsciiBlockIndex(raw));
        Assertions.assertEquals("L_Ver:3.25,L_POW", EdsProtocol.parseAsciiBlock(raw));
    }

    /**
     * Log: "Payload: a4 93 00 01 45 52 3a 32 36 39 2c 52 5f 56 65 72 3a 34 2e 33"
     * Block 1, content = "ER:269,R_Ver:4.3"
     */
    @Test
    public void parseAsciiBlock_block1() {
        byte[] raw = hex(0xa4, 0x93, 0x00, 0x01,
                         'E', 'R', ':', '2', '6', '9', ',', 'R', '_', 'V', 'e', 'r', ':', '4', '.', '3');
        Assertions.assertTrue(EdsProtocol.isAsciiBlock(raw));
        Assertions.assertEquals(1, EdsProtocol.parseAsciiBlockIndex(raw));
        Assertions.assertEquals("ER:269,R_Ver:4.3", EdsProtocol.parseAsciiBlock(raw));
    }

    @Test
    public void parseAsciiBlock_notAsciiBlock() {
        // A structured packet (starts with 0xFE) is NOT an ASCII block
        byte[] raw = hex(0xfe, 0x21, 0x00, 0xfe, 0xee, 0x00, 0x92, 0x85,
                         0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                         0x00, 0x00, 0x00, 0x00);
        Assertions.assertFalse(EdsProtocol.isAsciiBlock(raw));
    }

    // -------------------------------------------------------------------------
    // Device info string parsing
    // -------------------------------------------------------------------------

    @Test
    public void parseInfoValue_leftVersion() {
        String info = "L_Ver:3.25,L_POWER:269,R_Ver:4.3,R_POWER:312,TOTAL_CNT:11";
        Assertions.assertEquals("3.25", EdsProtocol.parseInfoValue(info, "L_Ver"));
    }

    @Test
    public void parseInfoValue_rightVersion() {
        String info = "L_Ver:3.25,L_POWER:269,R_Ver:4.3,R_POWER:312,TOTAL_CNT:11";
        Assertions.assertEquals("4.3", EdsProtocol.parseInfoValue(info, "R_Ver"));
    }

    @Test
    public void parseInfoValue_leftPower() {
        String info = "L_Ver:3.25,L_POWER:269,R_Ver:4.3,R_POWER:312,TOTAL_CNT:11";
        Assertions.assertEquals("269", EdsProtocol.parseInfoValue(info, "L_POWER"));
    }

    @Test
    public void parseInfoValue_totalGears() {
        String info = "L_Ver:3.25,L_POWER:269,R_Ver:4.3,R_POWER:312,TOTAL_CNT:11";
        Assertions.assertEquals("11", EdsProtocol.parseInfoValue(info, "TOTAL_CNT"));
    }

    @Test
    public void parseInfoValue_missingKey() {
        String info = "L_Ver:3.25,TOTAL_CNT:11";
        Assertions.assertNull(EdsProtocol.parseInfoValue(info, "R_Ver"));
    }

    @Test
    public void parseInfoValue_lastKeyNoTrailingComma() {
        String info = "L_Ver:3.25,R_Ver:4.3";
        Assertions.assertEquals("4.3", EdsProtocol.parseInfoValue(info, "R_Ver"));
    }
}
