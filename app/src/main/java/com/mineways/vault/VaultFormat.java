package com.mineways.vault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * MWVault-1 容器格式：头部、元数据、分块 AAD 的唯一实现。
 *
 * <pre>
 * 头部固定 132 字节：
 *   0   magic "MWV1"        4  version=1
 *   5   flags               6  reserved
 *   8   kdfId               12 memKiB
 *   16  iters               20 lanes + 3 reserved
 *   24  saltKdf(16)         40 saltHkdf(16)
 *   56  wrapNonce(12)       68 wrappedFK(48)
 *   116 metaNonce(12)       128 metaLen(4)
 *   132 metaCipher(metaLen)
 * 之后是数据块：[u8 flags][u32 ctLen][ctLen] …，终止块 final=1 且明文长 0。
 * </pre>
 */
public final class VaultFormat {

    public static final byte[] MAGIC = {'M', 'W', 'V', '1'};
    public static final int VERSION = 1;

    public static final int FLAG_PADDED = 0x01;
    private static final int KNOWN_HEADER_FLAGS = FLAG_PADDED;

    public static final int META_FILE = 0;
    public static final int META_ARCHIVE = 1;
    private static final int KNOWN_META_FLAGS = META_ARCHIVE;
    private static final int META_VERSION = 1;

    public static final int HEADER_FIXED = 132;
    public static final int WRAP_AAD_LEN = 56;
    public static final int CHUNK = 1 << 20;
    public static final int PAD_BLOCK = 4096;
    public static final int FRAME_BYTES = 5;
    public static final int MAX_CT_CHUNK = CHUNK + 2 * VaultCrypto.GCM_TAG_BYTES;
    public static final int MAX_META_CIPHER = 8192;
    public static final int MAX_NAME_BYTES = 1024;
    public static final int MIN_META_CIPHER = VaultCrypto.GCM_TAG_BYTES + 20;

    static final int OFF_VERSION = 4;
    static final int OFF_FLAGS = 5;
    static final int OFF_KDF = 8;
    static final int OFF_MEM = 12;
    static final int OFF_ITERS = 16;
    static final int OFF_LANES = 20;
    static final int OFF_SALT_KDF = 24;
    static final int OFF_SALT_HKDF = 40;
    static final int OFF_WRAP_NONCE = 56;
    static final int OFF_WRAPPED_FK = 68;
    static final int OFF_META_NONCE = 116;
    static final int OFF_META_LEN = 128;

    private VaultFormat() {
    }

    /** 解密后才知道的原始信息；文件名与大小都放在密文里。 */
    public static final class Meta {
        public final int flags;
        public final String name;
        /** 普通文件 = 明文真实大小；归档 = -1（打开流后才知道）。 */
        public final long size;
        public final long mtime;

        public Meta(int flags, String name, long size, long mtime) {
            this.flags = flags;
            this.name = name;
            this.size = size;
            this.mtime = mtime;
        }

        public boolean archive() {
            return (flags & META_ARCHIVE) != 0;
        }

