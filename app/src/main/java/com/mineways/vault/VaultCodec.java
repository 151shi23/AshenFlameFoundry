package com.mineways.vault;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * 流式加解密内核：双层 AES-256-GCM + STREAM 分块 + 终止块。
 *
 * <p>普通文件填充到 4 KiB 边界后再加密；文件夹归档不填充（ZIP 自带结束标记）。
 * 解密在凭据校验通过之前不会创建任何输出文件。
 */
public final class VaultCodec {

    public interface Cancel {
        boolean cancelled();
    }

    public interface Progress {
        /** total &lt; 0 表示总大小未知（文件夹归档 / 解压）。 */
        void onProgress(long done, long total);
    }

    public interface Sink {
        void write(byte[] b, int off, int len) throws IOException;
    }

    public static final Cancel NEVER = () -> false;
    public static final Progress QUIET = (done, total) -> {
    };

    public static final class Result {
        public final long plainBytes;
        public final long containerBytes;
        public final long elapsedMs;

        Result(long plainBytes, long containerBytes, long elapsedMs) {
            this.plainBytes = plainBytes;
            this.containerBytes = containerBytes;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final byte[] NO_SALT = new byte[0];

    private VaultCodec() {
    }

    /** 一次会话 = 一组凭据 + 一组盐。批量加密复用同一个会话，Argon2id 只跑一次。 */
    public static final class Session implements Closeable {
        public final VaultCrypto.KdfParams kdf;
        private final byte[] saltKdf;
        private final byte[] saltHkdf;
        private final byte[] master;
        private final byte[] mWrap;
        private final byte[] mMeta;

        private Session(VaultCrypto.KdfParams kdf, byte[] saltKdf, byte[] saltHkdf, byte[] master) {
            this.kdf = kdf;
            this.saltKdf = saltKdf;
            this.saltHkdf = saltHkdf;
            this.master = master;
            this.mWrap = Arrays.copyOfRange(master, 0, 32);
            this.mMeta = Arrays.copyOfRange(master, 32, 64);
        }

        public static Session create(char[] pass, byte[] key, VaultCrypto.KdfParams kdf) {
            byte[] s1 = VaultCrypto.random(VaultCrypto.SALT_BYTES);
            byte[] s2 = VaultCrypto.random(VaultCrypto.SALT_BYTES);
            byte[] master = VaultCrypto.masterKey(pass, key, kdf, s1, s2);
            return new Session(kdf, s1, s2, master);
        }

        public static Session open(char[] pass, byte[] key, VaultCrypto.KdfParams kdf,
                                   byte[] saltKdf, byte[] saltHkdf) {
            byte[] master = VaultCrypto.masterKey(pass, key, kdf, saltKdf, saltHkdf);
            return new Session(kdf, saltKdf.clone(), saltHkdf.clone(), master);
        }

        public String cacheKey() {
            return kdf.id + ":" + kdf.memKiB + ":" + kdf.iters + ":" + kdf.lanes + ":"
                    + VaultCrypto.hex(saltKdf, saltKdf.length) + ":"
                    + VaultCrypto.hex(saltHkdf, saltHkdf.length);
        }

        public Result encrypt(InputStream in, OutputStream out, VaultFormat.Meta meta,
                              Progress progress, Cancel cancel) throws IOException, VaultException {
            long started = System.currentTimeMillis();
            if (cancel == null) {
                cancel = NEVER;
            }
            if (progress == null) {
                progress = QUIET;
            }
            boolean archive = meta.archive();
            int flags = archive ? 0 : VaultFormat.FLAG_PADDED;

            byte[] metaBytes = meta.encode();
            byte[] fk = VaultCrypto.random(VaultCrypto.KEY_BYTES);
            FileKeys keys = null;
            try {
                keys = FileKeys.derive(fk);
                byte[] wrapNonce = VaultCrypto.random(VaultCrypto.GCM_NONCE_BYTES);
                byte[] metaNonce = VaultCrypto.random(VaultCrypto.GCM_NONCE_BYTES);
                int metaLen = metaBytes.length + VaultCrypto.GCM_TAG_BYTES;

                byte[] fixed0 = VaultFormat.buildFixed(flags, kdf, saltKdf, saltHkdf, wrapNonce,
                        new byte[VaultCrypto.KEY_BYTES + VaultCrypto.GCM_TAG_BYTES], metaNonce, metaLen);
                byte[] wrappedFk = VaultCrypto.aesGcmSeal(mWrap, wrapNonce, fk, 0, fk.length,
                        VaultFormat.wrapAad(fixed0));
                VaultCrypto.wipe(fixed0);
                byte[] fixed1 = VaultFormat.buildFixed(flags, kdf, saltKdf, saltHkdf, wrapNonce,
                        wrappedFk, metaNonce, metaLen);
                byte[] metaCipher = VaultCrypto.aesGcmSeal(mMeta, metaNonce, metaBytes, 0,
                        metaBytes.length, VaultFormat.metaAad(fixed1));
                VaultCrypto.wipe(fixed1);

                VaultFormat.Header header = new VaultFormat.Header(flags, kdf, saltKdf, saltHkdf,
                        wrapNonce, wrappedFk, metaNonce, metaCipher);
                byte[] headerDigest = header.digest();
                byte[] full = header.full();
                try {
                    out.write(full);
                } finally {
                    VaultCrypto.wipe(full);
                }
                long containerBytes = VaultFormat.HEADER_FIXED + metaCipher.length;

                byte[] buf = new byte[VaultFormat.CHUNK];
                long written = 0;
                int index = 0;
                try {
                    if (!archive) {
                        long remaining = meta.size;
                        while (remaining > 0) {
                            checkCancel(cancel);
                            int want = (int) Math.min(VaultFormat.CHUNK, remaining);
                            int n = VaultFormat.readFully(in, buf, 0, want);
                            if (n < want) {
                                throw new VaultException("读取源文件", "源文件长度不足（加密过程中被修改？已读取 "
                                        + written + " / " + meta.size + " 字节）");
                            }
                            byte[] c = keys.seal(headerDigest, index, false, buf, n);
                            VaultFormat.writeFrame(out, false, c);
                            containerBytes += VaultFormat.FRAME_BYTES + c.length;
                            VaultCrypto.wipe(c);
                            written += n;
                            remaining -= n;
                            index++;
                            progress.onProgress(written, meta.size);
                        }
                        if (in.read() != -1) {
                            throw new VaultException("读取源文件", "源文件变长（加密过程中被修改？）");
                        }
                        int padLen = VaultFormat.PAD_BLOCK - (int) (meta.size % VaultFormat.PAD_BLOCK);
                        byte[] pad = VaultCrypto.random(padLen);
                        byte[] c = keys.seal(headerDigest, index, false, pad, padLen);
                        VaultFormat.writeFrame(out, false, c);
                        containerBytes += VaultFormat.FRAME_BYTES + c.length;
                        VaultCrypto.wipe(c);
                        VaultCrypto.wipe(pad);
                        index++;
                    } else {
                        while (true) {
                            checkCancel(cancel);
                            int n = in.read(buf);
                            if (n < 0) {
                                break;
                            }
                            if (n == 0) {
                                continue;
                            }
                            byte[] c = keys.seal(headerDigest, index, false, buf, n);
                            VaultFormat.writeFrame(out, false, c);
                            containerBytes += VaultFormat.FRAME_BYTES + c.length;
                            VaultCrypto.wipe(c);
                            written += n;
                            index++;
                            progress.onProgress(written, -1);
                        }
                    }
                    byte[] last = keys.seal(headerDigest, index, true, buf, 0);
                    VaultFormat.writeFrame(out, true, last);
                    containerBytes += VaultFormat.FRAME_BYTES + last.length;
                    VaultCrypto.wipe(last);
                    out.flush();
                } finally {
                    VaultCrypto.wipe(headerDigest);
                    VaultCrypto.wipe(buf);
                }
                return new Result(archive ? written : meta.size, containerBytes,
                        System.currentTimeMillis() - started);
            } finally {
                VaultCrypto.wipe(fk);
                VaultCrypto.wipe(metaBytes);
                if (keys != null) {
                    keys.close();
                }
            }
        }

        public Open openContainer(InputStream in, Cancel cancel) throws IOException, VaultException {
            if (cancel == null) {
                cancel = NEVER;
            }
            checkCancel(cancel);
            VaultFormat.Header header = VaultFormat.read(in);

            byte[] fk = null;
            byte[] metaBytes = null;
            FileKeys keys = null;
            try {
                try {
                    fk = VaultCrypto.aesGcmOpen(mWrap, header.wrapNonce, header.wrappedFk,
                            header.wrapAad());
                } catch (VaultException e) {
                    throw new VaultException("验证凭据",
                            "口令或密钥不正确（也可能是文件头被改动）", e);
                }
                if (fk.length != VaultCrypto.KEY_BYTES) {
                    throw new VaultException("验证凭据", "文件密钥长度非法");
                }
                try {
                    metaBytes = VaultCrypto.aesGcmOpen(mMeta, header.metaNonce, header.metaCipher,
                            header.metaAad());
                } catch (VaultException e) {
                    throw new VaultException("验证凭据", "元数据损坏或凭据不正确", e);
                }
                VaultFormat.Meta meta = VaultFormat.Meta.decode(metaBytes);
                if (meta.archive() && header.padded()) {
                    throw new VaultException("读取文件头", "容器参数与元数据不一致（归档不应填充）");
                }
                if (!meta.archive() && !header.padded()) {
                    throw new VaultException("读取文件头", "容器参数与元数据不一致（文件缺少填充标记）");
                }
                keys = FileKeys.derive(fk);
                Open open = new Open(in, header, meta, keys);
                keys = null;
                return open;
            } finally {
                VaultCrypto.wipe(fk);
                VaultCrypto.wipe(metaBytes);
                if (keys != null) {
                    keys.close();
                }
            }
        }

        @Override
        public void close() {
            VaultCrypto.wipe(master);
            VaultCrypto.wipe(mWrap);
            VaultCrypto.wipe(mMeta);
            VaultCrypto.wipe(saltKdf);
            VaultCrypto.wipe(saltHkdf);
        }
    }

    /** 打开后的容器：凭据已通过，元数据已解出，可以安全地创建输出。 */
    public static final class Open implements Closeable {
        private final InputStream in;
        private final VaultFormat.Meta meta;
        private final FileKeys keys;
        private final byte[] headerDigest;

        Open(InputStream in, VaultFormat.Header header, VaultFormat.Meta meta, FileKeys keys) {
            this.in = in;
            this.meta = meta;
            this.keys = keys;
            this.headerDigest = header.digest();
        }

        public VaultFormat.Meta meta() {
            return meta;
        }

        public Result decryptTo(Sink sink, Progress progress, Cancel cancel)
                throws IOException, VaultException {
            if (cancel == null) {
                cancel = NEVER;
            }
            if (progress == null) {
                progress = QUIET;
            }
            long started = System.currentTimeMillis();
            boolean archive = meta.archive();
            long written = 0;
            long plainTotal = 0;
            int index = 0;
            while (true) {
                checkCancel(cancel);
                int flags = VaultFormat.readFrameFlags(in);
                if (flags < 0) {
                    throw new VaultException("解密数据", "文件不完整：缺少终止块（可能被截断）");
                }
                if ((flags & ~1) != 0) {
                    throw new VaultException("解密数据", "数据块标记非法（flags=0x"
                            + Integer.toHexString(flags) + "）");
                }
                boolean last = (flags & 1) != 0;
                byte[] lenBytes = new byte[4];
                if (VaultFormat.readFully(in, lenBytes, 0, 4) < 4) {
                    throw new VaultException("解密数据", "数据块长度不完整（文件被截断）");
                }
                int ctLen = VaultFormat.readInt(lenBytes, 0);
                if (ctLen < 2 * VaultCrypto.GCM_TAG_BYTES || ctLen > VaultFormat.MAX_CT_CHUNK) {
                    throw new VaultException("解密数据", "数据块长度非法：" + ctLen);
                }
                int plainLen = ctLen - 2 * VaultCrypto.GCM_TAG_BYTES;
                if (!last && plainLen == 0) {
                    throw new VaultException("解密数据", "空数据块非法");
                }
                byte[] cipher = new byte[ctLen];
                if (VaultFormat.readFully(in, cipher, 0, ctLen) < ctLen) {
                    VaultCrypto.wipe(cipher);
                    throw new VaultException("解密数据", "第 " + (index + 1) + " 块不完整（文件被截断）");
                }
                byte[] plain;
                try {
                    byte[] aad = VaultFormat.chunkAad(headerDigest, index, last, plainLen);
                    byte[] inner = VaultCrypto.aesGcmOpen(keys.kOut,
                            keys.nonce(keys.nOut, index), cipher, aad);
                    VaultCrypto.wipe(cipher);
                    cipher = null;
                    plain = VaultCrypto.aesGcmOpen(keys.kIn,
                            keys.nonce(keys.nIn, index), inner, aad);
                    VaultCrypto.wipe(inner);
                    VaultCrypto.wipe(aad);
                } catch (VaultException e) {
                    throw new VaultException("解密数据",
                            "第 " + (index + 1) + " 块校验失败：文件被篡改或被截断", e);
                } finally {
                    VaultCrypto.wipe(cipher);
                }
                if (last) {
                    if (plain.length != 0) {
                        VaultCrypto.wipe(plain);
                        throw new VaultException("解密数据", "终止块非法（应不携带数据）");
                    }
                    VaultCrypto.wipe(plain);
                    if (in.read() != -1) {
                        throw new VaultException("解密数据", "容器末尾有多余数据（可能被拼接/追加过）");
                    }
                    break;
                }
                plainTotal += plain.length;
                if (!archive) {
                    long room = meta.size - written;
                    int n = (int) Math.min(plain.length, Math.max(0, room));
                    if (n > 0 && sink != null) {
                        sink.write(plain, 0, n);
                    }
                    written += n;
                } else {
                    if (sink != null) {
                        sink.write(plain, 0, plain.length);
                    }
                    written += plain.length;
                }
                VaultCrypto.wipe(plain);
                index++;
                progress.onProgress(plainTotal, archive ? -1 : meta.size);
            }

            if (!archive) {
                if (written != meta.size) {
                    throw new VaultException("解密数据",
                            "明文长度不足：期望 " + meta.size + " 字节，实际 " + written);
                }
                long padLen = plainTotal - meta.size;
                if (padLen < 1 || padLen > VaultFormat.PAD_BLOCK
                        || plainTotal % VaultFormat.PAD_BLOCK != 0) {
                    throw new VaultException("解密数据", "填充长度非法（文件可能被篡改）");
                }
            }
            progress.onProgress(written, archive ? -1 : meta.size);
            return new Result(written, 0, System.currentTimeMillis() - started);
        }

        @Override
        public void close() {
            keys.close();
            VaultCrypto.wipe(headerDigest);
        }
    }

    private static final class FileKeys {
        final byte[] kIn;
        final byte[] kOut;
        final byte[] nIn;
        final byte[] nOut;

        private FileKeys(byte[] kIn, byte[] kOut, byte[] nIn, byte[] nOut) {
            this.kIn = kIn;
            this.kOut = kOut;
            this.nIn = nIn;
            this.nOut = nOut;
        }

        static FileKeys derive(byte[] fk) {
            byte[] kIn = VaultCrypto.hkdfSha512(fk, NO_SALT, "MWVault/1/inner", 32);
            try {
                byte[] kOut = VaultCrypto.hkdfSha512(fk, NO_SALT, "MWVault/1/outer", 32);
                byte[] nIn = VaultCrypto.hkdfSha512(fk, NO_SALT, "MWVault/1/inner-nonce", 4);
                byte[] nOut = VaultCrypto.hkdfSha512(fk, NO_SALT, "MWVault/1/outer-nonce", 4);
                return new FileKeys(kIn, kOut, nIn, nOut);
            } catch (RuntimeException e) {
                VaultCrypto.wipe(kIn);
                throw e;
            }
        }

        byte[] nonce(byte[] prefix, int index) {
            byte[] n = new byte[VaultCrypto.GCM_NONCE_BYTES];
            System.arraycopy(prefix, 0, n, 0, 4);
            long v = index & 0xFFFFFFFFL;
            for (int i = 0; i < 8; i++) {
                n[4 + i] = (byte) (v >>> (56 - 8 * i));
            }
            return n;
        }

        byte[] seal(byte[] headerDigest, int index, boolean last, byte[] plain, int len) {
            byte[] aad = VaultFormat.chunkAad(headerDigest, index, last, len);
            byte[] n1 = nonce(nIn, index);
            byte[] n2 = nonce(nOut, index);
            byte[] c1 = null;
            try {
                c1 = VaultCrypto.aesGcmSeal(kIn, n1, plain, 0, len, aad);
                return VaultCrypto.aesGcmSeal(kOut, n2, c1, 0, c1.length, aad);
            } finally {
                VaultCrypto.wipe(c1);
                VaultCrypto.wipe(n1);
                VaultCrypto.wipe(n2);
                VaultCrypto.wipe(aad);
            }
        }

        void close() {
            VaultCrypto.wipe(kIn);
            VaultCrypto.wipe(kOut);
            VaultCrypto.wipe(nIn);
            VaultCrypto.wipe(nOut);
        }
    }

    private static void checkCancel(Cancel cancel) throws VaultException {
        if (cancel.cancelled()) {
            throw new VaultException("已取消", "操作已取消");
        }
    }
}
