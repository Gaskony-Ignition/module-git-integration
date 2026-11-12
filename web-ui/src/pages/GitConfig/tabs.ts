import React from "react";

/** Switches the page's tab, for a drawer that sends someone to the tab that edits a thing. */
export const OpenTabContext = React.createContext<(tab: string) => void>(
  () => undefined
);

export const useOpenTab = () => React.useContext(OpenTabContext);
