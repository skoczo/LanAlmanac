package com.gnm.pkgmgmt.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PkgWorkerHmacSigner {

    public static final String HEADER_TIMESTAMP = "X-GNM-Timestamp";
    public static final String HEADER_SIGNATURE = "X-GNM-Signature";
    public static final int MIN_SECRET_BYTES = 32;

    private static final HexFormat HEX = HexFormat.of();

    private PkgWorkerHmacSigner() {}

    /**
     * Computes lowercase hex HMAC-SHA256 over:
     * {@code METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + HEX_SHA256(BODY)}
     * matching the Go {@code gnm-pkg-worker} middleware verbatim.
     */
    public static String computeSignature(
            byte[] secret, String method, String path, String timestamp, byte[] body) {
        if (secret == null || secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "Worker auth secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] bodyBytes = body != null ? body : new byte[0];
            String bodyHashHex = HEX.formatHex(sha256.digest(bodyBytes));

            String canonical =
                    method + "\n" + path + "\n" + timestamp + "\n" + bodyHashHex;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute worker HMAC-SHA256 signature", e);
        }
    }
}

