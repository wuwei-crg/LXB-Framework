# LXB-Ignition (Android On-Device Runtime)

`android/LXB-Ignition` is the Android runtime workspace of LXB-Framework.

Current direction:

1. APK-first user workflow
2. On-device Java backend (`lxb-core`)
3. Route-Then-Act FSM execution on Android device

This module no longer treats WebConsole/Python runtime as the primary path.

## Module Layout

1. `app`
- Android UI (Control / Tasks / Config / Logs)
- Wireless ADB bootstrap and native start flow
- Local TCP client to `lxb-core` (`127.0.0.1:<port>`)

2. `lxb-core`
- Device-side Java backend service
- LXB-Link protocol dispatch
- Cortex FSM (`INIT -> TASK_DECOMPOSE -> APP_RESOLVE -> ROUTE_PLAN -> ROUTING -> VISION_ACT -> FINISH/FAIL`)
- Task queue and scheduler

## Runtime Flow

1. User submits task in APK
2. App sends command to local `lxb-core` via LXB-Link
3. `lxb-core` runs Cortex FSM and optional schedule pipeline
4. Trace/status is pushed back to APK UI

## Build

From `android/LXB-Ignition`:

```bash
./gradlew :app:assembleDebug
```

Windows:

```bat
gradlew.bat :app:assembleDebug
```

During app build, Gradle will also:

1. Build `lxb-core` dex jar (`:lxb-core:buildDex`)
2. Build native starter binaries used by wireless bootstrap

## Run

Recommended runtime path:

1. Install APK to device (Android 11+)
2. In app `Control` tab, start Wireless ADB guide
3. Complete pairing code input from notification
4. Start native core (`app_process`) from app
5. Configure LLM / map source in `Config`
6. Submit chat task or create schedule task

## Documentation Entry

1. Project root quick start: `../../README.md`
2. Docs index: `../../docs/README.md`
3. On-device architecture: `../../docs/en/on_device_architecture.md` / `../../docs/zh/on_device_architecture.md`

## Notes

1. If docs conflict with code behavior, code is source of truth.
2. Keep this README aligned with Android on-device runtime only.
