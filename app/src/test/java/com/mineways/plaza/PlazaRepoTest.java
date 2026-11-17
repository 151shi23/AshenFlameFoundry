package com.mineways.plaza;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 资源仓库 URL/Release 隔离规则。 */
public class PlazaRepoTest {

    @Test
    public void onlyExactReleasePathIsTrusted() {
        String tag = "res-map-skyblock-v1.0";
        String asset = "map-skyblock.zip";
        String url = PlazaRepo.releaseUrl(tag, asset);
        assertTrue(PlazaRepo.trustedReleaseUrl(url, tag, asset));
        assertFalse(PlazaRepo.trustedReleaseUrl(
                "https://github.com/151shi23/AshenFlameFoundry/releases/download/" + tag + "/" + asset,
                tag, asset));
        assertFalse(PlazaRepo.trustedReleaseUrl(url, tag, "other.zip"));
        assertFalse(PlazaRepo.trustedReleaseUrl(
                url.replace("MinewaysMobile-Resources", "AshenFlameFoundry"), tag, asset));
    }

    @Test
    public void previewMustStayInSameRelease() {
        String tag = "res-model-ysm-girl-v1.1";
        String ok = PlazaRepo.releaseUrl(tag, "preview.glb");
        assertTrue(PlazaRepo.trustedPreview(ok, tag));
        assertFalse(PlazaRepo.trustedPreview(
                PlazaRepo.releaseUrl("res-model-ysm-girl-v1.0", "preview.glb"), tag));
        assertFalse(PlazaRepo.trustedPreview(
                "https://github.com/151shi23/MinewaysMobile-Resources/releases/download/"
                        + tag + "/../other.glb", tag));
    }

    @Test
    public void releaseTagIsImmutablePattern() {
        assertTrue(PlazaRepo.validTag("res-map-skyblock-v1.0"));
        assertFalse(PlazaRepo.validTag("latest"));
        assertFalse(PlazaRepo.validTag("res-map-skyblock-v1.0-extra"));
        assertTrue(PlazaRepo.validSha(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
    }
}
