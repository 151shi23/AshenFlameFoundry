package com.mineways.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mineways.DeviceId;

import org.junit.Test;

import java.util.Arrays;

/**
 * 云控 UI 开关的出厂行为：没有云端配置时必须全显示、文案原样透传。
 * 这组测试挡住「配置写坏 → 整个首页空白」这类事故。
 */
public class UiFlagsTest {

    @Test
    public void defaultsAreAllVisible() {
        assertTrue(UiFlags.show(UiFlags.NAV));
        assertTrue(UiFlags.show(UiFlags.TOPBAR));
        assertTrue(UiFlags.show(UiFlags.SEARCH));
        assertTrue(UiFlags.show(UiFlags.AI));
        assertTrue(UiFlags.show(UiFlags.PAGEGLASS));
        assertTrue(UiFlags.descVisible());
    }

    @Test
    public void descriptionsPassThroughByDefault() {
        assertEquals("默认副标题", UiFlags.desc("tool_prism", "默认副标题"));
        assertEquals("默认说明", UiFlags.desc("intro", "默认说明"));
    }

    @Test
    public void pagesAreVisibleByDefault() {
        assertFalse(UiFlags.pageHidden("prism"));
        assertFalse(UiFlags.pageHidden("mocap"));
        assertFalse(UiFlags.pageHidden(""));
        assertFalse(UiFlags.pageHidden(null));
    }

    @Test
    public void modelPatternsUseWildcardFindSemantics() {
        assertNotNull(DeviceId.match("K70*", Arrays.asList("redmi k70 pro")));
        assertNotNull(DeviceId.match("2210*", Arrays.asList("xiaomi 2210132c")));
        assertNull(DeviceId.match("K80*", Arrays.asList("redmi k70 pro")));
        assertNull(DeviceId.match("", Arrays.asList("redmi k70 pro")));
    }

    @Test
    public void packagePatternsSupportExactAndWildcard() {
        assertTrue(PkgProbe.matchAny("com.tencent.mm",
                Arrays.asList("com.tencent.mm", "com.android.settings")));
        assertTrue(PkgProbe.matchAny("com.tencent.*",
                Arrays.asList("com.tencent.mm", "com.android.settings")));
        assertFalse(PkgProbe.matchAny("com.omarea.vtools",
                Arrays.asList("com.tencent.mm")));
        assertFalse(PkgProbe.matchAny("", Arrays.asList("com.tencent.mm")));
    }

    @Test
    public void listParsingHandlesChineseSeparatorsAndCase() {
        // 没有云端配置时列表为空；键规范化必须稳定
        assertTrue(UiFlags.names("ui_hide_pages").isEmpty());
        assertEquals("prism", UiFlags.key(" PRISM "));
        assertEquals("a_b", UiFlags.key("a/b"));
        assertEquals("", UiFlags.key("棱镜"));
    }
}
