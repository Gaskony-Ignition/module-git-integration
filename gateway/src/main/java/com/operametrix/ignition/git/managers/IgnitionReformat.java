package com.operametrix.ignition.git.managers;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tells Ignition's own rewriting of a project's JSON apart from an edit.
 *
 * <p>Importing a project makes the gateway write every {@code project.json} and
 * {@code resource.json} back in its own format: no final newline, an apostrophe as a unicode escape,
 * {@code "parent": ""} added to {@code project.json}, and a resource's {@code files} list in
 * whatever order it holds them. A repository written by anything else —
 * a build script, an editor — then shows every one of those files modified straight after a
 * clone or sync, with identical content. Counted as edits, they made Pull refuse for ever
 * ("local changes present") and Replace report overwriting changes nobody made.
 *
 * <p>So a modified {@code .json} file whose parsed content equals HEAD's is not a change. Only
 * modified files qualify: an added, removed or untracked file is a change whatever it holds.
 */
public final class IgnitionReformat {

    private IgnitionReformat() {
    }

    /** Uncommitted and untracked paths, minus those that differ from HEAD only in formatting. */
    public static Set<String> realChanges(Repository repo, Status status) {
        Set<String> all = new TreeSet<>(status.getUncommittedChanges());
        all.addAll(status.getUntracked());
        all.removeAll(reformatted(repo, status));
        return all;
    }

    /** Modified paths whose content is HEAD's, as Ignition rewrote it. */
    public static Set<String> reformatted(Repository repo, Status status) {
        Set<String> out = new TreeSet<>();
        ObjectId head;
        try {
            head = repo.resolve("HEAD^{tree}");
        } catch (Exception e) {
            return out;
        }
        if (head == null) {
            return out;
        }
        for (String path : status.getModified()) {
            if (path.endsWith(".json") && sameContent(repo, head, path)) {
                out.add(path);
            }
        }
        return out;
    }

    private static boolean sameContent(Repository repo, ObjectId headTree, String path) {
        try (RevWalk walk = new RevWalk(repo);
             TreeWalk tw = TreeWalk.forPath(repo, path, walk.parseTree(headTree))) {
            if (tw == null) {
                return false;
            }
            String committed = new String(repo.open(tw.getObjectId(0)).getBytes(),
                    StandardCharsets.UTF_8);
            Path file = repo.getWorkTree().toPath().resolve(path);
            String working = Files.readString(file, StandardCharsets.UTF_8);
            return normalise(JsonParser.parseString(committed))
                    .equals(normalise(JsonParser.parseString(working)));
        } catch (Exception e) {
            // Unparseable or unreadable: treat it as a real change rather than hide one.
            return false;
        }
    }

    /**
     * An empty {@code parent} is what Ignition writes for "no parent", so absent means the same;
     * and a resource's {@code files} list is a set, which Ignition writes in no fixed order.
     */
    private static JsonElement normalise(JsonElement e) {
        if (e != null && e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            JsonElement parent = o.get("parent");
            if (parent != null && parent.isJsonPrimitive() && parent.getAsString().isEmpty()) {
                o.remove("parent");
            }
            JsonElement files = o.get("files");
            if (files != null && files.isJsonArray()) {
                List<String> names = new ArrayList<>();
                for (JsonElement f : files.getAsJsonArray()) {
                    names.add(f.toString());
                }
                Collections.sort(names);
                JsonArray sorted = new JsonArray();
                for (String n : names) {
                    sorted.add(JsonParser.parseString(n));
                }
                o.add("files", sorted);
            }
        }
        return e;
    }
}
