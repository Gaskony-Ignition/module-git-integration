package com.operametrix.ignition.git;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import com.operametrix.ignition.git.managers.GitManager;
import org.eclipse.jgit.internal.transport.sshd.OpenSshServerKeyDatabase;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.List;

public class SshTransportConfigCallback implements TransportConfigCallback {
    /**
     * SHA256 fingerprint of the key the server last presented, for the remote-test result. Static
     * because sshd calls the key database from its own I/O thread, not the caller's.
     */
    public static volatile String lastFingerprint;

    String sshKey;

    public SshTransportConfigCallback(String sshKey) {
        this.sshKey = sshKey;
    }

    private SshSessionFactory createSshSessionFactory() {
        return new SshdSessionFactoryBuilder()
                .setHomeDirectory(new File(System.getProperty("user.home")))
                .setSshDirectory(new File(new File(System.getProperty("user.home")), ".ssh"))
                .setDefaultKeysProvider(f -> {
                    try {
                        Iterable<KeyPair> keyPairs = SecurityUtils.loadKeyPairIdentities(
                                null, null, new ByteArrayInputStream(sshKey.getBytes()), null);
                        return keyPairs;
                    } catch (IOException | GeneralSecurityException e) {
                        throw new RuntimeException("Failed to load SSH key", e);
                    }
                })
                .setServerKeyDatabase((homeDir, sshDir) -> new TrustOnFirstUse())
                .build(null);
    }

    @Override
    public void configure(Transport transport) {
        SshTransport sshTransport = (SshTransport) transport;
        sshTransport.setSshSessionFactory(createSshSessionFactory());
    }

    /** Known hosts live under {@code var}, which the config repo ignores, so they are never committed. */
    static Path knownHostsFile() {
        return GitManager.getDataFolderPath().resolve("var").resolve("git-module").resolve("known_hosts");
    }

    /**
     * Trust on first use: a host:port seen for the first time is recorded, a later different key is
     * refused. The known_hosts reading and writing is JGit's own (an internal class of the bundled
     * jgit.ssh.apache, so recheck it on a JGit upgrade); this only adds the accept-new policy.
     */
    static class TrustOnFirstUse implements ServerKeyDatabase {
        private final ServerKeyDatabase known;

        TrustOnFirstUse() {
            try {
                Files.createDirectories(knownHostsFile().getParent());
            } catch (IOException e) {
                throw new RuntimeException("Cannot create " + knownHostsFile().getParent(), e);
            }
            known = new OpenSshServerKeyDatabase(true, List.of(knownHostsFile()));
        }

        @Override
        public List<PublicKey> lookup(String connectAddress, InetSocketAddress remoteAddress,
                                      Configuration config) {
            return known.lookup(connectAddress, remoteAddress, config);
        }

        @Override
        public boolean accept(String connectAddress, InetSocketAddress remoteAddress,
                              PublicKey serverKey, Configuration config, CredentialsProvider provider) {
            String seen = KeyUtils.getFingerPrint(serverKey);
            lastFingerprint = seen;
            List<PublicKey> recorded = lookup(connectAddress, remoteAddress, config);
            if (recorded.isEmpty()) {
                // The database asks before adding a host and before creating the file; answer yes.
                return known.accept(connectAddress, remoteAddress, serverKey, config, new CredentialsProvider() {
                    @Override public boolean isInteractive() { return false; }
                    @Override public boolean supports(CredentialItem... items) { return true; }
                    @Override public boolean get(URIish uri, CredentialItem... items) {
                        for (CredentialItem i : items) {
                            if (i instanceof CredentialItem.YesNoType) {
                                ((CredentialItem.YesNoType) i).setValue(true);
                            }
                        }
                        return true;
                    }
                });
            }
            for (PublicKey k : recorded) {
                if (KeyUtils.compareKeys(k, serverKey)) {
                    return true;
                }
            }
            String was = recorded.stream().map(KeyUtils::getFingerPrint).collect(Collectors.joining(", "));
            throw new IllegalStateException("The SSH host key for " + connectAddress
                    + " has changed. Recorded: " + was + ". Presented: " + seen
                    + ". Connection refused; if the server was rebuilt on purpose, remove its line from "
                    + knownHostsFile() + " and connect again.");
        }
    }
}
