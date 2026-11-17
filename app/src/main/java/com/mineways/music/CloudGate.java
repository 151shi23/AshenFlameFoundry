package com.mineways.music;

import com.mineways.cloud.CloudCatalog;

/** 云控开关的单一读口：所有 music_* 都从这里进，免得各处自己拼 FeatureGate。 */
final class CloudGate {

    private CloudGate() {
    }

    static String flag(String key, String def) {
        return com.mineways.cloud.CloudFlags.str(key, def);
    }
}
