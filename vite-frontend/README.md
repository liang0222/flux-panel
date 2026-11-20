# Vite & HeroUI Template

This is a template for creating applications using Vite and HeroUI (v2).

[Try it on CodeSandbox](https://githubbox.com/frontio-ai/vite-template)

## Technologies Used

- [Vite](https://vitejs.dev/guide/)
- [HeroUI](https://heroui.com)
- [Tailwind CSS](https://tailwindcss.com)
- [Tailwind Variants](https://tailwind-variants.org)
- [TypeScript](https://www.typescriptlang.org)
- [Framer Motion](https://www.framer.com/motion)

## How to Use

To clone the project, run the following command:

```bash
git clone https://github.com/frontio-ai/vite-template.git
```

### Install dependencies

You can use one of them `npm`, `yarn`, `pnpm`, `bun`, Example using `npm`:

```bash
npm install
```

### Run the development server

With a local backend listening on `127.0.0.1:6365`, use the same-origin Vite proxy:

```bash
VITE_API_BASE= npm run dev
```

The proxy forwards `/api/v1` and upgrades `/system-info` while preserving the browser's Host and Origin. Set `FLUX_DEV_PROXY_TARGET` to point the proxy at a different local backend. To connect directly instead, set `VITE_API_BASE` to the backend URL and allow the exact Vite origin in the backend configuration (for example, `PANEL_ALLOWED_ORIGINS=http://localhost:3000`). Production deployments should not add a development origin by default.

### Build Web and mobile assets

- `npm run build` produces `dist/` for the regular Web deployment.
- `npm run build:mobile` produces `dist-mobile/` for the bundled Android and iOS WebViews. Copy the **entire contents** of a fresh `dist-mobile/`, including `assets/`, into `android-app/app/src/main/assets/` and `ios-app/WebApp/` from the repository root before building their respective native targets. Replace stale generated contents. The Xcode target copies `WebApp` as a folder resource. The generated directories are ignored by Git.

The Android app requires Android 7+ and an up-to-date Android System WebView or Chrome with AndroidX `WEB_MESSAGE_LISTENER` support (the bundle targets Chromium 89+). The iOS app targets iOS 15.6+. The mobile panel settings are available before sign-in because they select the server used for sign-in. Plain Web browsers do not expose the native settings page.

Mobile clients require a full HTTPS panel URL and a certificate whose hostname and trust chain the device accepts. A LAN hostname or IP address works with a matching trusted certificate; explicit ports and reverse-proxy path prefixes are preserved. Forward the panel API and `/system-info` WebSocket through that prefix. Upgrade an existing HTTP panel URL to HTTPS in mobile settings, then sign in again. Android uses the `https://appassets.androidplatform.net` origin. The iOS bundled file page uses an opaque `null` origin. To allow it for HTTP API and WebSocket requests, add the literal `null` to the backend's `PANEL_ALLOWED_ORIGINS` list (`PANEL_ALLOWED_ORIGINS=null` if it is the only extra origin). An opaque origin is shared by other contexts, so enable it only on a panel that needs iOS access.

### Setup pnpm (optional)

If you are using `pnpm`, you need to add the following code to your `.npmrc` file:

```bash
public-hoist-pattern[]=*@heroui/*
```

After modifying the `.npmrc` file, you need to run `pnpm install` again to ensure that the dependencies are installed correctly.

## License

Licensed under the [MIT license](https://github.com/frontio-ai/vite-template/blob/main/LICENSE).
