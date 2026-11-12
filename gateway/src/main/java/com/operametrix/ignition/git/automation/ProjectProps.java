package com.operametrix.ignition.git.automation;

import com.inductiveautomation.ignition.common.project.GlobalProps;
import com.inductiveautomation.ignition.common.xmlserialization.deserialization.XMLDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a replace (Sync — replace, runner Release) keeps this gateway's project properties
 * ({@code ignition/global-props/data.bin}) or takes the incoming project's. Keeping is the
 * default: the file holds per-gateway settings such as the identity provider and default database.
 */
final class ProjectProps {

    private static final Logger logger = LoggerFactory.getLogger(ProjectProps.class);

    static final String KEPT = "kept this gateway's project properties";

    /** Project to the incoming file last warned about, so a sync every 300 s warns once. */
    private static final Map<String, String> warned = new ConcurrentHashMap<>();

    private ProjectProps() {
    }

    /**
     * Settles {@code props} once the incoming project is in place: puts {@code kept} (the
     * gateway's bytes from before, or null) back when keeping, or when the incoming project has
     * none, so the project is never left without one.
     *
     * @return the note for the run's message, or null when neither side has the file
     */
    static String settle(Path props, byte[] kept, boolean keep, String from, String project,
                         GitEvent.Builder event) throws IOException {
        byte[] incoming = Files.isRegularFile(props) ? Files.readAllBytes(props) : null;
        if (incoming != null && (!keep || kept == null)) {
            return "took the project properties from the " + from;
        }
        if (kept == null) {
            return null;
        }
        Files.createDirectories(props.getParent());
        Files.write(props, kept);
        if (incoming != null) {
            String w = idpWarning(project, kept, incoming, from);
            if (w != null && !sha(incoming).equals(warned.put(project, sha(incoming)))) {
                logger.warn(w);
                GitEvents.fire(event.warning(w));
            }
        }
        return KEPT;
    }

    /**
     * The warning for keeping properties that name no identity provider when the incoming ones
     * name one (a sign-in that works on the branch would not here), or null.
     */
    static String idpWarning(String project, byte[] kept, byte[] incoming, String from) {
        String ours = idp(kept);
        String theirs = idp(incoming);
        if (ours == null || theirs == null || !ours.isEmpty() || theirs.isEmpty()) {
            return null;
        }
        return project + ": " + KEPT + ", which name no identity provider; the " + from
                + "'s name '" + theirs + "'. Turn off 'Keep this gateway's project properties' to take them.";
    }

    /**
     * The identity provider a data.bin names, "" for none, or null when it cannot be read. The file
     * is Ignition's gzipped binary serialisation of {@link GlobalProps}, so the platform's own
     * deserialiser reads it; its string table cannot be scanned for the value reliably.
     */
    static String idp(byte[] dataBin) {
        try {
            for (Object o : new XMLDeserializer().initDefaults().deserialize(dataBin).getRootObjects()) {
                if (o instanceof GlobalProps g) {
                    return g.getIdp() == null ? "" : g.getIdp().trim();
                }
            }
        } catch (Exception | LinkageError e) {
            logger.debug("Could not read project properties.", e);
        }
        return null;
    }

    private static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(b));
        }
    }
}