        public byte[] encode() throws VaultException {
            if (name == null || name.isEmpty()) {
                throw new VaultException("元数据", "原始文件名为空");
            }
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length == 0 || nameBytes.length > MAX_NAME_BYTES) {
                throw new VaultException("元数据", "文件名过长（UTF-8 上限 1024 字节）");
            }
            if (archive()) {
                if (size != -1) {
                    throw new VaultException("元数据", "归档容器的大小必须是 -1");
                }
            } else if (size < 0) {
                throw new VaultException("元数据", "文件大小非法：" + size);
            }
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bos);
                out.writeByte(META_VERSION);
                out.writeByte(flags);
                out.writeShort(nameBytes.length);
                out.write(nameBytes);
                out.writeLong(size);
                out.writeLong(mtime);
                out.flush();
                if (bos.size() + VaultCrypto.GCM_TAG_BYTES > MAX_META_CIPHER) {
                    throw new VaultException("元数据", "元数据过长");
                }
                return bos.toByteArray();
            } catch (IOException e) {
                throw new VaultException("元数据", "编码失败：" + e.getMessage(), e);
            }
        }

        public static Meta decode(byte[] data) throws VaultException {
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
                int ver = in.readUnsignedByte();
                if (ver != META_VERSION) {
                    throw new VaultException("元数据", "元数据版本不支持：" + ver);
                }
                int flags = in.readUnsignedByte();
                if ((flags & ~KNOWN_META_FLAGS) != 0) {
                    throw new VaultException("元数据", "元数据参数不认识（flags=0x" + Integer.toHexString(flags) + "）");
                }
                int nameLen = in.readUnsignedShort();
                if (nameLen < 1 || nameLen > MAX_NAME_BYTES) {
                    throw new VaultException("元数据", "文件名长度非法：" + nameLen);
                }
                byte[] nameBytes = new byte[nameLen];
                in.readFully(nameBytes);
                long size = in.readLong();
                long mtime = in.readLong();
                if (in.available() != 0) {
                    throw new VaultException("元数据", "元数据尾部有多余数据");
                }
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                if (name.isEmpty()) {
                    throw new VaultException("元数据", "原始文件名为空");
                }
                if ((flags & META_ARCHIVE) != 0) {
                    if (size != -1) {
                        throw new VaultException("元数据", "归档容器的大小标记非法");
                    }
                } else if (size < 0) {
                    throw new VaultException("元数据", "文件大小非法：" + size);
                }
                return new Meta(flags, name, size, mtime);
            } catch (VaultException e) {
                throw e;
            } catch (IOException e) {
                throw new VaultException("元数据", "元数据损坏：" + e.getMessage(), e);
            }
        }
    }

    public static final class Header {
        public final int flags;
        public final VaultCrypto.KdfParams kdf;
        public final byte[] saltKdf;
        public final byte[] saltHkdf;
        public final byte[] wrapNonce;
        public final byte[] wrappedFk;
        public final byte[] metaNonce;
        public final byte[] metaCipher;
        public final byte[] fixed;

        Header(int flags, VaultCrypto.KdfParams kdf, byte[] saltKdf, byte[] saltHkdf,
               byte[] wrapNonce, byte[] wrappedFk, byte[] metaNonce, byte[] metaCipher) {
            this.flags = flags;
            this.kdf = kdf;
            this.saltKdf = saltKdf;
            this.saltHkdf = saltHkdf;
            this.wrapNonce = wrapNonce;
            this.wrappedFk = wrappedFk;
            this.metaNonce = metaNonce;
            this.metaCipher = metaCipher;
            this.fixed = buildFixed(flags, kdf, saltKdf, saltHkdf, wrapNonce, wrappedFk,
                    metaNonce, metaCipher.length);
        }

        public boolean padded() {
            return (flags & FLAG_PADDED) != 0;
        }

        public byte[] digest() {
            return headerDigest(fixed, metaCipher);
        }

        public byte[] full() {
            byte[] out = new byte[HEADER_FIXED + metaCipher.length];
            System.arraycopy(fixed, 0, out, 0, HEADER_FIXED);
            System.arraycopy(metaCipher, 0, out, HEADER_FIXED, metaCipher.length);
            return out;
        }

        public byte[] wrapAad() {
            return VaultFormat.wrapAad(fixed);
        }

        public byte[] metaAad() {
            return VaultFormat.metaAad(fixed);
        }
    }

    public static Header read(InputStream in) throws IOException, VaultException {
        byte[] fixed = new byte[HEADER_FIXED];
        int got = readFully(in, fixed, 0, HEADER_FIXED);
        if (got < HEADER_FIXED) {
            throw new VaultException("读取文件头",
                    got == 0 ? "文件为空或不是 MWVault 容器" : "文件太短，不是完整的 MWVault 容器");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (fixed[i] != MAGIC[i]) {
                throw new VaultException("读取文件头", "不是 MWVault 容器（文件头不匹配）");
            }
        }
        int version = fixed[OFF_VERSION] & 0xFF;
        if (version != VERSION) {
            throw new VaultException("读取文件头",
                    "这个文件由更新版本的 App 创建（容器版本 " + version + "），请升级后再试");
        }
        int flags = fixed[OFF_FLAGS] & 0xFF;
        if ((flags & ~KNOWN_HEADER_FLAGS) != 0) {
            throw new VaultException("读取文件头", "文件头参数不认识（flags=0x" + Integer.toHexString(flags) + "）");
        }
        int kdfId = readInt(fixed, OFF_KDF);
        int memKiB = readInt(fixed, OFF_MEM);
        int iters = readInt(fixed, OFF_ITERS);
        int lanes = fixed[OFF_LANES] & 0xFF;
        VaultCrypto.KdfParams kdf = new VaultCrypto.KdfParams(kdfId, memKiB, iters, lanes);
        if (!kdf.sane()) {
            throw new VaultException("读取文件头",
                    "容器 KDF 参数超出支持范围（id=" + kdfId + "，内存 " + memKiB
                            + " KiB，迭代 " + iters + "，通道 " + lanes + "）");
        }
        int metaLen = readInt(fixed, OFF_META_LEN);
        if (metaLen < MIN_META_CIPHER || metaLen > MAX_META_CIPHER) {
            throw new VaultException("读取文件头", "元数据长度超出支持范围：" + metaLen);
        }
        byte[] metaCipher = new byte[metaLen];
        if (readFully(in, metaCipher, 0, metaLen) < metaLen) {
            throw new VaultException("读取文件头", "元数据不完整（文件被截断）");
        }
        return new Header(flags, kdf,
                slice(fixed, OFF_SALT_KDF, VaultCrypto.SALT_BYTES),
                slice(fixed, OFF_SALT_HKDF, VaultCrypto.SALT_BYTES),
                slice(fixed, OFF_WRAP_NONCE, VaultCrypto.GCM_NONCE_BYTES),
                slice(fixed, OFF_WRAPPED_FK, VaultCrypto.KEY_BYTES + VaultCrypto.GCM_TAG_BYTES),
                slice(fixed, OFF_META_NONCE, VaultCrypto.GCM_NONCE_BYTES),
                metaCipher);
    }

    public static byte[] buildFixed(int flags, VaultCrypto.KdfParams kdf,
                                    byte[] saltKdf, byte[] saltHkdf, byte[] wrapNonce,
                                    byte[] wrappedFk, byte[] metaNonce, int metaLen) {
        byte[] f = new byte[HEADER_FIXED];
        System.arraycopy(MAGIC, 0, f, 0, MAGIC.length);
        f[OFF_VERSION] = (byte) VERSION;
        f[OFF_FLAGS] = (byte) flags;
        putInt(f, OFF_KDF, kdf.id);
        putInt(f, OFF_MEM, kdf.memKiB);
        putInt(f, OFF_ITERS, kdf.iters);
        f[OFF_LANES] = (byte) kdf.lanes;
        System.arraycopy(saltKdf, 0, f, OFF_SALT_KDF, VaultCrypto.SALT_BYTES);
        System.arraycopy(saltHkdf, 0, f, OFF_SALT_HKDF, VaultCrypto.SALT_BYTES);
        System.arraycopy(wrapNonce, 0, f, OFF_WRAP_NONCE, VaultCrypto.GCM_NONCE_BYTES);
        System.arraycopy(wrappedFk, 0, f, OFF_WRAPPED_FK, wrappedFk.length);
        System.arraycopy(metaNonce, 0, f, OFF_META_NONCE, VaultCrypto.GCM_NONCE_BYTES);
        putInt(f, OFF_META_LEN, metaLen);
        return f;
    }

    public static byte[] wrapAad(byte[] fixed) {
        return Arrays.copyOf(fixed, WRAP_AAD_LEN);
    }

    public static byte[] metaAad(byte[] fixed) {
        return VaultCrypto.sha256(fixed);
    }

    public static byte[] headerDigest(byte[] fixed, byte[] metaCipher) {
        byte[] all = new byte[fixed.length + metaCipher.length];
        System.arraycopy(fixed, 0, all, 0, fixed.length);
        System.arraycopy(metaCipher, 0, all, fixed.length, metaCipher.length);
        byte[] d = VaultCrypto.sha256(all);
        VaultCrypto.wipe(all);
        return d;
    }

    public static byte[] chunkAad(byte[] headerDigest, int index, boolean last, int plainLen) {
        byte[] aad = new byte[32 + 4 + 1 + 4];
        System.arraycopy(headerDigest, 0, aad, 0, 32);
        putInt(aad, 32, index);
        aad[36] = (byte) (last ? 1 : 0);
        putInt(aad, 37, plainLen);
        return aad;
    }

    public static void writeFrame(OutputStream out, boolean last, byte[] cipher) throws IOException {
        byte[] frame = new byte[FRAME_BYTES + cipher.length];
        frame[0] = (byte) (last ? 1 : 0);
        putInt(frame, 1, cipher.length);
        System.arraycopy(cipher, 0, frame, FRAME_BYTES, cipher.length);
        out.write(frame);
    }

    public static int readFrameFlags(InputStream in) throws IOException {
        int b = in.read();
        return b < 0 ? -1 : (b & 0xFF);
    }

    public static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    public static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    public static void require(boolean ok, String stage, String message) throws VaultException {
        if (!ok) {
            throw new VaultException(stage, message);
        }
    }

    public static void requireNotNull(Object o, String stage, String message) throws VaultException {
        if (o == null) {
            throw new VaultException(stage, message);
        }
    }

    static EOFException eof(String what) {
        return new EOFException(what);
    }
}
