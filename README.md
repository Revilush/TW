# TW

TW is a distributed Android device orchestration workspace. It combines a Node.js control server, a React operations dashboard, and Android clients that register over WebSocket and execute remote commands such as call, SMS, ring, and media playback.

## Scope

- `src/`: Node.js control plane and orchestration logic
- `dashboard/`: React/Vite operator dashboard
- `app/`: primary Android client
- `android-node-app/`: alternate Android client variant kept in the repo as a separate app project

## Architecture

1. Android nodes register with the server over WebSocket and send heartbeat and battery updates.
2. The server evaluates health and optional pool membership before marking a node runnable.
3. Runnable nodes are paired and scheduled for call workflows.
4. After configured call cycles, the orchestration flow can continue into SMS and media actions.
5. The dashboard consumes snapshot APIs plus a dashboard WebSocket stream for live operator visibility.

Core server modules:

- `deviceManager`: stores connected device state and command history
- `deviceHealthMonitor`: computes active/blocked/offline status
- `devicePoolManager`: restricts orchestration to an allowed phone-number pool
- `pairingEngine`: produces fair, recent-aware device pairings
- `schedulerEngine`: manages delayed call start and stop windows
- `orchestrationEngine`: advances device lifecycle across call, SMS, and media phases
- `dashboardHub`: broadcasts live dashboard snapshots

## Repository Layout

```text
.
|-- app/                 # Primary Android client
|-- android-node-app/    # Secondary Android client variant
|-- dashboard/           # React dashboard
|-- src/                 # Node.js server modules
|-- config.js            # Timing and orchestration configuration
|-- simulator.js         # WebSocket device simulator
`-- commands.txt         # Example local API calls
```

## Prerequisites

- Node.js 18+
- npm
- JDK 17+
- Android SDK and Android Studio for mobile builds

## Getting Started

### 1. Start the control server

```bash
npm install
npm start
```

Default server port: `3000`

Useful scripts:

```bash
npm run dev
npm run simulate
```

### 2. Start the dashboard

```bash
cd dashboard
npm install
npm run dev
```

If the API is not running on the default host, set `VITE_API_BASE_URL` before starting the dashboard.

### 3. Run the Android client

Open either `app/` or `android-node-app/` in Android Studio and build normally. The primary app stores runtime values such as server URL, phone number, and plan date in on-device preferences rather than in source control.

## Runtime Behavior

### Device eligibility

Devices are considered runnable only when:

- heartbeat freshness is within the configured timeout
- battery level passes the minimum threshold
- the plan due date is still valid
- the device is inside the allowed pool when pool enforcement is enabled

### Orchestration lifecycle

The main lifecycle is:

```text
IDLE -> CALL -> SMS -> MEDIA -> IDLE
```

The server tracks active calls, SMS batches, media sessions, and per-device phase state in memory.

## API Surface

Primary HTTP routes include:

- `GET /health`
- `GET /devices`
- `GET /devices/active`
- `GET /dashboard/snapshot`
- `GET /orchestration/state`
- `POST /orchestration/start`
- `POST /orchestration/stop`
- `POST /calls/schedule`
- `POST /sms/send`
- `POST /media/start`
- `GET /pool`
- `PUT /pool`

Primary WebSocket routes:

- `/ws`: device registration and command channel
- `/dashboard/ws`: live dashboard updates

## Local Configuration Policy

This repository is intended to stay publish-safe:

- keep SDK paths in local `local.properties` files only
- use environment variables or local IDE settings for machine-specific Java/SDK configuration
- do not commit secrets, tokens, keystores, or device-specific exports

Ignored local-only artifacts are defined in [`.gitignore`](./.gitignore).

## Notes

- The server is stateful and in-memory by design; restarting it clears live orchestration state.
- `simulator.js` is useful for testing the server without real Android devices.
- `commands.txt` contains example local requests for manual operator testing.
