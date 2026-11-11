package com.mineways.prism;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.mineways.cloud.FeatureCatalog;
import com.mineways.cloud.FeatureGate;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;

/**
 * 棱镜功能表登记进门面之后，云端应该能用与首页条目一致的写法控制本页：
 * hide / disable / label / desc / pin / order，以及中文键与功能 id 别名两种写法。
 */
public class PrismCloudTest {

    @BeforeClass
    public static void register() {
        PrismFeatures.registerCloud();
    }

    private static FeatureGate gate(String text) {
        FeatureCatalog.Result r = FeatureCatalog.parseChain(
                Collections.singletonList(text), 529, "unit-test-device", null, null);
        assertNotNull("清单不应解析失败", r);
        return new FeatureGate(r, true);
    }

    private static FeatureGate.State state(FeatureGate g, String id) {
        return g.stateOf(PrismFeatures.cloudKey(id),
                PrismFeatures.CLOUD_CATEGORY, FeatureGate.KIND_FREE);
    }

    @Test
    public void everyFeatureGetsItsOwnCloudKey() {
        for (PrismFeatures.Feature f : PrismFeatures.all()) {
            String key = PrismFeatures.cloudKey(f.id);
            assertNotNull(f.id, key);
            assertTrue("登记键不能为空：" + f.id, key.length() > 0);
            assertTrue("登记键应该能被门面认出来：" + f.id,
                    FeatureGate.keySpace().keys().contains(key));
        }
        assertTrue("整类下架用的类别要登记",
                FeatureGate.keySpace().categories().contains(
                        FeatureCatalog.norm(PrismFeatures.CLOUD_CATEGORY)));
    }

    @Test
    public void hideByChineseTitle() {
        FeatureGate.State s = state(gate("FEAT id=不锁屏 hide override=builtin"), "no_lock");
        assertFalse("云端隐藏后本页不该再渲染", s.visible);
    }

    @Test
    public void hideByIdAlias() {
        FeatureGate.State s = state(gate("FEAT id=no_lock hide override=builtin"), "no_lock");
        assertFalse("功能 id 也是别名，作者不用记中文标题", s.visible);
    }

    @Test
    public void disableCarriesNotice() {
        FeatureGate.State s = state(gate(
                "FEAT id=不锁屏 disable notice=维护中 override=builtin"), "no_lock");
        assertTrue(s.visible);
        assertFalse("停用只置灰，不应该直接消失", s.usable);
        assertEquals("维护中", s.notice);
    }

    @Test
    public void labelDescPinOrderAreApplied() {
        FeatureGate.State s = state(gate(
                "FEAT id=不锁屏 label=常亮屏 desc=云端说明 pin order=1 override=builtin"),
                "no_lock");
        assertEquals("常亮屏", s.label);
        assertEquals("云端说明", s.desc);
        assertTrue(s.pinned);
        assertEquals(1, s.order);
    }

    @Test
    public void droppingCategoryHidesWholePage() {
        FeatureGate g = gate("CAT id=棱镜玩机 drop");
        assertFalse("整类下架后所有条目都不可见", state(g, "no_lock").visible);
        assertFalse(state(g, "app_actions").visible);
    }

    @Test
    public void unknownIdFallsBackToItself() {
        assertEquals("not_a_feature", PrismFeatures.cloudKey("not_a_feature"));
    }
}
