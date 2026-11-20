
import ReactDOM from "react-dom/client";
import { BrowserRouter, HashRouter } from "react-router-dom";

import App from "./App.tsx";
import { Provider } from "./provider.tsx";
import "@/styles/globals.css";

import { applyPanelAddresses, getActivePanel } from '@/api/network';
import { restoreSession } from '@/utils/auth';
import { getPanelAddresses, isWebViewFunc } from '@/utils/panel';

const mobile = import.meta.env.MODE === 'mobile' || isWebViewFunc();
const Router = mobile ? HashRouter : BrowserRouter;

async function start() {
  if (isWebViewFunc()) {
    try {
      applyPanelAddresses(await getPanelAddresses());
      const panel = getActivePanel();
      if (panel) await restoreSession(panel);
    } catch {
      // Keep first-run settings reachable if the host has no usable session.
    }
  }

  ReactDOM.createRoot(document.getElementById("root")!).render(
    <Router>
      <Provider><App /></Provider>
    </Router>,
  );
}

void start();
