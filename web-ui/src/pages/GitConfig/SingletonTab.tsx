import React from "react";
import { ResourceModeSingletonPage } from "../../gatewayLib";
import { OnGatewayStore, OnOwnStore } from "../../store/bridge";
import { useNoBackupMenu, useSingletonSave } from "./singleton";

/**
 * One of this module's singleton records on the platform's settings page: its header with Save
 * Changes and the Resource Definitions menu, and the Deployment Modes banner. The form is the
 * page's own, so it runs on the page's store again.
 */
const SingletonTab = ({
  type,
  resourceType,
  title,
  schema,
  categories,
  transformData,
  send,
  children,
}: {
  type: "policy" | "remote" | "runner";
  resourceType: string;
  title: string;
  schema: unknown;
  categories?: { label: string; id: string }[];
  transformData: (data: any) => any;
  send: (config: { [k: string]: unknown }, saveTo: string) => Promise<unknown>;
  children: React.ReactNode;
}) => {
  useNoBackupMenu();
  const save = useSingletonSave(type, send);
  return (
    <div className="gitcfg-singleton">
      <OnGatewayStore>
        <ResourceModeSingletonPage
          resourceType={resourceType}
          title={title}
          configSchema={schema}
          categories={categories}
          transformData={transformData}
          handleSubmit={save.handleSubmit}
          {...save.callbacks}
        >
          <OnOwnStore>{children}</OnOwnStore>
        </ResourceModeSingletonPage>
      </OnGatewayStore>
    </div>
  );
};

export default SingletonTab;
