import React from "react";
import { PageStores } from "../../store/bridge";
import GitConfig from "../GitConfig";

const RootPage = () => {
  return (
    <PageStores>
      <GitConfig />
    </PageStores>
  );
};

export default RootPage;
