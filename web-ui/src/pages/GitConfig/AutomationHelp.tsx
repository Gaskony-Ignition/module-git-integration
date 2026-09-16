import React from "react";

/**
 * Which way round to point the automation, and what each way costs.
 *
 * People arrive here having heard of git-sync and wondering whether this module is an
 * alternative to it. It is not: git-sync is the pull model, and Scheduled sync / Repo updates
 * are that model. So the real choice is between two modes of one thing, and this tab exists to
 * make that choice once rather than have every step hint at it.
 */
export default function AutomationHelp() {
  return (
    <div className="gitcfg-help">
      <h4 className="gitcfg-step">Push or pull</h4>
      <p className="gitcfg-auto-hint">
        &ldquo;git-sync&rdquo; is the pull model: something beside the gateway
        polls the repository on a timer and writes the files down. This module
        already does that — <strong>Scheduled sync</strong>, and the{" "}
        <strong>Repo updates</strong> delivery mode. A runner is the push model:
        GitHub hands a job to a machine you own, and that machine calls the
        gateway. So this is a choice between two modes of one product, not
        between two products, and you can use both on one gateway for different
        projects.
      </p>
      <div className="gitcfg-table-scroll">
        <table className="gitcfg-proj-table">
          <thead>
            <tr>
              <th />
              <th>Runner (push)</th>
              <th>git-sync (pull)</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Starts the work</td>
              <td>GitHub, on a tag or a push</td>
              <td>The gateway, on a timer</td>
            </tr>
            <tr>
              <td>Inbound firewall hole</td>
              <td>none</td>
              <td>none</td>
            </tr>
            <tr>
              <td>Extra machine needed</td>
              <td>yes, a runner host</td>
              <td>no</td>
            </tr>
            <tr>
              <td>Latency</td>
              <td>seconds</td>
              <td>up to the poll interval</td>
            </tr>
            <tr>
              <td>Can run a build step</td>
              <td>yes</td>
              <td>no — it copies raw repository files</td>
            </tr>
            <tr>
              <td>Delivers a release zip</td>
              <td>yes</td>
              <td>no</td>
            </tr>
            <tr>
              <td>Pass or fail visible in GitHub</td>
              <td>yes</td>
              <td>no</td>
            </tr>
            <tr>
              <td>Suits a gated promotion</td>
              <td>yes</td>
              <td>no — it takes whatever is on the branch</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p className="gitcfg-auto-hint">
        Pull wins for a gateway with no runner near it that simply tracks a
        branch. Push wins when the thing being delivered is built, or approved
        before it lands, or has to report success back to whoever released it.
      </p>

      <h4 className="gitcfg-step">Does the gateway need git credentials?</h4>
      <p className="gitcfg-auto-hint">
        Only when the gateway itself does the git work. This is the most useful
        thing to know before setting anything up, because it decides whether the
        gateway needs to reach GitHub at all.
      </p>
      <div className="gitcfg-table-scroll">
        <table className="gitcfg-proj-table">
          <thead>
            <tr>
              <th>What you are doing</th>
              <th>Credential on this gateway</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Runner, Release mode</td>
              <td>
                <strong>No.</strong> No repository, no remote, no credential —
                the runner does every git operation and the gateway receives an
                authenticated zip. Works on a gateway with no internet access.
              </td>
            </tr>
            <tr>
              <td>Runner, Repo updates mode</td>
              <td>
                <strong>Yes.</strong> The project must be registered on the
                Projects tab with a remote and a credential.
              </td>
            </tr>
            <tr>
              <td>Scheduled sync</td>
              <td>
                <strong>Yes</strong> — the same registration as Repo updates.
              </td>
            </tr>
            <tr>
              <td>Versioning the gateway config</td>
              <td>
                Only if you give the config repository a remote to push to.
              </td>
            </tr>
            <tr>
              <td>Committing a workflow from this page</td>
              <td>
                <strong>Yes</strong> — it pushes to the project&apos;s
                repository.
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <h4 className="gitcfg-step">Labels, and why a job can queue for ever</h4>
      <p className="gitcfg-auto-hint">
        A runner is registered with labels, and a workflow&apos;s{" "}
        <code>runs-on</code> lists labels. GitHub sends the job to a runner
        carrying <em>all</em> of them. GitHub adds <code>self-hosted</code> plus
        the OS and architecture automatically; any others are yours to choose.
        If the two lists do not match, the job waits with no error and no
        timeout for a day — so if a deploy never starts, check the labels before
        anything else.
      </p>
      <p className="gitcfg-auto-hint">
        Two gateways must not share a label: the job has to land on the machine
        that can reach the right gateway. When a second gateway and a second
        runner appear, do not edit every workflow — make the label an input of
        the workflow and pass it per environment. GitHub&apos;s own mechanism
        for this is runner groups, which private repositories cannot use below
        the Team plan.
      </p>
    </div>
  );
}
