# On-Device Architecture (Current)

## Scope

This document describes the **current production direction** of `AutoLXB`.

Primary target:

1. Android APK-first user flow
2. Java-side backend runtime in `lxb-core`
3. Route-Then-Act FSM executed on device

Legacy Python Cortex/WebConsole paths were removed from this repository.

## Runtime Layers

1. App UI layer (`android/LXB-Ignition/app`)
- Chat-style task input
- Config and task/schedule management
- Real-time task status rendering from trace events

2. Backend service layer (`android/LXB-Ignition/lxb-core`)
- UDP command server (`network/UdpServer.java`)
- Cortex facade (`cortex/CortexFacade.java`)
- FSM engine (`cortex/CortexFsmEngine.java`)
- Task/schedule manager (`cortex/CortexTaskManager.java`)
- Route/map utilities (`cortex/MapManager.java`, `cortex/RouteMap.java`)

3. System execution layer
- Shizuku + `app_process` startup path
- Device-side command execution, screenshot capture, activity/screen state sensing

4. Model layer
- LLM/VLM calls from device-side runtime
- FSM stages consume model outputs and produce executable commands

## Core Flow

1. User submits task in APK
2. Task enters queue managed by `CortexTaskManager`
3. FSM executes sub-task pipeline:
- `INIT`
- `APP_RESOLVE`
- `ROUTE_PLAN`
- `ROUTING`
- `VISION_ACT`
4. Trace events are pushed back to app UI
5. Task completes with success/failure and persisted result metadata

## Maps

Current state:

1. Runtime consumes local maps via `MapManager`
2. Missing/invalid map falls back to `VISION_ACT`

Repository split:

1. MapBuilder and map web tooling are in `LXB-MapBuilder`
2. `AutoLXB` keeps runtime map consumption logic only

## Scheduling and Persistence

1. Schedule definitions and task states are persisted in app-private storage
2. Scheduler triggers enqueue operations at configured time points
3. Worker thread executes tasks sequentially for deterministic behavior

## Legacy Boundary

The following parts are not the current source of truth in this repository:

1. Old WebConsole documentation and APIs
2. Historical map-builder path under Python docs

Use this doc and root README as the baseline for future updates.
