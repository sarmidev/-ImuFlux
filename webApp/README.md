# ImuFlux Web Backoffice

Compose Multiplatform Wasm JS host for the shared `:backofficeCore` diagnostics UI.

## Local run

```bash
export IMUFLUX_FIREBASE_API_KEY="your-web-api-key"
export IMUFLUX_FIREBASE_PROJECT_ID="your-project-id"
./gradlew :webApp:wasmJsBrowserDevelopmentRun
```

Alternatively, put the same values in `desktopApp/local.properties` (git-ignored):

```properties
firebase.apiKey=your-web-api-key
firebase.projectId=your-project-id
```

The `generateFirebaseConfig` Gradle task writes a generated Kotlin object used at startup.
If neither env vars nor local properties are present, empty placeholders are written so the
project still compiles (login will show a config-missing screen).

## Production build

```bash
./gradlew :webApp:wasmJsBrowserDistribution
```

Output: `webApp/build/dist/wasmJs/productionExecutable/`

## GitHub Pages

The workflow `.github/workflows/deploy-backoffice-web.yml` builds and deploys on push to `main`
(or via **Actions → Deploy backoffice web → Run workflow**).

Required repository secrets:

- `IMUFLUX_FIREBASE_API_KEY`
- `IMUFLUX_FIREBASE_PROJECT_ID`

Also enable **Settings → Pages → Source: GitHub Actions**.

Commit `kotlin-js-store/yarn.lock` so CI yarn installs stay reproducible.
