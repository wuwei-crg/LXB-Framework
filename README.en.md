<div align="center">

<img src="resources/logo.jpg" alt="LXB Logo" width="160" />

# LXB-Framework

**Experimental Android automation framework focused on repetitive, linear daily tasks**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-34A853?logo=android&logoColor=white)]()
[![Latest Release](https://img.shields.io/github/v/release/wuwei-crg/LXB-Framework?label=Release)](https://github.com/wuwei-crg/LXB-Framework/releases)

**English** | [中文](README.md)

</div>

LXB-Framework does not let the model freely explore the whole phone UI. It follows a **Route-Then-Act** pipeline: deterministic routing is handled first, and the vision model only steps in when real dynamic interaction is needed.

---

## Software Preview & Feature Overview

![Software preview](resources/software_en.png)

Current core capabilities:

- **Direct tasks**: submit one natural-language task and run it immediately
- **Scheduled tasks**: one-shot / daily / weekly execution, with list-level enable / disable toggles
- **Notification-triggered tasks**: trigger tasks from notifications using package, title, body, and optional LLM filtering
- **Task route editing**: each task can keep its own task route, and the frontend can manually delete noisy trace actions before saving the final route
- **Playbook fallback**: add step-by-step instructions for apps that do not have a stable route yet
- **Dual startup paths**: `Wireless ADB` for non-root devices, `Root startup` for rooted devices
- **Trace page**: structured core trace cards, detail viewer, and local export

## How It Works

The Route-Then-Act pipeline is supported by several cooperating parts:

- **Phase split**: each task is split into route navigation and visual execution. Anything stable enough for routing should not consume vision-model budget.
- **FSM orchestration**: `INIT -> TASK_DECOMPOSE -> APP_RESOLVE -> ROUTING -> VISION_ACT -> FINISH/FAIL`
- **`app_process` daemon**: `lxb-core` runs as a shell-level background process outside the normal Android app lifecycle, which makes it suitable for always-on runtime, schedules, and notification triggers
- **Device-side separation**: `LXB-Ignition` handles startup, configuration, task management, and logs; `lxb-core` handles local automation execution

![Overall architecture](resources/architecture_overall.png)

![Framework internal architecture](resources/architecture_LXB-Framework.png)

## Current Product Shape

The app is currently organized into four main pages:

- **Home**: start / stop core, view runtime state, and jump into first-time setup
- **Tasks**: quick task, schedules, notification triggers, and recent runs
- **Config**: control mode config, device-side LLM config, unlock policy, route sync/source, and language
- **Logs**: trace cards, detail dialog, and trace export

Important UX points in the current design:

- Home is optimized around one thing first: **start core**. When core is running, stop is emphasized. When core is offline, the two startup paths are emphasized.
- The Tasks page puts **task runtime status** first, then splits task abilities into **Quick task / Schedules / Notification triggers / Recent runs**.
- Schedules and notification rules both support **direct toggling from the list page** without opening the edit page.
- The route editor is currently **manual-review only**: it edits the **latest captured trace**, not only successful traces.
- `task route mode` is shown as **On / Off** in the frontend. “On” means execute with saved route support.
- Input prefers **ADB Keyboard** when available and falls back automatically otherwise.
- Touch injection supports both **Shell** and **UIAutomator**.
- UI language follows the system by default: Chinese devices default to Chinese, everything else defaults to English; once changed manually, the manual choice wins.

## Requirements

Before starting, make sure:

- you are using a real Android device on **Android 11 (API 30)** or above
- for **Wireless ADB startup**: Developer Options, USB debugging, and Wireless debugging are enabled
- for **Root startup**: the device is rooted and can grant `su`
- an **OpenAI Chat Completions-compatible** LLM / VLM endpoint is configured
  - the app now auto-completes `/chat/completions`
  - you can enter a higher-level base URL and the app shows the resolved final request URL in real time

## Quick Start

### Option 1: Non-root device (`Wireless ADB`)

1. Install the latest APK from [Releases](https://github.com/wuwei-crg/LXB-Framework/releases)
2. Enable the required developer settings on the phone:
   - `USB debugging`
   - `Wireless debugging`
   - **USB debugging must stay enabled, otherwise process keepalive may fail**
3. Some ROMs need extra adjustments:

   | ROM | Action |
   |-----|--------|
   | MIUI / HyperOS (Xiaomi, POCO) | enable `USB debugging (Security settings)` |
   | ColorOS (OPPO / OnePlus) | disable `Permission monitoring` |
   | Flyme (Meizu) | disable `Flyme payment protection` |

4. Open `LXB-Ignition` and tap **ADB startup**
5. Complete the guide once:
   - open Developer Options
   - enable Wireless debugging
   - open `Pair device with pairing code`
   - enter the 6-digit code through the app notification
6. After the first pairing, later startups usually do not need re-pairing as long as Wireless debugging is enabled

### Option 2: Rooted device (`Root startup`)

1. Install the APK
2. Tap **Root startup** on the home page
3. Confirm root permission can be granted
4. The app starts `lxb-core` directly through `su`

## Recommended First Configuration Pass

After core is up, check these pages in `Config`.

### 1. Control Mode Config

This page decides how taps, swipes, and input are executed:

- **Touch mode**: `Shell` / `UIAutomator`
- **Input mode**: installing **ADB Keyboard** is strongly recommended
- **Task-time Do Not Disturb**: do nothing / turn sound back on / fully mute

### 2. Device-side LLM Config

Fill in:

- `API Base URL`
- `API Key`
- `Model`

Current extras:

- **real resolved request URL preview**
- **multiple saved local LLM profiles**
- **masked API key display**
- **test-and-sync to device**

### 3. Unlock & Lock Policy

- auto unlock before route execution
- auto lock after task
- lockscreen PIN / password, only when swipe alone is not enough

### 4. Route Sync & Source

- set the MapRepo address
- choose runtime source (`stable` / `candidate` / `burn`)
- pull route assets by package or identifier
- inspect active map status

## Task Types

### Direct Tasks

Submit one natural-language task from the home page or quick-task page, for example:

```text
Open WeChat and send "hello" to File Transfer
Open Bilibili and publish a new post with title test and content test
```

### Scheduled Tasks

Create them in `Tasks -> Schedules`:

- one-shot / daily / weekly
- optional target package
- optional Playbook
- optional screen recording
- optional saved-route execution
- **can be enabled / disabled directly from the list page**

### Notification-Triggered Tasks

Create them in `Tasks -> Notification Triggers`:

- required package match
- optional title / body match
- optional LLM condition
- optional active time window
- optional recording
- optional route execution
- **can be enabled / disabled directly from the list page**

Current notification-trigger pipeline:

1. dump notifications
2. detect new notifications
3. evaluate rules in sequence
4. build the final task when matched
5. push that task into the core queue

## Task Routes

The current design has moved from “global app maps” toward **task-local route assets**.

### Current logic

- core records the latest task trace during execution
- if a task succeeds, the latest successful trace is also retained
- the frontend route editor now defaults to the **latest captured trace**, not only the latest successful one
- you can delete noisy actions and manually save the final task route
- saved task routes can be used by later routing passes

### Current frontend state

- **AI-assisted route optimization is temporarily hidden**
- only **manual review / manual deletion / manual save** is exposed right now
- the “Finish task directly after replay” switch controls whether:
  - a successful route replay ends the current task immediately
  - or the flow continues into later visual execution

## Trace & Debugging

The logs page is now a structured trace viewer instead of a plain log panel:

- each trace entry is shown as an individual card
- tapping a card opens structured details
- latest traces load first
- older traces load on upward scrolling
- cached traces can be exported locally

This is the most useful page for debugging FSM transitions, notification-trigger pipelines, route replay, and visual-action failures.

## Usage Notes

- set `LXB-Ignition` battery policy to **Unrestricted**
- without ADB Keyboard, Chinese input falls back to clipboard / shell-based paths and compatibility may vary by app
- for apps without stable routes, write short and explicit Playbooks
- some ROMs behave better with `Shell`, others with `UIAutomator`, so test both paths

## Developer Debug Workflow

After code changes, install a debug build to your phone:

1. connect the device and confirm `adb devices` can see it
2. go to `android/LXB-Ignition`
3. run:

```bash
./gradlew :app:installDebug
```

Then open the debug build of `LXB-Ignition` on the phone.

## Related Repositories

| Repository | Description |
|------------|-------------|
| [LXB-MapBuilder](https://github.com/wuwei-crg/LXB-MapBuilder) | route building and publishing tool |
| [LXB-MapRepo](https://github.com/wuwei-crg/LXB-MapRepo) | stable / candidate route repository |

## Acknowledgements

The `app_process` daemon design is inspired by [Shizuku](https://github.com/RikkaApps/Shizuku).

LXB-Framework implements its own Wireless ADB pairing, connection, and startup flow and does not depend on Shizuku at runtime. The project is also actively shared in the [LINUX DO community](https://linux.do/).

Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## License

MIT. See [LICENSE](LICENSE).

## Star Trend

[![Star History Chart](https://api.star-history.com/svg?repos=wuwei-crg/LXB-Framework&type=Date)](https://star-history.com/#wuwei-crg/LXB-Framework&Date)
