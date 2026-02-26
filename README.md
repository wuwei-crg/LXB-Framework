<div align="center">

# LXB-Framework

### An Android Automation Framework with Visual-Language Model Integration

**Route-Then-Act**: Build navigation maps, route to target pages, then execute tasks with VLM guidance.

[![Python](https://img.shields.io/badge/Python-3.9+-blue.svg)](https://www.python.org/downloads/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/wuwei-crg/LXB-Framework.svg?style=social)](https://github.com/wuwei-crg/LXB-Framework)
[![Documentation](https://img.shields.io/badge/docs-latest-brightgreen.svg)](docs/en)

---

[English](README.md) | [中文文档](README.zh.md)

</div>

---

> **A note before you dive in**
>
> This is an exploratory side project built by an undergraduate student — very much a Work in Progress.
> The code is rough, many corner cases are not handled, and I make no promises about active maintenance.
> I stumbled onto the idea of combining VLMs with XML accessibility trees and found it genuinely
> interesting, so I documented it here hoping it might be useful or spark ideas for others.
>
> If you are a researcher or engineer working in this space and spot something wrong or improvable,
> feedback and PRs are warmly welcome. Please be gentle though — this is a learning project
> and the author is still figuring things out.

---

## Overview

LXB-Framework is an engineering system for Android automation with two core goals:

1. **Build reusable navigation maps** of Android apps automatically (LXB-MapBuilder)
2. **Route to target pages first, then execute tasks** using VLM guidance (LXB-Cortex)

### Key Features

- **Map-Driven Automation**: Build app navigation maps once, reuse for multiple tasks
- **Route-Then-Act Pattern**: Navigate deterministically, then execute with AI guidance
- **VLM-XML Fusion**: Combine vision-language model understanding with XML hierarchy for reliable element location
- **Retrieval-First Positioning**: Use resource_id/text over hardcoded coordinates
- **Web Console**: Unified interface for debugging, mapping, and task execution

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     User Interface                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Web Console  │  │ Python API   │  │  Examples    │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
└─────────┼──────────────────┼──────────────────┼─────────────┘
          │                  │                  │
┌─────────┴──────────────────┴──────────────────┴─────────────┐
│                     Core Modules                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ LXB-Cortex   │  │LXB-MapBuilder│  │  LXB-Link    │      │
│  │   (FSM)      │  │  (VLM+XML)   │  │  (Protocol)  │      │
│  └──────────────┘  └──────────────┘  └──────┬───────┘      │
└─────────────────────────────────────────────┼───────────────┘
                                               │
┌─────────────────────────────────────────────┴───────────────┐
│                  Android Device                               │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              LXB-Server (Shizuku Service)            │   │
│  │  Accessibility Service + Input Injection             │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Modules

| Module | Description | Code Path |
|--------|-------------|-----------|
| **LXB-Link** | Device communication client with reliable UDP protocol | `src/lxb_link/` |
| **LXB-Server** | Android-side service for input injection and UI perception | `android/LXB-Ignition/` |
| **LXB-MapBuilder** | Automatic app navigation map builder using VLM + XML | `src/auto_map_builder/` |
| **LXB-Cortex** | Route-Then-Act automation engine with FSM runtime | `src/cortex/` |
| **LXB-WebConsole** | Web interface for debugging and task execution | `web_console/` |

## Quick Start

### Prerequisites

- Python 3.9+
- Android device with Shizuku installed
- VLM API access (Qwen-VL-Plus or GPT-4o recommended)

### Installation

```bash
# Clone the repository
git clone https://github.com/wuwei-crg/LXB-Framework.git
cd LXB-Framework

# Install dependencies
pip install -r requirements.txt
```

### Basic Usage

```python
from lxb_link import LXBLinkClient

# Connect to device
client = LXBLinkClient("192.168.1.100", 12345)
client.connect()
client.handshake()

# Take a screenshot
screenshot = client.screenshot()

# Find and tap an element
nodes = client.find_node("Settings", match_type="text")
if nodes:
    x, y = nodes[0]["bounds"]
    client.tap(x, y)
```

### Launch Web Console

```bash
cd web_console
python app.py
```

Then open `http://localhost:5000/` in your browser.

## Documentation

### Module Documentation

- [LXB-Link](docs/en/lxb_link.md) - Device communication protocol
- [LXB-Server](docs/en/lxb_server.md) - Android service architecture
- [LXB-MapBuilder](docs/en/lxb_map_builder.md) - Map building engine
- [LXB-Cortex](docs/en/lxb_cortex.md) - Route-Then-Act execution
- [LXB-WebConsole](docs/en/lxb_web_console.md) - Web console interface

### Guides

- [Quick Start Guide](docs/en/quickstart.md)
- [Configuration Reference](docs/en/configuration.md)
- [Examples](examples/)

## Design Philosophy

### Route-Then-Act

Instead of using VLM for every action, LXB-Framework:

1. **Build a map** of the app's navigation structure
2. **Route deterministically** to the target page using the map
3. **Execute tasks** on the target page with VLM guidance

This approach reduces VLM API calls, increases reliability, and enables task reproducibility.

### Retrieval-First Positioning

Elements are located using semantic attributes (resource_id, text) rather than hardcoded coordinates, ensuring reliability across different devices and screen sizes.

### VLM-XML Fusion

- **VLM** provides semantic understanding (what is this button?)
- **XML** provides precise positioning (resource_id, bounds)
- **Fusion** combines both for reliable automation locators

## Project Structure

```
LXB-Framework/
├── android/LXB-Ignition/    # Android service (Shizuku)
├── docs/
│   ├── zh/                  # Chinese documentation
│   └── en/                  # English documentation
├── examples/                # Usage examples
├── src/
│   ├── cortex/              # Route-Then-Act engine
│   ├── auto_map_builder/    # Map building engine
│   └── lxb_link/            # Device communication
└── web_console/             # Web interface
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

<div align="center">

**[Documentation](docs/en)** | **[Examples](examples/)** | **[Issues](https://github.com/wuwei-crg/LXB-Framework/issues)**

</div>
