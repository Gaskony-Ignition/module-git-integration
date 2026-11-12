import React from "react";
import { Card, Checkbox, FormControlInput, yup } from "../../webui";
import { FormContent, RESOURCE } from "../../gatewayLib";
import { useFormContext } from "react-hook-form";
import SingletonTab from "./SingletonTab";
import { Snippet } from "./Credentials";
import { useGetStatusQuery, useSaveRunnerMutation } from "./GitConfig.service";

const n = "config.";

const schema = yup.object({});

const RunnerForm = ({ issued }: { issued: string | null }) => {
  const { getValues, watch } = useFormContext();
  const hasToken = !!watch(n + "hasToken");
  const { data: status } = useGetStatusQuery();
  const combined = status?.layout === "combined";
  return (
    <FormContent>
      <div className="cardContainer">
        <section id="git-runner">
          <Card title="RUNNER ACCESS">
            <p className="gitcfg-hint">
              A GitHub Actions runner on this network delivers releases and
              repository updates with this token.{" "}
              {combined
                ? "Repo updates are the gateway's: set Configure versioning › Delivery to Runner — repo updates. A release still needs the project set to Runner — release on the Projects tab."
                : "Each project still needs a runner delivery on the Projects tab."}
            </p>
            <FormControlInput
              name={n + "enabled"}
              id="git-runner-enabled"
              label="Runner deliveries"
              input={
                <Checkbox
                  defaultValue={getValues(n + "enabled")}
                  label="Accept deliveries from a runner"
                />
              }
            />
            <FormControlInput
              name={n + "newToken"}
              id="git-runner-token"
              label={`Token: ${hasToken ? "set" : "none"}`}
              input={
                <Checkbox
                  defaultValue={getValues(n + "newToken")}
                  label={
                    hasToken
                      ? "Generate a new token — the current one stops working"
                      : "Generate a token"
                  }
                />
              }
            />
            {issued ? (
              <div className="gitcfg-token-once">
                <strong>Copy this token now — it is not shown again.</strong>
                <Snippet text={issued} />
              </div>
            ) : null}
          </Card>
        </section>
      </div>
    </FormContent>
  );
};

// The runner settings on the platform's settings page, overridable per mode.
const Runner = () => {
  const [saveRunner] = useSaveRunnerMutation();
  // Shown once after it is generated; the gateway cannot return it again.
  const [issued, setIssued] = React.useState<string | null>(null);
  return (
    <SingletonTab
      type="runner"
      resourceType={RESOURCE.runner}
      title="Runner"
      schema={schema}
      transformData={(data: any) => ({
        ...data,
        // The token's ciphertext stays out of the form; only whether there is one.
        config: {
          enabled: !!data?.config?.enabled,
          hasToken: !!data?.config?.token,
          newToken: false,
        },
      })}
      send={async (config, saveTo) => {
        const res = await saveRunner({
          enabled: !!config.enabled,
          generateToken: !!config.newToken,
          saveTo,
        }).unwrap();
        setIssued(res.token ?? null);
      }}
    >
      <RunnerForm issued={issued} />
    </SingletonTab>
  );
};

export default Runner;
