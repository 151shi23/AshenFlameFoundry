package com.mineways.cloud;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 云端配置的编解码内核：gzip 收发与清单解析。
 *
 * <p>刻意<b>不依赖 Android</b>，这样「配置能不能压、压完还对不对」这件事可以在
 * {@code 云端配置/harness/HomeHarness} 里用纯 JVM 断言跑死，而不是等装到手机上才发现解压坏了。
 *
 * <p>正确性靠两道哈希：清单第 2 列是<b>明文整文件</b>的 sha256（历史语义，一字未改），
 * 第 3 列是<b>压缩件</b>的 sha256。解压之后仍要拿明文哈希核一次 —— 于是压缩不会改变任何一条指令，
 * {@code @hmac} 签名核的也是解压后的正文。
 */
public final class CloudCodec {

	/** gzip 魔数，用来判断磁盘上这份是 .txt 还是 .txt.gz。必须是 0..255 的 int，写成 byte 会变成 -115。 */
	private static final int MAGIC_0 = 0x1f;
	private static final int MAGIC_1 = 0x8b;

	/** 明文上限：防压缩炸弹。现在的配置两千多万字节也才 2.3 MB，留 8 倍余量。 */
	public static final int MAX_PLAIN_BYTES = 16 * 1024 * 1024;

	private CloudCodec() {
	}

	/** 压成 gzip（运行时只用得着解压，这个方向留给自检与发布脚本对齐字节）。 */
	public static byte[] gzip(byte[] plain) throws java.io.IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		GZIPOutputStream gz = new GZIPOutputStream(bos);
		gz.write(plain);
		gz.close();
		return bos.toByteArray();
	}

	/**
	 * 解 gzip。超过 {@code maxOut} 直接抛错 —— 宁可这份配置不要，也不能让一个畸形包撑爆内存。
	 */
	public static byte[] gunzip(byte[] packed, int maxOut) throws java.io.IOException {
		if (!looksGzipped(packed)) {
			throw new java.io.IOException("不是 gzip 流（缺 1f8b 魔数）");
		}
		java.io.InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(packed));
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(maxOut, 1 << 20));
			byte[] buf = new byte[16384];
			int n;
			long total = 0L;
			while ((n = in.read(buf)) > 0) {
				total += n;
				if (total > maxOut) {
					throw new java.io.IOException("解压后超过上限 " + maxOut + " 字节，已拒绝");
				}
				bos.write(buf, 0, n);
			}
			return bos.toByteArray();
		} finally {
			in.close();
		}
	}

	/** 只看头两字节：磁盘上可能是老版本的纯 .txt，也可能是新版本的 .txt.gz。 */
	public static boolean looksGzipped(byte[] data) {
		return data != null && data.length > 2 && (data[0] & 0xff) == MAGIC_0 && (data[1] & 0xff) == MAGIC_1;
	}

	/** 读磁盘字节并还原成正文文本（不是 gzip 就当 UTF-8 明文读）。 */
	public static byte[] plainBytes(byte[] stored) throws java.io.IOException {
		return looksGzipped(stored) ? gunzip(stored, MAX_PLAIN_BYTES) : stored;
	}

	public static String sha256Hex(byte[] data) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				sb.append(String.format(Locale.ROOT, "%02x", b));
			}
			return sb.toString();
		} catch (Throwable t) {
			return "";
		}
	}

	public static String sha256Hex(String text) {
		return sha256Hex(text.getBytes(Charset.forName("UTF-8")));
	}

	/** 清单第 2 列：版本 → 明文 sha256。 */
	public static Map<String, String> manifestPlain(String manifest) {
		return manifestColumn(manifest, 1);
	}

	/** 清单第 3 列：版本 → 压缩件 sha256（老清单没有这一列，返回空表就等于"只能走明文"）。 */
	public static Map<String, String> manifestGz(String manifest) {
		return manifestColumn(manifest, 2);
	}

	private static Map<String, String> manifestColumn(String text, int col) {
		Map<String, String> out = new HashMap<String, String>();
		if (text == null) {
			return out;
		}
		for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
			String s = line.trim();
			if (s.isEmpty() || s.charAt(0) == '#') {
				continue;
			}
			String[] parts = s.split("\\s+");
			if (parts.length <= col || !parts[0].matches("[0-9]+(\\.[0-9]+)*")) {
				continue;
			}
			if (parts[col].matches("[0-9a-fA-F]{64}")) {
				out.put(parts[0], parts[col].toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}
}
