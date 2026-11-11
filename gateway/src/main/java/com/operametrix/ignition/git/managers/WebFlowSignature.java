package com.operametrix.ignition.git.managers;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Checks that a commit carries a valid OpenPGP signature by one of a few bundled keys — GitHub's
 * web-flow keys, which sign the commits GitHub makes itself (a pull request merged in its UI).
 * Behind the policy option "Only deliver commits merged on GitHub" (4.4.0).
 *
 * <p>Plain JDK crypto, deliberately narrow: one v4 signature packet of type 0x00 (binary
 * document), RSA with PKCS#1 v1.5, SHA-256/384/512, issuer and creation time read only from the
 * hashed subpackets (RFC 4880 §5.2.3–5.2.4). Anything else is "not verified"; nothing here throws.
 * JGit's own verifier needs BouncyCastle's bcpg, which the gateway does not ship, and a second
 * BouncyCastle beside the platform's older one is the version trap modules/CLAUDE.md warns about.
 */
public final class WebFlowSignature {

    private WebFlowSignature() {
    }

    /** A signature made later than this past the gateway's clock is refused. */
    static final long CLOCK_SKEW_SECONDS = 300;

    /** One trusted RSA key: its v4 fingerprint, creation and (if any) expiry. */
    public record TrustedKey(byte[] fingerprint, RSAPublicKey key, long created, Long expires, boolean revoked) {
        public long keyId() {
            long id = 0;
            for (int i = 12; i < 20; i++) {
                id = (id << 8) | (fingerprint[i] & 0xff);
            }
            return id;
        }

        public String fingerprintHex() {
            return HexFormat.of().withUpperCase().formatHex(fingerprint);
        }
    }

    /** Thrown inside the parsers; always turned into a refusal reason, never let out. */
    private static final class Bad extends Exception {
        Bad(String m) {
            super(m);
        }
    }

    // ── Commit ────────────────────────────────────────────────────────────────────────────────

    /** A commit's signed payload and its armored signature, split the way git splits them. */
    public record Split(byte[] payload, byte[] signature) {}

