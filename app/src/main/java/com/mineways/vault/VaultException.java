package com.mineways.vault;

/** 带阶段名的异常：界面直接把「哪一步、为什么」显示给用户，不吞错。 */
public class VaultException extends Exception {

    private final String stage;

    public VaultException(String stage, String message) {
        super(message);
        this.stage = stage;
    }

    public VaultException(String stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }

    public String display() {
        String m = getMessage();
        return stage + "：" + (m == null || m.isEmpty() ? "未知错误" : m);
    }
}
