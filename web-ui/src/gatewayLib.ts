// The platform's own resource pages, from the gateway's @inductiveautomation/ignition-gateway-lib:
// the same Resource Definitions panel (Create Override, Move Definition, Remove Override) the
// gateway's settings pages and IA's modules use. Untyped, like webui.ts: 8.3 ships no typings.
import * as GatewayLib from "@inductiveautomation/ignition-gateway-lib";

const gl = GatewayLib as any;

export const ResourceModeSingletonPage = gl.ResourceModeSingletonPage;
export const ResourceModeDataGrid = gl.ResourceModeDataGrid;
export const FormContent = gl.FormContent;
export const useConfigurationModeContext =
  gl.useConfigurationModeContext as () => {
    mode: string;
  };

/** The module's resource type ids, as the platform's resource routes name them. */
export const RESOURCE = {
  policy: "com.operametrix.ignition.git/git-policy",
  remote: "com.operametrix.ignition.git/git-config-remote",
  runner: "com.operametrix.ignition.git/git-runner",
  sync: "com.operametrix.ignition.git/git-sync",
};
