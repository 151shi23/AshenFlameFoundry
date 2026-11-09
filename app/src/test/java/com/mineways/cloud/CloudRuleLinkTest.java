package com.mineways.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 界面开关必须和现有规则引擎同源：
 *  - FLAG 的 if= / rollout / from-until 由 FeatureCatalog 统一处理；
 *  - THEME 预置能一次展开成整组 ui_show_*；
 *  - ui_* 既可用 flag_ui_xxx，也可用裸键 ui_xxx 参与后续规则。
 */
public class CloudRuleLinkTest {

    private static final String HEAD = "@file-version=9.9.9\n@ver=1\n";

    @Test
    public void uiFlagsHonorIfAndExposeConditionAlias() {
        FeatureCatalog.Result r = FeatureCatalog.parse(
                HEAD + "FLAG key=ui_show_nav to=0 if=\"sdk_int < 33\"\n", 522, "dev", null,
                new FeatureCatalog.CondEval() {
                    @Override
                    public boolean test(String expr) {
                        return true;
                    }
                });
        assertTrue(r.accepted);
        FeatureGate g = new FeatureGate(r, true);
        assertEquals("0", g.flag("ui_show_nav", "1"));
        Map<String, String> vars = g.condVars(new HashMap<String, String>());
        assertEquals("0", vars.get("ui_show_nav"));
        assertEquals("0", vars.get("flag_ui_show_nav"));
    }

    @Test
    public void failedConditionKeepsUiVisible() {
        FeatureCatalog.Result r = FeatureCatalog.parse(
                HEAD + "FLAG key=ui_show_search to=0 if=\"authorized\"\n", 522, "dev", null,
                new FeatureCatalog.CondEval() {
                    @Override
                    public boolean test(String expr) {
                        return false;
                    }
                });
        FeatureGate g = new FeatureGate(r, true);
        assertEquals("1", g.flag("ui_show_search", "1"));
    }

    @Test
    public void simpleThemeHidesExtraPanelsAndDescriptions() {
        FeatureCatalog.Result r = FeatureCatalog.parse(
                HEAD + "THEME set name=simple\n", 522, "dev", null,
                new FeatureCatalog.CondEval() {
                    @Override
                    public boolean test(String expr) {
                        return true;
                    }
                });
        FeatureGate g = new FeatureGate(r, true);
        assertEquals("0", g.flag("ui_show_quick", "1"));
        assertEquals("0", g.flag("ui_show_story", "1"));
        assertEquals("0", g.flag("ui_show_video", "1"));
        assertEquals("1", g.flag("ui_hide_desc", "0"));
    }

    @Test
    public void fullThemeRestoresEverything() {
        FeatureCatalog.Result r = FeatureCatalog.parse(
                HEAD + "THEME set name=simple\nTHEME set name=full\n", 522, "dev", null,
                new FeatureCatalog.CondEval() {
                    @Override
                    public boolean test(String expr) {
                        return true;
                    }
                });
        FeatureGate g = new FeatureGate(r, true);
        assertEquals("1", g.flag("ui_show_quick", "0"));
        assertEquals("1", g.flag("ui_show_story", "0"));
        assertEquals("0", g.flag("ui_hide_desc", "1"));
    }

    @Test
    public void laterRuleCanReadEarlierUiFlag() {
        FeatureCatalog.Result r = FeatureCatalog.parse(
                HEAD + "FLAG key=ui_show_nav to=0\n"
                        + "FLAG key=ui_show_ai to=0 if=\"flag_ui_show_nav == 0\"\n",
                522, "dev", null,
                new FeatureCatalog.CondEvalVars() {
                    @Override
                    public boolean test(String expr) {
                        return true;
                    }

                    @Override
                    public boolean test(String expr, Map<String, String> extra) {
                        return true;
                    }
                });
        FeatureGate g = new FeatureGate(r, true);
        assertEquals("0", g.flag("ui_show_nav", "1"));
        assertEquals("0", g.flag("ui_show_ai", "1"));
        assertFalse(r.rules.isEmpty());
    }
}
