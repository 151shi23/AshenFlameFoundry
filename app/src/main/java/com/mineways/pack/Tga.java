package com.mineways.pack;

/**
 * TGA 解码。基岩材质包里有一部分贴图是 {@code .tga}（Faithful 64x 的 74 张，含 kelp 全系动画源图），
 * Java 版游戏和 Android 的 BitmapFactory 都不认 TGA，所以只能自己解成 ARGB 再重编码成 PNG。
 *
 * <p>TGA 的 image type 语义（规范里就这三个基数，加上 RLE 各 +8）：
 * {@code 1}=索引色(带调色板)、{@code 2}=真彩色、{@code 3}=灰度；{@code 9/10/11} 是它们的 RLE 版。
 * 实测这个包只有 {@code 2} 与 {@code 10}（真彩色，非压缩 / RLE），bpp 24/32。
 *
 * <p>位深 15/16/24/32，头部第 17 字节的 bit5 决定起跑是「左上」还是「左下」，bit4 决定横向方向；
 * 解完统一摆成 Java 像素数组的「从左上按行往下」。
 *
 * <p>解不了（Huffman 压缩族、声明索引色却没调色板、数据被截断）一律返回 null，
 * 由调用方计入降级清单 —— 宁可不转也不出一张错图。
 */
public final class Tga {

    private Tga() {
    }

    /**
     * @param sizeOut 长度 ≥2，成功时写入 {@code sizeOut[0]=宽 sizeOut[1]=高}
     * @return ARGB 像素数组（长度 = 宽×高）；格式不支持或数据不完整返回 null
     */
    public static int[] decode(byte[] d, int[] sizeOut) {
        if (d == null || d.length < 18) {
            return null;
        }
        int idLen = d[0] & 0xFF;
        boolean hasCmap = (d[1] & 0xFF) == 1;
        int imgType = d[2] & 0xFF;
        int cmapStart = (d[3] & 0xFF) | ((d[4] & 0xFF) << 8);
        int cmapLen = (d[5] & 0xFF) | ((d[6] & 0xFF) << 8);
        int cmapDepth = d[7] & 0xFF;
        int w = (d[12] & 0xFF) | ((d[13] & 0xFF) << 8);
        int h = (d[14] & 0xFF) | ((d[15] & 0xFF) << 8);
        int bpp = d[16] & 0xFF;
        int desc = d[17] & 0xFF;
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) {
            return null;
        }
        boolean rle = imgType >= 9 && imgType <= 11;
        int kind = rle ? imgType - 8 : imgType;        // 1 索引 / 2 真彩 / 3 灰度
        if (kind != 1 && kind != 2 && kind != 3) {
            return null;
        }
        boolean topFirst = (desc & 0x20) != 0;
        boolean rightFirst = (desc & 0x10) != 0;

        int pos = 18 + idLen;
        int[] cmap = null;
        int cmapBytes = 0;
        if (kind == 1) {
            if (!hasCmap || cmapLen <= 0) {
                return null;
            }
            int cd = cmapDepth == 0 ? 24 : cmapDepth;
            int cb = (cd + 7) / 8;
            cmapBytes = cb * cmapLen;
            if (pos + cmapBytes > d.length) {
                return null;
            }
            cmap = new int[cmapLen];
            for (int i = 0; i < cmapLen; i++) {
                cmap[i] = rawPixel(d, pos + i * cb, cd, true);
            }
        }
        int pStart = pos + cmapBytes;
        int depth = kind == 1 ? (cmapDepth == 0 ? 24 : cmapDepth) : (bpp == 0 ? 24 : bpp);
        int bytesPP = kind == 1 ? Math.max(1, depth / 8) : (depth + 7) / 8;
        if (bytesPP <= 0) {
            return null;
        }
        int n = w * h;
        int[] px = new int[n];
        int cursor = pStart;
        if (rle) {
            int i = 0;
            while (i < n) {
                if (cursor >= d.length) {
                    return null;
                }
                int pkt = d[cursor++] & 0xFF;
                int count = (pkt & 0x7F) + 1;
                boolean packed = (pkt & 0x80) != 0;
                int color = 0;
                if (packed) {
                    if (cursor + bytesPP > d.length) {
                        return null;
                    }
                    color = at(d, cursor, kind, depth, cmap);
                    cursor += bytesPP;
                    for (int k = 0; k < count && i < n; k++) {
                        px[i++] = color;
                    }
                } else {
                    for (int k = 0; k < count && i < n; k++) {
                        if (cursor + bytesPP > d.length) {
                            return null;
                        }
                        px[i++] = at(d, cursor, kind, depth, cmap);
                        cursor += bytesPP;
                    }
                }
            }
        } else {
            if (cursor + bytesPP * n > d.length) {
                return null;
            }
            for (int i = 0; i < n; i++) {
                px[i] = at(d, cursor + i * bytesPP, kind, depth, cmap);
            }
        }

        int[] out = new int[n];
        for (int y = 0; y < h; y++) {
            int sy = topFirst ? y : (h - 1 - y);
            for (int x = 0; x < w; x++) {
                int sx = rightFirst ? (w - 1 - x) : x;
                out[y * w + x] = px[sy * w + sx];
            }
        }
        if (sizeOut != null && sizeOut.length >= 2) {
            sizeOut[0] = w;
            sizeOut[1] = h;
        }
        return out;
    }

    /** 取一个样本：kind 1 走调色板索引，2 是真彩色，3 是灰度。 */
    private static int at(byte[] d, int off, int kind, int depth, int[] cmap) {
        if (kind == 1) {
            int idx = depth == 16
                    ? (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                    : (d[off] & 0xFF);
            idx -= 0;
            return idx >= 0 && idx < cmap.length ? cmap[idx] : 0xFF000000;
        }
        if (kind == 3) {
            int g = d[off] & 0xFF;
            return 0xFF000000 | (g << 16) | (g << 8) | g;
        }
        return rawPixel(d, off, depth, false);
    }

    /** 15/16 位 BGR555x；24 位 BGR；32 位 BGRA。{@code fromCmap} 时 32 位的 alpha 全取。 */
    private static int rawPixel(byte[] d, int off, int depth, boolean fromCmap) {
        if (depth == 32) {
            int b = d[off] & 0xFF;
            int g = d[off + 1] & 0xFF;
            int r = d[off + 2] & 0xFF;
            int a = d[off + 3] & 0xFF;
            // 有的包写了 alpha 通道却整条填 0，视觉上其实是不透明的：RGB 有值就按不透明处理
            if (a == 0 && (r | g | b) != 0) {
                a = 255;
            }
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
        if (depth == 15 || depth == 16) {
            int v = (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
            int r = (v >> 10) & 0x1F;
            int g = (v >> 5) & 0x1F;
            int b = v & 0x1F;
            int a = depth == 16 ? 255 : (((v >> 15) & 1) == 1 ? 255 : 0);
            return (a << 24) | ((r * 255 / 31) << 16) | ((g * 255 / 31) << 8) | (b * 255 / 31);
        }
        int b = d[off] & 0xFF;
        int g = d[off + 1] & 0xFF;
        int r = d[off + 2] & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
