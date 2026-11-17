package com.mineways.vault;

import com.mineways.vault.argon2.crypto.generators.Argon2BytesGenerator;
import com.mineways.vault.argon2.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 二进制加密的密码学装配层。
 *
 * <p>底层只用公开标准件：Argon2id（内置参考实现）、HKDF-SHA512、AES-256-GCM。
 * 本类不发明任何分组密码或轮函数 —— 独创性在容器与密钥编排，见方案文档。
 */
public final class VaultCrypto {

    public static final int KDF_ARGON2ID = 2;

    /** Argon2id 出厂参数：32 MiB × 3 遍 × 单通道。 */
    public static final int DEFAULT_MEM_KIB = 32 * 1024;
    public static final int DEFAULT_ITERS = 3;
    public static final int DEFAULT_LANES = 1;

    public static final int MAX_MEM_KIB = 256 * 1024;
    public static final int MIN_MEM_KIB = 1024;
    public static final int MAX_ITERS = 64;
    public static final int MAX_LANES = 8;

    public static final int KEY_BYTES = 32;
    public static final int SALT_BYTES = 16;
    public static final int GCM_TAG_BYTES = 16;
    public static final int GCM_NONCE_BYTES = 12;

    private static final char[] BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final String KEY_PREFIX = "MWK1";
    private static final int KEY_BODY_CHARS = 52;
    private static final int KEY_CHECK_CHARS = 5;

    private static final SecureRandom RNG = new SecureRandom();

    private VaultCrypto() {
    }

    /** KDF 参数随文件头保存；解密时先校验上限再跑，避免恶意文件把手机拖死。 */
    public static final class KdfParams {
        public final int id;
        public final int memKiB;
        public final int iters;
        public final int lanes;

        public KdfParams(int id, int memKiB, int iters, int lanes) {
            this.id = id;
            this.memKiB = memKiB;
            this.iters = iters;
            this.lanes = lanes;
        }

        public static KdfParams defaults() {
            return new KdfParams(KDF_ARGON2ID, DEFAULT_MEM_KIB, DEFAULT_ITERS, DEFAULT_LANES);
        }

        public boolean sane() {
            return id == KDF_ARGON2ID
                    && memKiB >= MIN_MEM_KIB && memKiB <= MAX_MEM_KIB
                    && iters >= 1 && iters <= MAX_ITERS
                    && lanes >= 1 && lanes <= MAX_LANES;
        }

        @Override
        public String toString() {
            return "Argon2id " + (memKiB / 1024) + " MiB × " + iters;
        }
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static void wipe(byte[] a) {
        if (a != null) {
            Arrays.fill(a, (byte) 0);
        }
    }

    public static void wipe(char[] a) {
        if (a != null) {
            Arrays.fill(a, '\0');
        }
    }

    /**
     * 口令先做 NFC + UTF-8 + SHA-256，把平台差异挡在 KDF 之外：
     * 以后换实现、换设备，同一个口令一定得到同一组字节。
     */
    public static byte[] normalizePassphrase(char[] pass) {
        String norm = Normalizer.normalize(new String(pass), Normalizer.Form.NFC);
        byte[] utf8 = norm.getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.getInstance("SHA-256").digest(utf8);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } finally {
            wipe(utf8);
        }
    }

    public static byte[] argon2id(byte[] passwordHash, byte[] salt, KdfParams p) {
        if (!p.sane()) {
            throw new IllegalArgumentException("KDF 参数超出支持范围");
        }
        return argon2idRaw(passwordHash, salt, null, null, p.memKiB, p.iters, p.lanes, 32);
    }

