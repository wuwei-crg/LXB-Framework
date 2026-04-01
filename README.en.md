<div align="center">

<img src="resources/logo.jpg" alt="LXB Logo" width="160" />

# LXB-Framework

**An experimental Android automation framework for repetitive, linear daily tasks**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-34A853?logo=android&logoColor=white)]()
[![Latest Release](https://img.shields.io/github/v/release/wuwei-crg/LXB-Framework?label=Release)](https://github.com/wuwei-crg/LXB-Framework/releases)

**English** | [中文](README.md)

</div>

Instead of letting the model roam freely, LXB-Framework uses a **Route-Then-Act** pipeline: a pre-built navigation map handles deterministic page routing, then a VLM takes over to handle the actual on-screen work.

---

## Software Preview & Features

![Software preview](resources/software_en.png)

Built on this pipeline design, the framework currently offers three task execution modes:

| Mode | Description | Example |
|------|-------------|---------|
| **Chat Task** | Type a one-time natural language request and execute immediately | `Help me order one large oat latte from the coffee app` |
| **Scheduled Task** | Set a trigger time (one-shot / daily / weekly) and let the daemon execute automatically | `Every weekday at 08:30, place my usual coffee order` |
| **Playbook Fallback** | Write a step-by-step playbook for apps without a navigation map | — |

## How It Works

The Route-Then-Act pipeline is powered by three core mechanisms working together:

- **Pipeline split** — tasks are divided into a deterministic routing phase (map-based, no vision) and a vision-based action phase (VLM handles dynamic UI).
- **FSM orchestration** — a state machine (INIT → TASK_DECOMPOSE → APP_RESOLVE → ROUTE_PLAN → ROUTING → VISION_ACT → FINISH/FAIL) keeps execution structured and traceable.
- **`app_process` daemon** — the backend runs as a shell-level process independent of the Android app lifecycle, enabling reliable background and scheduled execution without relying on Android's fragile service keep-alive mechanisms.

![Overall architecture](resources/architecture_overall.png)

![Framework internal architecture](resources/architecture_LXB-Framework.png)

## Requirements

Before getting started, make sure you have the following:

- Android **11 (API 30)** or higher (real device recommended; emulators may trigger app detection)
- **Developer Options** and **Wireless Debugging** enabled on the device (no root, no extra apps required)
- An **OpenAI-compatible** LLM/VLM endpoint (`/v1/chat/completions` format); any model provider works

## Quick Start

Once the requirements are met, follow these steps to get up and running:

1. **Enable Developer Options & Debugging**
   - Go to `Settings → Developer Options` and enable both **USB debugging** and **Wireless debugging**
   - **USB debugging must be enabled; otherwise the daemon process cannot stay alive**

2. **Check ROM-specific Developer Options** (required on some devices)

   | ROM | Action |
   |-----|--------|
   | MIUI / HyperOS (Xiaomi, POCO) | Enable "USB debugging (Security settings)" — a separate toggle from "USB debugging" |
   | ColorOS (OPPO / OnePlus) | Disable "Permission monitoring" |
   | Flyme (Meizu) | Disable "Flyme payment protection" |

3. **Install the APK** — download the latest `lxb-ignition-vX.Y.Z.apk` from [Releases](https://github.com/wuwei-crg/LXB-Framework/releases) and install it

4. **Pair the device** — open LXB-Ignition and follow the in-app pairing guide. The device screen will display a 6-digit pairing code; enter it when prompted. Subsequent launches reconnect automatically

5. **Start the daemon** — after pairing succeeds, the app automatically pushes the backend DEX to the device and starts the daemon via `app_process`. The status indicator will change to **Running**

6. **Configure LLM** — go to the `Config` tab and fill in:

   | Parameter | Description | Example |
   |-----------|-------------|---------|
   | API Base URL | Model endpoint (OpenAI-compatible) | `https://api.openai.com/v1` |
   | API Key | Corresponding API key | `sk-...` |
   | Model | Model name | `gpt-4o-mini`, `qwen-plus` |

7. **(Optional) Sync maps** — in `Config`, set the MapRepo URL to enable automatic stable map downloads. Without maps, the framework falls back to pure vision mode

## Running Your First Task

With everything set up, type your request in the home screen chat box to launch a task, for example:

```
Open Bilibili and post a moment with content "test" and title "test"
Open WeChat and send "hello" to File Transfer
```

The interface will display the current FSM state in real time as the task executes (ROUTE_PLAN → ROUTING → VISION_ACT).

## Scheduled Tasks

Beyond manual triggering, the framework also supports automatic scheduled execution. Open the `Tasks` tab to create a scheduled task:

- Set a trigger time (one-shot, daily, or weekly)
- Specify the target app package name
- Write the task instruction
- Optionally attach a **Playbook** for apps without a map

The daemon's `app_process` design ensures scheduled tasks fire on time even when the screen is locked or the app has been killed by the system.

## Building Maps for New Apps

As mentioned earlier, navigation maps are the foundation of the routing phase in the Route-Then-Act pipeline. Maps are built with [LXB-MapBuilder](https://github.com/wuwei-crg/LXB-MapBuilder) and distributed via [LXB-MapRepo](https://github.com/wuwei-crg/LXB-MapRepo). See the MapBuilder README for the full build workflow. Pre-built stable maps can be synced directly from the `Config` tab via the MapRepo URL.

## Usage Tips

- Set the battery policy for LXB-Ignition to **Unrestricted** (especially on MIUI / ColorOS / HyperOS / Honor ROM variants).
- If an app has no map, write a short **playbook** describing the steps — this significantly improves action stability compared to pure vision.

## Related Repositories

| Repository | Description |
|------------|-------------|
| [LXB-MapBuilder](https://github.com/wuwei-crg/LXB-MapBuilder) | Map construction and publishing tool |
| [LXB-MapRepo](https://github.com/wuwei-crg/LXB-MapRepo) | Stable / candidate navigation map artifacts |

## Acknowledgements

The `app_process` daemon design is inspired by [Shizuku](https://github.com/RikkaApps/Shizuku). LXB-Framework implements its own Wireless ADB pairing and connection and does not depend on Shizuku at runtime.
This project is also shared with and supported by the [LINUX DO community](https://linux.do/).

Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## Developer Debug Workflow

After code changes, you can quickly install a debug build to your phone for testing:

1. Connect your device and confirm ADB is available (for example, `adb devices` shows the device).
2. Go to `android/LXB-Ignition`.
3. Run:

```bash
./gradlew :app:installDebug
```

After installation, open the debug build of `LXB-Ignition` on the phone and start debugging.

## License

MIT. See [LICENSE](LICENSE).

## Star Trend

[![Star History Chart](https://api.star-history.com/svg?repos=wuwei-crg/LXB-Framework&type=Date)](https://star-history.com/#wuwei-crg/LXB-Framework&Date)
