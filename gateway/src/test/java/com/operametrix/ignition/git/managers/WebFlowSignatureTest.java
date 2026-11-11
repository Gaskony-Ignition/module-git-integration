package com.operametrix.ignition.git.managers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The web-flow signature check: real GitHub signatures against the bundled keys, and a test key
 * signing payloads that are then broken one way at a time.
 */
class WebFlowSignatureTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final long KEY_CREATED = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
    private static final long SIGNED = Instant.parse("2026-11-10T00:00:00Z").getEpochSecond();
    private static final byte[] PAYLOAD = ("tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\n"
            + "author A <a@example.com> 1794300000 +0000\ncommitter GitHub <noreply@github.com> 1794300000 +0000\n"
            + "\nMerge pull request #1\n").getBytes(StandardCharsets.UTF_8);

    private static KeyPair pair;
    private static byte[] keyBody;
    private static WebFlowSignature.TrustedKey trusted;
    private static KeyPair otherPair;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        pair = g.generateKeyPair();
        otherPair = g.generateKeyPair();
        keyBody = keyBody((RSAPublicKey) pair.getPublic(), KEY_CREATED);
        List<WebFlowSignature.TrustedKey> parsed = WebFlowSignature.parseKeys(armor("PUBLIC KEY BLOCK", packet(6, keyBody)));
        assertThat(parsed).hasSize(1);
        trusted = parsed.get(0);
    }

    // ── Real GitHub signatures and the bundled keys ──────────────────────────────────────────

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = WebFlowSignatureTest.class.getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    private static List<WebFlowSignature.TrustedKey> bundled() throws Exception {
        return WebFlowSignature.parseKeys(new String(resource("github-web-flow.asc"), StandardCharsets.US_ASCII));
    }

    @Test
    void bundledKeysAreGitHubsTwoWithTheOldOneExpired() throws Exception {
        List<WebFlowSignature.TrustedKey> k = bundled();
        assertThat(k).extracting(WebFlowSignature.TrustedKey::fingerprintHex).containsExactly(
                "5DE3E0509C47EA3CF04A42D34AEE18F83AFDEB23", "968479A1AFF927E37D1A566BB5690EEEBB952194");
        assertThat(k.get(0).expires()).isEqualTo(1705435200L);
        assertThat(k.get(1).expires()).isNull();
        assertThat(k).noneMatch(WebFlowSignature.TrustedKey::revoked);
    }

    @Test
    void realGitHubMergeCommitsVerifyWithBothKeys() throws Exception {
        assertThat(WebFlowSignature.problem(resource("github-2026.payload"), resource("github-2026.asc"), bundled(), NOW))
                .isNull();
        // Signed by the old key before it expired: still valid.
        assertThat(WebFlowSignature.problem(resource("github-2023.payload"), resource("github-2023.asc"), bundled(), NOW))
                .isNull();
    }

    @Test
    void aRealSignatureOverAnotherPayloadFails() throws Exception {
        byte[] p = resource("github-2026.payload");
        p[p.length - 2] ^= 1;
        assertThat(WebFlowSignature.problem(p, resource("github-2026.asc"), bundled(), NOW)).contains("bad signature");
    }

    @Test
    void theSignedPayloadIsTheRawCommitWithoutItsGpgsigHeader(@TempDir Path dir) throws Exception {
        byte[] payload = resource("github-2026.payload");
        byte[] sig = resource("github-2026.asc");
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            // Rebuild GitHub's commit object from its payload and signature: git puts the gpgsig
            // header after committer, continuation lines indented by one space.
            String text = new String(payload, StandardCharsets.UTF_8);
            int blank = text.indexOf("\n\n");
            String sigText = new String(sig, StandardCharsets.US_ASCII).trim();
            String raw = text.substring(0, blank + 1) + "gpgsig " + sigText.replace("\n", "\n ") + "\n"
                    + text.substring(blank + 1);
            ObjectId id;
            try (ObjectInserter ins = git.getRepository().newObjectInserter()) {
                id = ins.insert(org.eclipse.jgit.lib.Constants.OBJ_COMMIT, raw.getBytes(StandardCharsets.UTF_8));
                ins.flush();
            }
            try (RevWalk w = new RevWalk(git.getRepository())) {
                RevCommit c = w.parseCommit(id);
                WebFlowSignature.Split s = WebFlowSignature.split(c.getRawBuffer());
                assertThat(s.payload()).isEqualTo(payload);
                assertThat(new String(s.signature(), StandardCharsets.US_ASCII).trim())
                        .isEqualTo(new String(c.getRawGpgSignature(), StandardCharsets.US_ASCII).trim());
                assertThat(WebFlowSignature.commitProblem(c.getRawBuffer(), bundled(), NOW)).isNull();
            }
        }
    }

    // ── Test key ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aWellFormedSignatureByTheTrustedKeyVerifies() throws Exception {
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().armored(), List.of(trusted), NOW)).isNull();
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().hash(10).armored(), List.of(trusted), NOW)).isNull();
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().issuerFingerprint().armored(), List.of(trusted), NOW))
                .isNull();
    }

    @Test
    void aTamperedPayloadFails() throws Exception {
        byte[] other = PAYLOAD.clone();
        other[10] ^= 1;
        assertThat(WebFlowSignature.problem(other, new Sig().armored(), List.of(trusted), NOW)).contains("bad signature");
    }

    @Test
    void aTamperedHashedSubpacketFails() throws Exception {
        Sig s = new Sig();
        s.tamperHashedAfterSigning = true;
        assertThat(WebFlowSignature.problem(PAYLOAD, s.armored(), List.of(trusted), NOW)).contains("bad signature");
    }

    @Test
    void onlyBinaryDocumentSignaturesAreAccepted() throws Exception {
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().type(0x01).armored(), List.of(trusted), NOW))
                .contains("binary-document");
    }

    @Test
    void sha1AndMd5AreRefused() throws Exception {
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().hash(2).armored(), List.of(trusted), NOW))
                .contains("hash algorithm 2");
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().hash(1).armored(), List.of(trusted), NOW))
                .contains("hash algorithm 1");
    }

    @Test
    void onlyVersion4RsaSignatures() throws Exception {
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().version(3).armored(), List.of(trusted), NOW))
                .contains("version 4");
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().pkAlgo(17).armored(), List.of(trusted), NOW))
                .contains("RSA");
    }

    @Test
    void anUnknownIssuerFails() throws Exception {
        Sig s = new Sig();
        s.signer = otherPair;
        // Another key's value can exceed this modulus, which is refused before RSA runs.
        assertThat(WebFlowSignature.problem(PAYLOAD, s.armored(), List.of(trusted), NOW))
                .containsAnyOf("bad signature", "out of range");
        Sig t = new Sig();
        t.issuerId = 0x0123456789ABCDEFL;
        assertThat(WebFlowSignature.problem(PAYLOAD, t.armored(), List.of(trusted), NOW)).contains("not trusted");
    }

    @Test
    void anIssuerOnlyInTheUnhashedAreaIsNotTrusted() throws Exception {
        Sig s = new Sig();
        s.issuerInUnhashed = true;
        assertThat(WebFlowSignature.problem(PAYLOAD, s.armored(), List.of(trusted), NOW)).contains("no issuer");
    }

    @Test
    void creationTimeIsRequiredAndNotInTheFuture() throws Exception {
        Sig none = new Sig();
        none.created = null;
        assertThat(WebFlowSignature.problem(PAYLOAD, none.armored(), List.of(trusted), NOW)).contains("creation time");
        Sig future = new Sig();
        future.created = NOW.getEpochSecond() + 3600;
        assertThat(WebFlowSignature.problem(PAYLOAD, future.armored(), List.of(trusted), NOW)).contains("future");
        Sig skew = new Sig();
        skew.created = NOW.getEpochSecond() + 60;
        assertThat(WebFlowSignature.problem(PAYLOAD, skew.armored(), List.of(trusted), NOW)).isNull();
    }

    @Test
    void anExpiredKeyOnlyCoversSignaturesMadeBeforeItExpired() throws Exception {
        long expiry = SIGNED - 10;
        WebFlowSignature.TrustedKey expired = new WebFlowSignature.TrustedKey(trusted.fingerprint(), trusted.key(),
                trusted.created(), expiry, false);
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().armored(), List.of(expired), NOW)).contains("expired");
        Sig before = new Sig();
        before.created = expiry - 100;
        assertThat(WebFlowSignature.problem(PAYLOAD, before.armored(), List.of(expired), NOW)).isNull();
    }

    @Test
    void aRevokedKeyVerifiesNothing() throws Exception {
        WebFlowSignature.TrustedKey revoked = new WebFlowSignature.TrustedKey(trusted.fingerprint(), trusted.key(),
                trusted.created(), null, true);
        assertThat(WebFlowSignature.problem(PAYLOAD, new Sig().armored(), List.of(revoked), NOW)).contains("revoked");
    }

    @Test
    void anUnknownCriticalSubpacketFails() throws Exception {
        Sig s = new Sig();
        s.extraHashed = new byte[] {2, (byte) (0x80 | 27), 3};
        assertThat(WebFlowSignature.problem(PAYLOAD, s.armored(), List.of(trusted), NOW)).contains("critical");
    }

    @Test
    void truncatedTrailingAndDoubledPacketsFail() throws Exception {
        byte[] pkt = new Sig().packet();
        String trunc = armor("SIGNATURE", Arrays.copyOf(pkt, pkt.length - 5));
        assertThat(WebFlowSignature.problem(PAYLOAD, trunc.getBytes(), List.of(trusted), NOW)).startsWith("signature not verified");
        byte[] two = concat(pkt, pkt);
        assertThat(WebFlowSignature.problem(PAYLOAD, armor("SIGNATURE", two).getBytes(), List.of(trusted), NOW))
                .contains("more than one packet");
        byte[] trailing = concat(pkt, new byte[] {0});
        assertThat(WebFlowSignature.problem(PAYLOAD, armor("SIGNATURE", trailing).getBytes(), List.of(trusted), NOW))
                .startsWith("signature not verified");
        // A body longer than the packet's MPI: trailing data inside the packet.
        Sig s = new Sig();
        s.trailingInBody = true;
        assertThat(WebFlowSignature.problem(PAYLOAD, s.armored(), List.of(trusted), NOW)).contains("trailing data");
        String good = new String(new Sig().armored(), StandardCharsets.US_ASCII);
        assertThat(WebFlowSignature.problem(PAYLOAD, (good + "junk\n").getBytes(), List.of(trusted), NOW))
                .contains("after the block");
        assertThat(WebFlowSignature.problem(PAYLOAD, (good + good).getBytes(), List.of(trusted), NOW))
                .startsWith("signature not verified");
        assertThat(WebFlowSignature.problem(PAYLOAD, "garbage".getBytes(), List.of(trusted), NOW))
                .startsWith("signature not verified");
    }

    @Test
    void aNumberWithAWholeLeadingZeroByteFails() throws Exception {
        Sig s = new Sig();
        s.padMpi = true;
        assertThat(WebFlowSignature.problem(PAYLOAD, s.armored(), List.of(trusted), NOW)).contains("length mismatch");
    }

    @Test
    void aBadArmorChecksumFails() throws Exception {
        String a = new String(new Sig().armored(), StandardCharsets.US_ASCII);
        int eq = a.lastIndexOf("\n=");
        String broken = a.substring(0, eq + 2) + (a.charAt(eq + 2) == 'A' ? 'B' : 'A') + a.substring(eq + 3);
        assertThat(WebFlowSignature.problem(PAYLOAD, broken.getBytes(), List.of(trusted), NOW)).contains("checksum");
    }

    @Test
    void aSignedCommitBuiltWithJGitVerifiesAndAnUnsignedOneDoesNot(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            ObjectInserter ins = git.getRepository().newObjectInserter();
            ObjectId tree = ins.insert(new org.eclipse.jgit.lib.TreeFormatter());
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(tree);
            PersonIdent who = new PersonIdent("GitHub", "noreply@github.com", NOW, java.time.ZoneOffset.UTC);
            cb.setAuthor(who);
            cb.setCommitter(who);
            cb.setMessage("Merge pull request #2\n");
            ObjectId unsigned = ins.insert(cb);
            byte[] payload = cb.build();
            cb.setGpgSignature(new GpgSignature(new Sig(payload).armored()));
            ObjectId signed = ins.insert(cb);
            ins.flush();
            assertThat(GitHubMerged.problem(git.getRepository(), signed, List.of(trusted), NOW)).isNull();
            assertThat(GitHubMerged.problem(git.getRepository(), unsigned, List.of(trusted), NOW))
                    .contains("not signed");
            assertThat(GitHubMerged.problem(git.getRepository(), signed, bundled(), NOW)).contains("not trusted");
        }
    }

    // ── Building test signatures ─────────────────────────────────────────────────────────────

    /** A v4 signature over a payload, with one knob per way it can go wrong. */
    private static final class Sig {
        final byte[] payload;
        int version = 4;
        int type = 0;
        int pkAlgo = 1;
        int hash = 8;
        Long created = SIGNED;
        Long issuerId;
        boolean useFingerprint;
        boolean issuerInUnhashed;
        boolean tamperHashedAfterSigning;
        boolean trailingInBody;
        boolean padMpi;
        byte[] extraHashed = new byte[0];
        KeyPair signer = pair;

        Sig() {
            this(PAYLOAD);
        }

        Sig(byte[] payload) {
            this.payload = payload;
            this.issuerId = trusted == null ? 0 : trusted.keyId();
        }

        Sig version(int v) {
            version = v;
            return this;
        }

        Sig type(int t) {
            type = t;
            return this;
        }

        Sig pkAlgo(int a) {
            pkAlgo = a;
            return this;
        }

        Sig hash(int h) {
            hash = h;
            return this;
        }

        Sig issuerFingerprint() {
            useFingerprint = true;
            return this;
        }

        byte[] packet() throws Exception {
            ByteArrayOutputStream hashed = new ByteArrayOutputStream();
            if (created != null) {
                hashed.write(new byte[] {5, 2});
                hashed.write(u32(created));
            }
            byte[] issuer = issuerPacket();
            if (!issuerInUnhashed) {
                hashed.write(issuer);
            }
            hashed.write(extraHashed);
            byte[] h = hashed.toByteArray();
            String jca = switch (hash) {
                case 1 -> "MD5withRSA";
                case 2 -> "SHA1withRSA";
                case 9 -> "SHA384withRSA";
                case 10 -> "SHA512withRSA";
                default -> "SHA256withRSA";
            };
            Signature s = Signature.getInstance(jca);
            s.initSign(signer.getPrivate());
            s.update(payload);
            s.update(new byte[] {(byte) version, (byte) type, (byte) pkAlgo, (byte) hash, (byte) (h.length >> 8), (byte) h.length});
            s.update(h);
            int n = 6 + h.length;
            s.update(new byte[] {4, (byte) 0xff, (byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
            byte[] sig = s.sign();
            if (tamperHashedAfterSigning) {
                h = h.clone();
                h[5] ^= 1; // the creation time, one second later
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(new byte[] {(byte) version, (byte) type, (byte) pkAlgo, (byte) hash, (byte) (h.length >> 8), (byte) h.length});
            body.write(h);
            byte[] un = issuerInUnhashed ? issuer : new byte[0];
            body.write(new byte[] {(byte) (un.length >> 8), (byte) un.length});
            body.write(un);
            body.write(new byte[] {0, 0});
            if (padMpi) {
                // A count eight bits larger than needed, and a zero byte in front to match it.
                int bits = new BigInteger(1, sig).bitLength() + 8;
                body.write(new byte[] {(byte) (bits >> 8), (byte) bits, 0});
                body.write(unsignedBytes(new BigInteger(1, sig)));
            } else {
                body.write(mpi(new BigInteger(1, sig)));
            }
            if (trailingInBody) {
                body.write(0);
            }
            return WebFlowSignatureTest.packet(2, body.toByteArray());
        }

        private byte[] issuerPacket() throws Exception {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (useFingerprint) {
                o.write(new byte[] {22, 33, 4});
                o.write(trusted.fingerprint());
            } else {
                o.write(new byte[] {9, 16});
                o.write(java.nio.ByteBuffer.allocate(8).putLong(issuerId).array());
            }
            return o.toByteArray();
        }

        byte[] armored() throws Exception {
            return armor("SIGNATURE", packet()).getBytes(StandardCharsets.US_ASCII);
        }
    }

    private static byte[] keyBody(RSAPublicKey k, long created) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(4);
        o.write(u32(created));
        o.write(1);
        o.write(mpi(k.getModulus()));
        o.write(mpi(k.getPublicExponent()));
        return o.toByteArray();
    }

    private static byte[] unsignedBytes(BigInteger v) {
        byte[] b = v.toByteArray();
        return b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private static byte[] mpi(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b[0] == 0) {
            b = Arrays.copyOfRange(b, 1, b.length);
        }
        byte[] out = new byte[b.length + 2];
        out[0] = (byte) (v.bitLength() >> 8);
        out[1] = (byte) v.bitLength();
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    private static byte[] u32(long v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** A new-format packet with a five-octet length. */
    private static byte[] packet(int tag, byte[] body) {
        byte[] out = new byte[body.length + 6];
        out[0] = (byte) (0xC0 | tag);
        out[1] = (byte) 255;
        System.arraycopy(u32(body.length), 0, out, 2, 4);
        System.arraycopy(body, 0, out, 6, body.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String armor(String kind, byte[] data) {
        int crc = WebFlowSignature.crc24(data);
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(data);
        String c = Base64.getEncoder().encodeToString(new byte[] {(byte) (crc >> 16), (byte) (crc >> 8), (byte) crc});
        return "-----BEGIN PGP " + kind + "-----\n\n" + b64 + "\n=" + c + "\n-----END PGP " + kind + "-----\n";
    }

    @Test
    void onlyAGitHubRemoteCanBeCheckedForAGitHubMerge() {
        List<String> hosts = List.of("github.com", "*.ghe.example");
        assertThat(GitHubMerged.notGitHub(hosts, "https://github.com/o/r.git")).isNull();
        assertThat(GitHubMerged.notGitHub(hosts, "git@github.com:o/r.git")).isNull();
        assertThat(GitHubMerged.notGitHub(hosts, "https://code.ghe.example/o/r.git")).isNull();
        assertThat(GitHubMerged.notGitHub(hosts, "https://gitlab.com/o/r.git")).contains("isn't GitHub");
        assertThat(GitHubMerged.notGitHub(hosts, "https://evilgithub.com/o/r.git")).contains("isn't GitHub");
    }
}
