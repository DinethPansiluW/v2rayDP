# 🚀 v2rayDP Desktop

A modern Windows desktop VPN client built with **Electron**, **React**, **TypeScript**, and **Vite**. Powered by **sing-box**, it combines profile management, SSID-based auto-connect rules, split tunneling, VPN hotspot sharing, DNS-level ad & threat blocking, and detailed bandwidth analytics in a clean dark interface.

---

### 📸 Screenshots

| Home Dashboard | Master Automation Engine |
| :---: | :---: |
| ![Home Dashboard](screenshots/1.png) | ![Master Automation Engine](screenshots/2.png) |
| **Home Dashboard** — Real-time download/upload speeds, session data, latency, VLESS/WS/TLS profiles & live engine logs | **Network Automation** — Auto-connect/disconnect rules based on active Wi-Fi SSID or LAN connections |

| Exclude & Split Tunneling | Hotspot & Local Network Sharing |
| :---: | :---: |
| ![Exclude & Split Tunneling](screenshots/3.png) | ![Hotspot & Local Network Sharing](screenshots/4.png) |
| **Exclude & Split Tunneling** — Per-network bypass rules, LAN bypass, QUIC (UDP 443) blocking & 1-click domain presets | **Hotspot & Network Sharing** — Share VPN via Direct ICS TUN bridging or local SOCKS5/HTTP proxy (`10808`) |

| DNS Ad & Threat Blocking | Data Usage Analytics |
| :---: | :---: |
| ![DNS Ad & Threat Blocking](screenshots/5.png) | ![Data Usage Analytics](screenshots/6.png) |
| **DNS Ad & Threat Blocking** — Fast-fail NXDOMAIN blocking with 66,000+ domains, tracker/ad filtering & strict mode | **Data Usage Analytics** — Daily, monthly & custom-range RX/TX charts, peak usage stats & CSV export |

| Application & Battery Settings | |
| :---: | :---: |
| ![Application & Battery Settings](screenshots/7.png) | |
| **Settings & Power Management** — Windows startup task, system tray behavior, Battery Saver Mode & low-battery auto-exit | |

---

### ✨ Key Features

* ⚡ **Dashboard & Profile Management**: Monitor live download/upload speeds, session usage, connection duration, and server latency (`ms`) while managing VLESS/VMess profiles with quick QR code, clipboard, and phone-import actions.
* 🤖 **Master Automation Engine**: Automatically triggers VPN actions (`Connect VPN`, `Do not connect`, etc.) using priority-ordered Wi-Fi SSID and LAN rules with fallback handling.
* 🔀 **Exclude & Split Tunneling**: Route selected apps, local LAN devices, or specific domains directly outside the VPN tunnel, complete with per-Wi-Fi rule profiles, QUIC (UDP 443) blocking, and 1-click presets (YouTube & CDN, Netflix, Spotify, Twitch, Local TLDs).
* 📡 **Hotspot & Local Network Sharing**: Share your encrypted VPN connection with phones, consoles, and smart TVs via **Direct ICS TUN Bridging** or a local **SOCKS5/HTTP Proxy** on port `10808`, with built-in diagnostics, auto-start, and disconnect recovery.
* 🛡️ **DNS Ad & Threat Blocking**: Block ads, telemetry trackers, and phishing/malware domains at the DNS & TUN layer via sing-box fast-fail (`NXDOMAIN`) with offline-seeded lists, live sinkhole counters, and optional Strict Mode.
* 📊 **Detailed Data Usage Analytics**: Track RX/TX bandwidth consumption across Today, Day, Month, Year, or Custom Ranges with daily averages, peak usage highlights, visual bar charts, and one-click CSV export.
* 🔋 **System Tray & Battery Saver Controls**: Launch silently on Windows startup via Task Scheduler, keep the VPN running in the system tray on close, enable Battery Saver Mode (pauses auto-latency checks, reduces polling, disables animations), and set a low-battery auto-exit threshold.

---

### ⚠️ Disclaimer

Use this software responsibly and only in accordance with applicable laws, network policies, and service terms.
