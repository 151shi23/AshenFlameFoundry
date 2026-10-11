package com.mineways;

/**
 * 离线种子地图 · native 桥（cubiomes，MIT License，© 2020 Cubitect）。
 *
 * <p>坐标约定：带 {@code scale} 的接口里，所有坐标都是「方块坐标 / scale」，
 * 例：scale=4 → 传 blockX&gt;&gt;2；scale=16 → 传 blockX&gt;&gt;4。</p>
 *
 * <p>整个计算过程不联网：只按种子和版本推算群系/结构，和游戏内生成规则一致。</p>
 */
public final class SeedMapNative {

    private static boolean loaded;
    private static String loadError = "";

    private SeedMapNative() {
    }

    /** 载入 native 库（失败时把原因记下来，界面提示用）。 */
    public static synchronized boolean load() {
        if (loaded) {
            return true;
        }
        try {
            System.loadLibrary("seedmap");
            loaded = true;
        } catch (Throwable t) {
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return loaded;
    }

    public static boolean available() {
        return load();
    }

    public static String loadError() {
        return loadError;
    }

    // ---------------------------------------------------------------- 原生

    /** 建生成器；失败返回 0。 */
    public static native long nativeCreate(String mcKey, int dim, long seed);

    public static native void nativeFree(long ptr);

    public static native int nativeBiomeAt(long ptr, int scale, int x, int y, int z);

    /** 区域群系 id 表（长度 w*h，行优先）。 */
    public static native int[] nativeMap(long ptr, int scale, int x0, int z0, int w, int h, int y);

    /** 结构：返回 {类型, x, z, 类型, x, z, ...}（类型值见 finders.h 的 StructureType）。 */
    public static native int[] nativeStructures(long ptr, String mcKey, long seed,
                                                int dim, int cx, int cz, int radius, int maxOut);

    /** 要塞：返回 {x, z, x, z, ...}（需主世界生成器）。 */
    public static native int[] nativeStrongholds(long ptr, String mcKey, long seed,
                                                 int cx, int cz, int radius, int maxOut);

    /** 出生点（估算）：{x, z}。 */
    public static native int[] nativeSpawn(long ptr);

    public static native boolean nativeSlimeChunk(long seed, int chunkX, int chunkZ);
}
