package com.operametrix.ignition.git.automation;

import java.time.Instant;
import java.util.List;

/**
 * One git operation, as recorded in the Event log.
 *
 * <p>Built through {@link #of(String)} rather than the canonical constructor: eleven positional
 * arguments at a dozen call sites is unreadable, and most operations set four or five of them.
 */
public record GitEvent(String type, String outcome, String scope, String project, String user,
                       String branch, String remote, String commit, String message,
                       List<String> files, String timestamp) {

    public static final String COMMIT = "commit";
    public static final String PUSH = "push";
    public static final String PULL = "pull";
    public static final String AUTOCOMMIT = "autocommit";
    public static final String SYNC = "sync";

    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";

    public static final String SCOPE_PROJECT = "project";
    public static final String SCOPE_CONFIG = "config";

    public GitEvent {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static Builder of(String type) {
        return new Builder(type);
    }

    public boolean failed() {
        return FAILURE.equals(outcome);
    }

    public static final class Builder {
        private final String type;
        private String scope = SCOPE_PROJECT;
        private String project;
        private String user;
        private String branch;
        private String remote;
        private String commit;
        private String message;
        private List<String> files = List.of();

        private Builder(String type) {
            this.type = type;
        }

        public Builder scope(String v) {
            this.scope = v;
            return this;
        }

        public Builder config() {
            this.scope = SCOPE_CONFIG;
            return this;
        }

        public Builder project(String v) {
            this.project = v;
            return this;
        }

        public Builder user(String v) {
            this.user = v;
            return this;
        }

        public Builder branch(String v) {
            this.branch = v;
            return this;
        }

        public Builder remote(String v) {
            this.remote = v;
            return this;
        }

        public Builder commit(String v) {
            this.commit = v;
            return this;
        }

        public Builder message(String v) {
            this.message = v;
            return this;
        }

        public Builder files(List<String> v) {
            this.files = v == null ? List.of() : List.copyOf(v);
            return this;
        }

        public GitEvent success() {
            return build(SUCCESS);
        }

        /** Failure carries the reason in {@code message}, replacing any commit message set. */
        public GitEvent failure(String reason) {
            this.message = reason;
            return build(FAILURE);
        }

        private GitEvent build(String outcome) {
            return new GitEvent(type, outcome, scope, project, user, branch, remote, commit,
                    message, files, Instant.now().toString());
        }
    }
}