    /** 供 RFC 9106 向量测试使用；生产路径不传 secret / additional。 */
    public static byte[] argon2idRaw(byte[] password, byte[] salt, byte[] secret, byte[] additional,
                                     int memKiB, int iters, int lanes, int outLen) {
        Argon2Parameters.Builder b = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iters)
                .withMemoryAsKB(memKiB)
                .withParallelism(lanes)
                .withSalt(salt);
        if (secret != null) {
            b.withSecret(secret);
        }
        if (additional != null) {
            b.withAdditional(additional);
        }
        Argon2BytesGenerator g = new Argon2BytesGenerator();
        g.init(b.build());
        byte[] out = new byte[outLen];
        g.generateBytes(password, out);
        return out;
    }

    /** RFC 5869 HKDF，哈希用 SHA-512。 */
    public static byte[] hkdfSha512(byte[] ikm, byte[] salt, String info, int len) {
        return hkdf(ikm, salt, info, len, "HmacSHA512", 64);
    }

    /** 同一个实现也暴露 SHA-256 版本，专门用来跑 RFC 5869 官方测试向量。 */
    static byte[] hkdf(byte[] ikm, byte[] salt, String info, int len, String macAlg, int hashLen) {
        if (len <= 0 || len > 255 * hashLen) {
            throw new IllegalArgumentException("HKDF 输出长度非法");
        }
        try {
            Mac mac = Mac.getInstance(macAlg);
            byte[] realSalt = (salt == null || salt.length == 0) ? new byte[hashLen] : salt;
            mac.init(new SecretKeySpec(realSalt, macAlg));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, macAlg));
            byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[len];
            byte[] t = new byte[0];
            int pos = 0;
            int counter = 1;
            while (pos < len) {
                mac.update(t);
                mac.update(infoBytes);
                mac.update((byte) counter++);
                t = mac.doFinal();
                int n = Math.min(t.length, len - pos);
                System.arraycopy(t, 0, out, pos, n);
                pos += n;
            }
            wipe(prk);
            wipe(t);
            wipe(infoBytes);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("HKDF 不可用", e);
        }
    }

    /** 64 字节主密钥：前 32 包装文件密钥，后 32 加密元数据。 */
    public static byte[] masterKey(char[] pass, byte[] userKey, KdfParams p,
                                   byte[] saltKdf, byte[] saltHkdf) {
        if (userKey == null || userKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("密钥必须是 32 字节");
        }
        byte[] pwd = normalizePassphrase(pass);
        byte[] kek = null;
        byte[] ikm = null;
        try {
            kek = argon2id(pwd, saltKdf, p);
            ikm = new byte[kek.length + userKey.length];
            System.arraycopy(kek, 0, ikm, 0, kek.length);
            System.arraycopy(userKey, 0, ikm, kek.length, userKey.length);
            return hkdfSha512(ikm, saltHkdf, "MWVault/1/master", 64);
        } finally {
            wipe(pwd);
            wipe(kek);
            wipe(ikm);
        }
    }

    public static byte[] aesGcmSeal(byte[] key, byte[] nonce, byte[] plain, int off, int len, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BYTES * 8, nonce));
            if (aad != null && aad.length > 0) {
                c.updateAAD(aad);
            }
            return c.doFinal(plain, off, len);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    public static byte[] aesGcmOpen(byte[] key, byte[] nonce, byte[] cipher, byte[] aad)
            throws VaultException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BYTES * 8, nonce));
            if (aad != null && aad.length > 0) {
                c.updateAAD(aad);
            }
            return c.doFinal(cipher);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new VaultException("校验", "认证标签不匹配（内容被篡改或凭据不对）", e);
        } catch (Exception e) {
            throw new VaultException("解密", "AES-GCM 解密失败：" + e.getMessage(), e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String hex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < data.length; i++) {
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    /** 密钥指纹：只用来认钥匙，不能还原密钥。 */
    public static String fingerprint(byte[] key) {
        byte[] h = sha256(concat("MWVault-key-fp".getBytes(StandardCharsets.UTF_8), key));
        try {
            String s = hex(h, 4);
            return s.substring(0, 4) + "-" + s.substring(4);
        } finally {
            wipe(h);
        }
    }

    public static String encodeKeyText(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("密钥必须是 32 字节");
        }
        String body = base32Encode(key);
        byte[] checkHash = sha256(concat("MWVault-key-check".getBytes(StandardCharsets.UTF_8), key));
        String check = base32Encode(new byte[]{checkHash[0], checkHash[1], checkHash[2]})
                .substring(0, KEY_CHECK_CHARS);
        wipe(checkHash);
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        for (int i = 0; i < body.length(); i++) {
            if (i % 4 == 0) {
                sb.append('-');
            }
            sb.append(body.charAt(i));
        }
        sb.append('-').append(check);
        return sb.toString();
    }

    public static byte[] decodeKeyText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("密钥为空");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = Character.toUpperCase(text.charAt(i));
            if (ch == '-' || Character.isWhitespace(ch)) {
                continue;
            }
            sb.append(ch);
        }
        String s = sb.toString();
        if (!s.startsWith(KEY_PREFIX)) {
            throw new IllegalArgumentException("密钥文本必须以 " + KEY_PREFIX + " 开头");
        }
        String rest = s.substring(KEY_PREFIX.length());
        if (rest.length() != KEY_BODY_CHARS + KEY_CHECK_CHARS) {
            throw new IllegalArgumentException("密钥文本长度不对（应为 52 + 5 个字符）");
        }
        byte[] key = base32Decode(rest.substring(0, KEY_BODY_CHARS), KEY_BYTES);
        String check = rest.substring(KEY_BODY_CHARS);
        byte[] checkHash = sha256(concat("MWVault-key-check".getBytes(StandardCharsets.UTF_8), key));
        String expect = base32Encode(new byte[]{checkHash[0], checkHash[1], checkHash[2]})
                .substring(0, KEY_CHECK_CHARS);
        wipe(checkHash);
        if (!MessageDigest.isEqual(expect.getBytes(StandardCharsets.US_ASCII),
                check.getBytes(StandardCharsets.US_ASCII))) {
            wipe(key);
            throw new IllegalArgumentException("密钥校验码不匹配（可能有字符抄错了）");
        }
        return key;
    }

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0;
        int bits = 0;
        for (byte b : data) {
            buf = (buf << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32[(buf >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32[(buf << (5 - bits)) & 31]);
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String s, int outLen) {
        int buf = 0;
        int bits = 0;
        byte[] out = new byte[outLen];
        int pos = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == 'I' || ch == 'L') {
                ch = '1';
            } else if (ch == 'O') {
                ch = '0';
            }
            int v = -1;
            for (int k = 0; k < BASE32.length; k++) {
                if (BASE32[k] == ch) {
                    v = k;
                    break;
                }
            }
            if (v < 0) {
                throw new IllegalArgumentException("密钥文本含非法字符：" + ch);
            }
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                if (pos < outLen) {
                    out[pos++] = (byte) ((buf >>> (bits - 8)) & 0xFF);
                }
                bits -= 8;
            }
        }
        if (pos != outLen) {
            wipe(out);
            throw new IllegalArgumentException("密钥长度不足");
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
