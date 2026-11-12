import React from "react";
import { Provider, ReactReduxContext } from "react-redux";
import type { Store } from "@reduxjs/toolkit";
import store from "./index";

// The platform's resource components read the gateway app's own Redux store: its gateway-lib
// API cache, the CSRF token (userSession), the active mode (gateway) and write permission
// (permissions). This page keeps its own store for its own API, so the gateway's is captured
// before ours is provided and handed back to just those components.
const GatewayStoreContext = React.createContext<Store | null>(null);

/** Wraps the page: remembers the gateway app's store, then provides the page's own. */
export const PageStores = ({ children }: { children: React.ReactNode }) => {
  const outer = React.useContext(ReactReduxContext);
  return (
    <GatewayStoreContext.Provider value={outer ? outer.store : null}>
      <Provider store={store}>{children}</Provider>
    </GatewayStoreContext.Provider>
  );
};

/** For the platform's components: the gateway app's store. */
export const OnGatewayStore = ({ children }: { children: React.ReactNode }) => {
  const gateway = React.useContext(GatewayStoreContext);
  return gateway ? (
    <Provider store={gateway}>{children}</Provider>
  ) : (
    <p className="gitcfg-hint">
      This view needs the gateway&apos;s web interface; open it from the
      gateway&apos;s menu.
    </p>
  );
};

/** Inside a platform component, back to the page's own store for the page's own queries. */
export const OnOwnStore = ({ children }: { children: React.ReactNode }) => (
  <Provider store={store}>{children}</Provider>
);