    /**
     * Splits a raw commit into the payload git signs (the commit without its {@code gpgsig}
     * header) and the signature. Null when the commit has no {@code gpgsig} header, or more than
     * one, or a malformed header block.
     */
    public static Split split(byte[] raw) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(raw.length);
        ByteArrayOutputStream sig = null;
        int i = 0;
        boolean inSig = false;
        int sigs = 0;
        while (i < raw.length) {
            int eol = indexOf(raw, (byte) '\n', i);
            if (eol < 0) {
                return null; // the header block always ends with a newline before the message
            }
            int len = eol - i;
            if (len == 0) {
                // Blank line: the message follows, copied as is.
                payload.write(raw, i, raw.length - i);
                break;
            }
            if (inSig && raw[i] == ' ') {
                sig.write('\n');
                sig.write(raw, i + 1, len - 1);
                i = eol + 1;
                continue;
            }
            inSig = false;
            if (startsWith(raw, i, "gpgsig ")) {
                if (++sigs > 1) {
                    return null;
                }
                sig = new ByteArrayOutputStream();
                sig.write(raw, i + 7, len - 7);
                inSig = true;
            } else {
                payload.write(raw, i, len + 1);
            }
            i = eol + 1;
        }
        if (sig == null) {
            return null;
        }
        sig.write('\n');
        return new Split(payload.toByteArray(), sig.toByteArray());
    }

    /**
     * Why the commit's signature does not verify against {@code keys} at {@code now}, or null when
     * it does.
     */
    public static String commitProblem(byte[] rawCommit, List<TrustedKey> keys, Instant now) {
        Split s = split(rawCommit);
        if (s == null) {
            return "the commit is not signed";
        }
        return problem(s.payload(), s.signature(), keys, now);
    }

    // ── Signature ─────────────────────────────────────────────────────────────────────────────

    /** Why {@code armoredSignature} is not a valid signature of {@code payload}, or null. */
    public static String problem(byte[] payload, byte[] armoredSignature, List<TrustedKey> keys, Instant now) {
        try {
            byte[] packets = dearmor(new String(armoredSignature, StandardCharsets.US_ASCII), "SIGNATURE");
            Reader r = new Reader(packets);
            int[] tag = new int[1];
            byte[] body = r.packet(tag);
            if (tag[0] != 2) {
                throw new Bad("not a signature packet");
            }
            if (!r.done()) {
                throw new Bad("more than one packet in the signature");
            }
            return check(payload, body, keys, now);
        } catch (Bad b) {
            return "signature not verified: " + b.getMessage();
        } catch (RuntimeException e) {
            return "signature not verified: malformed";
        }
    }

    private static String check(byte[] payload, byte[] sig, List<TrustedKey> keys, Instant now) throws Bad {
        Reader r = new Reader(sig);
        if (r.u8() != 4) {
            throw new Bad("not a version 4 signature");
        }
        if (r.u8() != 0x00) {
            throw new Bad("not a binary-document signature");
        }
        if (r.u8() != 1) {
            throw new Bad("not an RSA signature");
        }
        int hashAlgo = r.u8();
        String jca = switch (hashAlgo) {
            case 8 -> "SHA256withRSA";
            case 9 -> "SHA384withRSA";
            case 10 -> "SHA512withRSA";
            default -> throw new Bad("hash algorithm " + hashAlgo + " is not accepted");
        };
        int hashedLen = r.u16();
        byte[] hashed = r.bytes(hashedLen);
        int unhashedLen = r.u16();
        r.bytes(unhashedLen); // read for its length only: nothing in it is trusted
        r.bytes(2); // the left 16 bits of the hash: a quick check, never the decision
        BigInteger s = r.mpi();
        if (!r.done()) {
            throw new Bad("trailing data in the signature packet");
        }

        Long created = null;
        Long sigExpires = null;
        byte[] issuerFpr = null;
        Long issuerId = null;
        Reader h = new Reader(hashed);
        while (!h.done()) {
            int len = h.subpacketLength();
            if (len < 1) {
                throw new Bad("empty subpacket");
            }
            int type = h.u8();
            boolean critical = (type & 0x80) != 0;
            type &= 0x7f;
            byte[] data = h.bytes(len - 1);
            switch (type) {
                case 2 -> {
                    if (data.length != 4 || created != null) {
                        throw new Bad("bad creation time");
                    }
                    created = u32(data);
                }
                case 3 -> {
                    if (data.length != 4 || sigExpires != null) {
                        throw new Bad("bad signature expiry");
                    }
                    sigExpires = u32(data);
                }
                case 16 -> {
                    if (data.length != 8 || issuerId != null) {
                        throw new Bad("bad issuer key ID");
                    }
                    long id = 0;
                    for (byte b : data) {
                        id = (id << 8) | (b & 0xff);
                    }
                    issuerId = id;
                }
                case 33 -> {
                    if (data.length != 21 || data[0] != 4 || issuerFpr != null) {
                        throw new Bad("bad issuer fingerprint");
                    }
                    issuerFpr = Arrays.copyOfRange(data, 1, 21);
                }
                default -> {
                    if (critical) {
                        throw new Bad("unknown critical subpacket " + type);
                    }
                }
            }
        }
        if (created == null) {
            throw new Bad("no signature creation time");
        }
        if (issuerFpr == null && issuerId == null) {
            throw new Bad("no issuer in the signed part");
        }
        TrustedKey key = null;
        for (TrustedKey k : keys) {
            boolean fprOk = issuerFpr == null || Arrays.equals(issuerFpr, k.fingerprint());
            boolean idOk = issuerId == null || issuerId == k.keyId();
            if (fprOk && idOk) {
                key = k;
                break;
            }
        }
        if (key == null) {
            throw new Bad("signed by a key that is not trusted");
        }
        long nowS = now.getEpochSecond();
        if (created > nowS + CLOCK_SKEW_SECONDS) {
            throw new Bad("signature made in the future");
        }
        if (created < key.created()) {
            throw new Bad("signature older than its key");
        }
        if (key.revoked()) {
            throw new Bad("signing key revoked");
        }
        if (key.expires() != null && created >= key.expires()) {
            throw new Bad("signed after the key expired");
        }
        if (sigExpires != null && sigExpires != 0 && nowS >= created + sigExpires) {
            throw new Bad("signature expired");
        }

        byte[] modulus = unsigned(key.key().getModulus());
        byte[] sBytes = unsigned(s);
        if (s.signum() <= 0 || s.compareTo(key.key().getModulus()) >= 0 || sBytes.length > modulus.length) {
            throw new Bad("signature value out of range");
        }
        byte[] padded = new byte[modulus.length];
        System.arraycopy(sBytes, 0, padded, modulus.length - sBytes.length, sBytes.length);
        try {
            Signature v = Signature.getInstance(jca);
            v.initVerify(key.key());
            v.update(payload);
            // RFC 4880 §5.2.4: version, type, algorithms, hashed length and subpackets, then the trailer.
            v.update(new byte[] {4, 0, 1, (byte) hashAlgo, (byte) (hashedLen >> 8), (byte) hashedLen});
            v.update(hashed);
            int n = 6 + hashedLen;
            v.update(new byte[] {4, (byte) 0xff, (byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
            if (!v.verify(padded)) {
                throw new Bad("bad signature");
            }
        } catch (Bad b) {
            throw b;
        } catch (Exception e) {
            throw new Bad("bad signature");
        }
        return null;
    }

    // ── Keys ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The RSA keys in an armored public key block. A key's expiry and revocation come from the
     * signature packets that follow it in the block — the block itself is what is trusted (bundled
     * with the module), so these are not verified. Non-RSA keys are skipped.
     */
    public static List<TrustedKey> parseKeys(String armored) {
        try {
            Reader r = new Reader(dearmor(armored, "PUBLIC KEY BLOCK"));
            List<TrustedKey> out = new ArrayList<>();
            int current = -1;
            long latestSelfSig = -1;
            int[] tag = new int[1];
            while (!r.done()) {
                byte[] body = r.packet(tag);
                if (tag[0] == 6 || tag[0] == 14) {
                    TrustedKey k = key(body);
                    latestSelfSig = -1;
                    current = -1;
                    if (k != null) {
                        out.add(k);
                        current = out.size() - 1;
                    }
                } else if (tag[0] == 2 && current >= 0) {
                    long[] info = selfSigInfo(body);
                    if (info == null) {
                        continue;
                    }
                    TrustedKey k = out.get(current);
                    if (info[0] == 0x20 || info[0] == 0x28) {
                        out.set(current, new TrustedKey(k.fingerprint(), k.key(), k.created(), k.expires(), true));
                    } else if (info[1] >= latestSelfSig) {
                        latestSelfSig = info[1];
                        Long exp = info[2] > 0 ? k.created() + info[2] : null;
                        out.set(current, new TrustedKey(k.fingerprint(), k.key(), k.created(), exp, k.revoked()));
                    }
                }
            }
            return out;
        } catch (Bad | RuntimeException e) {
            throw new IllegalArgumentException("Not a readable OpenPGP public key block: " + e.getMessage(), e);
        }
    }

    private static TrustedKey key(byte[] body) throws Bad {
        Reader r = new Reader(body);
        if (r.u8() != 4) {
            return null;
        }
        long created = u32(r.bytes(4));
        if (r.u8() != 1) {
            return null;
        }
        BigInteger n = r.mpi();
        BigInteger e = r.mpi();
        try {
            RSAPublicKey pk = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(new byte[] {(byte) 0x99, (byte) (body.length >> 8), (byte) body.length});
            sha1.update(body);
            return new TrustedKey(sha1.digest(), pk, created, null, false);
        } catch (Exception ex) {
            throw new Bad("bad RSA key");
        }
    }

    /** {@code [type, created, keyExpirySeconds]} of a key's signature packet, or null if unreadable. */
    private static long[] selfSigInfo(byte[] body) {
        try {
            Reader r = new Reader(body);
            if (r.u8() != 4) {
                return null;
            }
            int type = r.u8();
            r.u8();
            r.u8();
            Reader h = new Reader(r.bytes(r.u16()));
            long created = 0;
            long expiry = 0;
            while (!h.done()) {
                int len = h.subpacketLength();
                int t = h.u8() & 0x7f;
                byte[] d = h.bytes(len - 1);
                if (t == 2 && d.length == 4) {
                    created = u32(d);
                } else if (t == 9 && d.length == 4) {
                    expiry = u32(d);
                }
            }
            return new long[] {type, created, expiry};
        } catch (Bad | RuntimeException e) {
            return null;
        }
    }

    // ── Armor and packets ─────────────────────────────────────────────────────────────────────

    /**
     * The binary body of exactly one armored block of {@code kind}; its CRC-24, when present, must
     * match. Nothing but whitespace is allowed around the block.
     */
    static byte[] dearmor(String text, String kind) throws Bad {
        String begin = "-----BEGIN PGP " + kind + "-----";
        String end = "-----END PGP " + kind + "-----";
        String[] lines = text.replace("\r", "").split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].trim().equals(begin)) {
            throw new Bad("no " + kind.toLowerCase() + " block");
        }
        i++;
        while (i < lines.length && !lines[i].isBlank()) {
            if (!lines[i].contains(": ")) {
                break; // no armor headers and no blank line before the data
            }
            i++;
        }
        if (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        StringBuilder b64 = new StringBuilder();
        String crc = null;
        for (; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.equals(end)) {
                break;
            }
            if (crc != null) {
                throw new Bad("data after the checksum");
            }
            if (l.startsWith("=")) {
                crc = l.substring(1);
            } else {
                b64.append(l);
            }
        }
        if (i >= lines.length) {
            throw new Bad("unterminated block");
        }
        for (i++; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                throw new Bad("data after the block");
            }
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(b64.toString());
            if (crc != null) {
                byte[] c = Base64.getDecoder().decode(crc);
                if (c.length != 3 || crc24(data) != (((c[0] & 0xff) << 16) | ((c[1] & 0xff) << 8) | (c[2] & 0xff))) {
                    throw new Bad("checksum mismatch");
                }
            }
        } catch (IllegalArgumentException e) {
            throw new Bad("bad base64");
        }
        if (data.length == 0) {
            throw new Bad("empty block");
        }
        return data;
    }

    static int crc24(byte[] data) {
        int crc = 0xB704CE;
        for (byte b : data) {
            crc ^= (b & 0xff) << 16;
            for (int j = 0; j < 8; j++) {
                crc <<= 1;
                if ((crc & 0x1000000) != 0) {
                    crc ^= 0x1864CFB;
                }
            }
        }
        return crc & 0xFFFFFF;
    }

    /** Bounds-checked big-endian reader over one byte array. */
    private static final class Reader {
        private final byte[] b;
        private int p;

        Reader(byte[] b) {
            this.b = b;
        }

        boolean done() {
            return p == b.length;
        }

        int u8() throws Bad {
            if (p >= b.length) {
                throw new Bad("truncated");
            }
            return b[p++] & 0xff;
        }

        int u16() throws Bad {
            return (u8() << 8) | u8();
        }

        byte[] bytes(int n) throws Bad {
            if (n < 0 || n > b.length - p) {
                throw new Bad("truncated");
            }
            byte[] out = Arrays.copyOfRange(b, p, p + n);
            p += n;
            return out;
        }

        /**
         * An MPI: a bit count, then exactly the bytes it needs. GitHub writes the count rounded up
         * to whole bytes (4096 for a 4095-bit value), as GnuPG accepts, so the value may fall short
         * of the count by up to seven bits — never by a whole byte, and never exceed it.
         */
        BigInteger mpi() throws Bad {
            int bits = u16();
            if (bits == 0) {
                throw new Bad("empty number");
            }
            byte[] v = bytes((bits + 7) / 8);
            BigInteger n = new BigInteger(1, v);
            if (n.bitLength() > bits || bits - n.bitLength() >= 8) {
                throw new Bad("number length mismatch");
            }
            return n;
        }

        /** A subpacket length (RFC 4880 §5.2.3.1). */
        int subpacketLength() throws Bad {
            int c = u8();
            if (c < 192) {
                return c;
            }
            if (c < 255) {
                return ((c - 192) << 8) + u8() + 192;
            }
            long l = ((long) u8() << 24) | ((long) u8() << 16) | ((long) u8() << 8) | u8();
            if (l > Integer.MAX_VALUE) {
                throw new Bad("subpacket too long");
            }
            return (int) l;
        }

        /** One packet with a definite length, old or new format; partial lengths are refused. */
        byte[] packet(int[] tag) throws Bad {
            int ctb = u8();
            if ((ctb & 0x80) == 0) {
                throw new Bad("not a packet");
            }
            long len;
            if ((ctb & 0x40) != 0) {
                tag[0] = ctb & 0x3f;
                int c = u8();
                if (c < 192) {
                    len = c;
                } else if (c < 224) {
                    len = ((c - 192) << 8) + u8() + 192;
                } else if (c == 255) {
                    len = ((long) u8() << 24) | ((long) u8() << 16) | ((long) u8() << 8) | u8();
                } else {
                    throw new Bad("partial packet lengths are not accepted");
                }
            } else {
                tag[0] = (ctb >> 2) & 0x0f;
                switch (ctb & 3) {
                    case 0 -> len = u8();
                    case 1 -> len = u16();
                    case 2 -> len = ((long) u8() << 24) | ((long) u8() << 16) | ((long) u8() << 8) | u8();
                    default -> throw new Bad("indeterminate packet length");
                }
            }
            if (len > Integer.MAX_VALUE) {
                throw new Bad("packet too long");
            }
            return bytes((int) len);
        }
    }

    private static long u32(byte[] d) {
        return ((long) (d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b.length > 1 && b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static int indexOf(byte[] b, byte c, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == c) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] b, int at, String s) {
        if (b.length - at < s.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (b[at + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
