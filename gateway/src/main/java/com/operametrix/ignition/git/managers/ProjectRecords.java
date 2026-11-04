package com.operametrix.ignition.git.managers;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionChangeEvent;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionListener;
import com.operametrix.ignition.git.records.GitProjectsConfigRecord;
import com.operametrix.ignition.git.records.GitRemoteCredentialsRecord;
import com.operametrix.ignition.git.records.GitReposUsersRecord;
import com.operametrix.ignition.git.records.GitRunnerRecord;
import com.operametrix.ignition.git.records.GitSyncRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.operametrix.ignition.git.GatewayHook.getContext;

/**
 * Removes the module's records for projects the gateway no longer has. Every record is keyed by
 * project name, so a deleted project left a sync failing on every tick and a credential reported
 * as broken, and a project later created under the same name inherited both.
 *
 * <p>A rename is handled the same way, because it is one: Ignition builds the new project in a
 * new folder and deletes the old one, {@code .git} included, so nothing is left to re-key.
 */
public final class ProjectRecords implements ResourceCollectionListener {
    private static final Logger logger = LoggerFactory.getLogger(ProjectRecords.class);

    // A release replacing a project's folder can briefly look like a delete; check once it settles.
    private static final long SETTLE_SECONDS = 5;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "git-project-records");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pending;

    @Override
    public void collectionAdded(ResourceCollectionChangeEvent e) {
    }

    @Override
    public synchronized void collectionDeleted(ResourceCollectionChangeEvent e) {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = executor.schedule(ProjectRecords::reconcile, SETTLE_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void collectionUpdated(ResourceCollectionChangeEvent e) {
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Gone means neither the project manager nor the disk has it. Either alone is not enough: the
     * manager can lag a folder copied in, and a release replacing a folder leaves it briefly empty.
     */
    public static boolean isGone(String project) {
        return project != null && !project.isBlank()
                && !getContext().getProjectManager().getNames().contains(project)
                && !Files.isDirectory(GitManager.getProjectFolderPath(project));
    }

    /** Forgets every project that is gone. Also run at startup, for deletes made while it was down. */
    public static synchronized void reconcile() {
        try {
            Set<String> gone = new LinkedHashSet<>();
            for (GitProjectsConfigRecord r : GitProjectsConfigRecord.listAll()) {
                if (isGone(r.getProjectName())) {
                    gone.add(r.getProjectName());
                }
            }
            // A sync needs no repo record, so it can outlive its project on its own.
            for (GitSyncRecord s : GitSyncRecord.listAll()) {
                if (isGone(s.getProject())) {
                    gone.add(s.getProject());
                }
            }
            for (String project : gone) {
                forget(project);
            }
            GitRunnerRecord runner = GitRunnerRecord.get();
            List<String> dropped = runner.pruneMissing(getContext().getProjectManager().getNames());
            if (!dropped.isEmpty()) {
                runner.save();
                logger.info("Cleared the runner delivery of deleted project(s) {}.", dropped);
            }
        } catch (Exception e) {
            logger.warn("Could not remove git records of deleted projects.", e);
        }
    }

    private static void forget(String project) {
        Set<String> credentials = new LinkedHashSet<>();
        GitProjectsConfigRecord rec = GitProjectsConfigRecord.findByProjectName(project);
        if (rec != null) {
            for (GitRemoteCredentialsRecord c : GitRemoteCredentialsRecord.listByProject(rec.getId())) {
                if (c.getSshKeyId() > 0) {
                    credentials.add("SSH:" + c.getSshKeyId());
                }
                if (c.getHttpsCredentialId() > 0) {
                    credentials.add("HTTPS:" + c.getHttpsCredentialId());
                }
                c.delete();
            }
            for (GitReposUsersRecord u : GitReposUsersRecord.listByProject(rec.getId())) {
                u.delete();
            }
            rec.delete();
        }
        GitSyncRecord sync = GitSyncRecord.findByProject(project);
        if (sync != null) {
            sync.delete();
        }
        // The last check still lists the project against its credential until it runs again.
        for (String c : credentials) {
            String[] p = c.split(":");
            CredentialCheck.checkSoon(p[0], Long.parseLong(p[1]));
        }
        logger.info("Project '{}' is no longer on the gateway; removed its git records.", project);
    }
}
