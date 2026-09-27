package io.github.flowable.plus.extension.decision;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Transport 缝的响应（ADR-0042 第 7 节）：<b>只报状态与字节</b>。
 *
 * <p>状态即 HTTP 状态码；{@link #STATUS_UNREACHABLE}（{@code 0}）表示<b>没有取得任何 HTTP 响应</b>
 * （连接 / 读取超时或 I/O 故障）。字节即响应体原文，不解析、不解释 —— 解释归 Provider 缝。</p>
 */
public final class DecisionTransportResponse {

    /** 「未取得响应」的状态约定值（超时 / I/O 故障） */
    public static final int STATUS_UNREACHABLE = 0;

    /** HTTP 状态码（{@link #STATUS_UNREACHABLE} = 未取得响应） */
    private final int status;

    /** 响应体字节 */
    private final byte[] body;

    /**
     * 构造一次出站响应。
     *
     * @param status HTTP 状态码；未取得响应时取 {@link #STATUS_UNREACHABLE}
     * @param body   响应体字节，不得为 null（无响应体取空数组）
     */
    public DecisionTransportResponse(final int status, final byte[] body) {
        this.status = status;
        this.body = Objects.requireNonNull(body, "响应体字节不得为 null（无响应体取空数组）").clone();
    }

    /**
     * 「未取得响应」的响应（超时 / I/O 故障）。
     *
     * @return 状态 {@link #STATUS_UNREACHABLE}、空响应体
     */
    public static DecisionTransportResponse unreachable() {
        return new DecisionTransportResponse(STATUS_UNREACHABLE, new byte[0]);
    }

    /**
     * HTTP 状态码。
     *
     * @return 状态码；未取得响应时取 {@link #STATUS_UNREACHABLE}
     */
    public int getStatus() {
        return status;
    }

    /**
     * 响应体字节（防御性副本）。
     *
     * @return 响应体字节副本
     */
    public byte[] getBody() {
        return body.clone();
    }

    /**
     * 响应体文本（UTF-8）：<b>即入站加工的入参形态</b>（{@code rawOutput} 的原文裸串来源）。
     *
     * @return 响应体文本
     */
    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
