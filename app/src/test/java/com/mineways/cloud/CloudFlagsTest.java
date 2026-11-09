package com.mineways.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 云控解析层语义测试：布尔词表、默认回退、整数夹取。 */
public class CloudFlagsTest {

    @Test
    public void boolAcceptsCommonWords() {
        assertTrue(CloudFlags.parseBool("1", false));
        assertTrue(CloudFlags.parseBool("true", false));
        assertTrue(CloudFlags.parseBool("TRUE", false));
        assertTrue(CloudFlags.parseBool(" on ", false));
        assertTrue(CloudFlags.parseBool("yes", false));

        assertFalse(CloudFlags.parseBool("0", true));
        assertFalse(CloudFlags.parseBool("false", true));
        assertFalse(CloudFlags.parseBool("OFF", true));
        assertFalse(CloudFlags.parseBool("no", true));
    }

    @Test
    public void boolFallsBackOnEmptyOrGarbage() {
        assertTrue(CloudFlags.parseBool("", true));
        assertFalse(CloudFlags.parseBool("", false));
        assertTrue(CloudFlags.parseBool("garbage", true));
        assertFalse(CloudFlags.parseBool("garbage", false));
        assertTrue(CloudFlags.parseBool(null, true));
        assertFalse(CloudFlags.parseBool(null, false));
    }

    @Test
    public void numberParsesAndClamps() {
        assertEquals(6185, CloudFlags.parseNum("6185", 0, 0, 65535));
        assertEquals(1024, CloudFlags.parseNum("80", 0, 1024, 65535));
        assertEquals(65535, CloudFlags.parseNum("70000", 0, 0, 65535));
        assertEquals(0, CloudFlags.parseNum("-5", 9, 0, 65535));
    }

    @Test
    public void numberFallsBackOnEmptyOrGarbage() {
        assertEquals(42, CloudFlags.parseNum("oops", 42, 0, 100));
        assertEquals(42, CloudFlags.parseNum("", 42, 0, 100));
        assertEquals(42, CloudFlags.parseNum(null, 42, 0, 100));
        assertEquals(42, CloudFlags.parseNum("   ", 42, 0, 100));
    }
}
