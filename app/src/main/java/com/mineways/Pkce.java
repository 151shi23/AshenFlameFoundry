package com.mineways;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * OAuth 2.0 PKCE（RFC 7636）的码值与状态串。
 *
 * <p>Google 与微软的移动/桌面公共客户端都要求授权码流程带 PKCE：授权时送
 * {@code code_challenge}，换票时送 {@code code_verifier}，服务端比 SHA-256。
 * 截获了回跳 URL 也换不到 token —— 这是这两家登录在客户端侧唯一的兜底，所以自己写、
 * 不用任何第三方 SDK（包里不引 Google Play Services，也不引 MSAL）。
 *
 * <p>只用 {@link java.util.Base64}（minSdk 26 起有），所以这个类能用 javac 直接断言。
 */
public final class Pkce {

    /** RFC 7636 §4.1 允许的字符集（unreserved）。 */
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toCharArray();

    /** verifier 长度取 64（上限 128，太长手写容易截断，太短熵不够）。 */
    private static final int VERIFIER_LEN = 64;
    private static final int STATE_LEN = 24;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private Pkce() {
    }

    public static String verifier() {
        return random(VERIFIER_LEN);
    }

    /** 防授权页被 CSRF / 回跳被伪造的那一次一串。 */
    public static String state() {
        return random(STATE_LEN);
    }

    private static String random(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** S256：{@code base64url(sha256(verifier))}，去掉填充等号。算不出来返回空串（调用方据此中止）。 */
    public static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * URL 编码。{@link java.net.URLEncoder} 是 form 编码（空格写成 {@code +}），
     * 而 OAuth 的授权页参数按 RFC 3986 解析 —— 空格必须是 {@code %20}，否则 scope 会被读成
     * 一个字，两家都会回 {@code invalid_scope}。
     */
    public static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }
}
