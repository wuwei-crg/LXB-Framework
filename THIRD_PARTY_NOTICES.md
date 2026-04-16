# Third-Party Notices

This document records third-party references used in AutoLXB.

## Shizuku

- Project: https://github.com/RikkaApps/Shizuku
- License: Apache License 2.0
- Upstream reference paths reviewed:
  - `manager/src/main/jni/starter.cpp`
  - `starter/src/main/java/moe/shizuku/starter/ServiceStarter.java`

### What Is Inspired by Shizuku

The following parts are design-level references inspired by Shizuku's public implementation:

1. **Starter model (`app_process` bootstrap)**
   - Use a small native starter binary to launch Java service code via `app_process`.
   - Keep startup path independent from Android app process lifecycle.

2. **Detached process launch pattern**
   - Use `fork + setsid + chdir("/") + stdio redirection` before `exec(app_process)`.
   - Use `--nice-name` to make process identity stable and observable.

3. **Lifecycle handling around startup**
   - Stop/clean old process instances before a new start attempt.
   - Keep launch/stop as explicit control actions rather than implicit app lifecycle behavior.

### What Is Implemented Independently in AutoLXB

The following components are project-specific implementations and are not copied from Shizuku:

- LXB protocol/TCP link and command set
- Cortex FSM / Route-Then-Act execution pipeline
- Task scheduler, map sync, and runtime data model
- Wireless bootstrap service state machine and verification strategy (port readiness, retries, diagnostics)

### Compliance Note

AutoLXB keeps this attribution to document architectural inspiration and avoid ambiguity.
For the full Apache 2.0 license text, see:
http://www.apache.org/licenses/LICENSE-2.0
