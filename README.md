# IoT LED Controller – Real-time WebSocket Control with Mobile & Android Apps

A complete IoT system that lets you toggle an ESP32's built-in LED from **any mobile browser** or a **native Android app** — with Google Sign-In authentication, real-time WebSocket communication, and a full audit trail in Google Sheets.

The ESP32 implements the **WebSocket protocol from scratch** (no third-party library) — raw TLS sockets, manual frame construction with masking, and custom frame parsing. The backend runs serverless on Google Cloud Run.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Project Structure](#project-structure)
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Google Cloud Setup](#google-cloud-setup)
  - [1. Create a Project & Enable APIs](#1-create-a-project--enable-apis)
  - [2. Create a Service Account](#2-create-a-service-account)
  - [3. Grant Permissions](#3-grant-permissions)
  - [4. Share Google Sheets](#4-share-google-sheets)
  - [5. Set Up OAuth 2.0](#5-set-up-oauth-20)
- [Deployment (Cloud Run)](#deployment-cloud-run)
  - [Build & Push Docker Image](#build--push-docker-image)
  - [Deploy to Cloud Run](#deploy-to-cloud-run)
- [ESP32 Programming](#esp32-programming)
  - [Hardware](#hardware)
  - [Dependencies](#dependencies)
  - [Configuration](#configuration)
  - [Uploading the Sketch](#uploading-the-sketch)
- [Android App](#android-app)
  - [Building the APK](#building-the-apk)
  - [Configuration](#configuration-1)
- [Local Development & Testing](#local-development--testing)
- [How It Works](#how-it-works)
  - [The Manual WebSocket Implementation](#the-manual-websocket-implementation)
  - [Latency Measurement](#latency-measurement)
- [Troubleshooting](#troubleshooting)
- [Flowcharts](#flowcharts)
  - [ESP32 LED Controller Logic](#esp32-led-controller-logic)
  - [Complete System Interaction](#complete-system-interaction)
- [License](#license)

---

## Architecture Overview

![System Architecture](A_docs/System%20Architecture.png)

**Key architectural decisions:**

- **ESP32 ↔ Cloud Run** — The ESP32 connects directly to Cloud Run over **WebSocket Secure (WSS)** using a custom, library-free client. No MQTT broker, no third-party WebSocket library.
- **Cloud Run ↔ Google Sheets** — The server uses Google Application Default Credentials (ADC) to read/write sheets. **No service account key file is ever stored on disk.**
- **Web App / Android App ↔ Cloud Run** — Both clients use **HTTP POST** (not WebSocket) to send commands. The WebSocket channel exists only between the server and ESP32.
- **min-instances = 1** — Ensures the WebSocket connection to the ESP32 stays alive between user requests.

---

## Project Structure

```
iot-led-controller/
├── CloudRun_Backend_WebApp/         # Backend + Mobile Web Frontend
│   ├── server.js                    # Express + WebSocket + OAuth + Sheets logging
│   ├── sheets.js                    # Google Sheets helper (getRows, appendRow)
│   ├── public/
│   │   └── index.html               # Mobile-friendly UI with Google Sign-In (Pico.css)
│   ├── package.json                 # Dependencies: express, ws, googleapis, etc.
│   ├── Dockerfile                   # node:20-slim, port 8080
│   └── .gcloudignore
│
├── android-ws/                      # Native Android App (Kotlin)
│   ├── ESP32LedApp/
│   │   ├── app/
│   │   │   ├── build.gradle         # App module: SERVER_URL, GOOGLE_CLIENT_ID, deps
│   │   │   └── src/main/
│   │   │       ├── AndroidManifest.xml
│   │   │       ├── java/com/example/esp32led/
│   │   │       │   └── MainActivity.kt   # ~250 lines: sign-in, toggle, UI updates
│   │   │       └── res/                  # Layouts, drawables, themes, strings
│   │   ├── build.gradle             # Root: AGP 8.2.2 + Kotlin 1.9.22
│   │   ├── settings.gradle
│   │   ├── gradle.properties
│   │   ├── local.properties         # Points to local Android SDK
│   │   ├── gradlew / gradlew.bat    # Gradle wrapper (no global install needed)
│   │   └── gradle/wrapper/
│   ├── android_built_explanation.txt
│   ├── Step-by-step_Guide_ofbuilt.txt
│   └── interaction.json
│
├── Iot-project-arduino-code/        # ESP32 Firmware
│   └── ws-android-iot-project.ino   # Manual WebSocket client (no library)
│
└── README.md                        # This file
```

---

## Features

- **Real-time LED control** — Commands reach the ESP32 in < 10 ms after the WebSocket handshake.
- **Google Sign-In authentication** — Only users listed in the `allowed_users` sheet can toggle the LED.
- **Two client apps** — Mobile web app (served from Cloud Run) **and** native Android app (Kotlin).
- **Persistent audit logging** — Every on/off action is timestamped and written to the `logs` sheet, including end-to-end latency.
- **Latency measurement** — The server measures round-trip time from command to ESP32 status response.
- **Automatic reconnection** — The ESP32 retries the WebSocket connection every 5 seconds after disconnect.
- **Keep-alive pings** — The ESP32 sends a WebSocket ping every 30 seconds to prevent idle disconnection.
- **Library-free WebSocket** — Full WebSocket protocol (handshake, masking, frame parsing) implemented manually — only `WiFi.h` and `ArduinoJson.h` are needed.
- **Serverless deployment** — Everything runs on Google Cloud Run. Scales to zero, costs pennies per day.

---

## Prerequisites

- **Google Cloud Platform** account with billing enabled (free tier is sufficient).
- **Docker Desktop** installed on your development machine.
- **Google Cloud CLI** installed and authenticated (`gcloud auth login`).
- **Arduino IDE** with ESP32 board support installed.
- **ESP32 DevKit board** (e.g., NodeMCU-32S, DOIT ESP32 DEVKIT V1).
- **Android Studio** (optional, only if you want to build/modify the Android app).
- **A Google Sheet** with two tabs: `allowed_users` and `logs` (column layouts below).

---

## Google Cloud Setup

### 1. Create a Project & Enable APIs

```bash
gcloud services enable run.googleapis.com artifactregistry.googleapis.com \
  --project=YOUR_PROJECT_ID
```

### 2. Create a Service Account

Cloud Run will use this account to access Google Sheets.

```bash
gcloud iam service-accounts create cloud-run-sa \
  --display-name="Cloud Run Service Account" \
  --project=YOUR_PROJECT_ID
```

### 3. Grant Permissions

Allow your user account to deploy using this service account:

```bash
gcloud iam service-accounts add-iam-policy-binding \
  cloud-run-sa@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --member=user:YOUR_EMAIL@gmail.com \
  --role=roles/iam.serviceAccountUser \
  --project=YOUR_PROJECT_ID
```

### 4. Share Google Sheets

Create a Google Sheet with **two tabs** and share it with the service account email as **Editor**.

| Tab | Columns |
|---|---|
| `allowed_users` | `email` (one row per authorised user, e.g., `rakeshsrikanth853@gmail.com`) |
| `logs` | `timestamp`, `user_email`, `action`, `node_id`, `status`, `response_time_ms` |

The server reads `allowed_users` to authorise sign-ins and appends to `logs` on every action.

### 5. Set Up OAuth 2.0

1. Go to **APIs & Services → Credentials → Create Credentials → OAuth client ID**.
2. Configure the **OAuth consent screen** (External, add test users as needed).
3. Create a **Web application** client.
   - Add `http://localhost:8080` as an authorised JavaScript origin (local testing).
   - After deployment, add your Cloud Run URL (e.g., `https://*.run.app`).
4. Copy the **Client ID**.
5. Update `GOOGLE_CLIENT_ID` in:
   - `server.js` (line ~15)
   - `public/index.html` (line ~54)
   - Android `app/build.gradle` (`buildConfigField "String", "GOOGLE_CLIENT_ID"`)

> **Android OAuth note:** You must also add your Android app's SHA-1 fingerprint to the same OAuth client in Google Cloud Console under **"Authorised Android applications"**:
> ```bash
> keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore \
>   -alias androiddebugkey -storepass android -keypass android
> ```

---

## Deployment (Cloud Run)

### Build & Push Docker Image

```bash
# Navigate to the backend folder
cd CloudRun_Backend_WebApp

# Build the image
docker build -t esp32-led-backend .

# Tag for Artifact Registry
docker tag esp32-led-backend \
  asia-south1-docker.pkg.dev/YOUR_PROJECT_ID/esp32-led-repo/esp32-led-backend:latest

# Push
docker push asia-south1-docker.pkg.dev/YOUR_PROJECT_ID/esp32-led-repo/esp32-led-backend:latest
```

Create the Artifact Registry repository if needed:

```bash
gcloud artifacts repositories create esp32-led-repo \
  --repository-format=docker --location=asia-south1 --project=YOUR_PROJECT_ID
```

### Deploy to Cloud Run

```bash
gcloud run deploy esp32-led-service \
  --image=asia-south1-docker.pkg.dev/YOUR_PROJECT_ID/esp32-led-repo/esp32-led-backend:latest \
  --region=asia-south1 \
  --allow-unauthenticated \
  --min-instances=1 \
  --set-env-vars=AUTH_TOKEN=rak123,SPREADSHEET_ID=your_google_sheet_id \
  --service-account=cloud-run-sa@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --project=YOUR_PROJECT_ID
```

After deployment, note the **Cloud Run URL** (e.g., `https://esp32-led-service-xxxxx-uc.a.run.app`). You'll need it for the ESP32 sketch and the Android app.

---

## ESP32 Programming

### Hardware

| Component | Details |
|---|---|
| Board | ESP32 DevKit V1 (or any ESP32 board) |
| LED | Built-in LED on GPIO 2 (active high — check your board) |
| Connection | USB cable for power + serial |

### Dependencies

Install via Arduino Library Manager:
- **ArduinoJson** by Benoit Blanchon

No WebSocket library is needed — the sketch implements the WebSocket protocol from scratch.

### Configuration

Open `Iot-project-arduino-code/ws-android-iot-project.ino` and update these constants:

| Variable | Description | Example |
|---|---|---|
| `ssid` | Your Wi-Fi SSID | `"I2ST_WIFI"` |
| `password` | Your Wi-Fi password | `"your_password"` |
| `ws_host` | Cloud Run hostname (w/o `wss://`) | `"esp32-led-service-xxxxx-uc.a.run.app"` |
| `ws_port` | 443 for production, 8080 for local | `443` |
| `USE_TLS` | `true` for Cloud Run, `false` for local | `true` |
| `authToken` | Must match server's `AUTH_TOKEN` env var | `"rak123"` |
| `node_id` | Identifies this ESP32 to the server | `"esp32-1"` |
| `LED_PIN` | GPIO pin for the LED | `2` |

**For local testing:**
- Set `#define USE_TLS false`
- Change `ws_host` to your PC's IP address
- Change `ws_port` to `8080`

### Uploading the Sketch

1. Connect the ESP32 via USB.
2. Select the correct board and port in Arduino IDE (**Tools → Board → ESP32 Dev Module**).
3. Click **Upload**.

Open the **Serial Monitor** (115200 baud) to verify:
- Wi-Fi connection status
- WebSocket upgrade success
- Registration message sent
- Incoming commands received

---

## Android App

The `android-ws/ESP32LedApp/` folder contains a complete native Android application built with Kotlin and Material Design.

### How It Works

| Step | What Happens |
|---|---|
| 1. Launch | App shows Google Sign-In button. Toggle button is disabled. |
| 2. Sign In | User taps "Sign in with Google" → gets ID token from Google |
| 3. Verify | App sends `POST /api/verify` with the ID token to your backend |
| 4. Authorised? | Backend checks `allowed_users` sheet → if OK, enable toggle |
| 5. Toggle LED | User taps button → `POST /api/led {"command":"on"/"off"}` |
| 6. Update UI | Green circle + "LED is ON" or gray circle + "LED is OFF" |

The app communicates **only via HTTP POST** (no WebSocket). The WebSocket is between the backend and ESP32 only.

### Building the APK

**Prerequisite:** Android Studio (or JDK 17+ and Android SDK 34).

From the `ESP32LedApp` directory:

```cmd
.\gradlew.bat assembleDebug
```

The APK will be generated at:
```
ESP32LedApp\app\build\outputs\apk\debug\app-debug.apk
```

Install on a connected device:

```cmd
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Configuration

Two values in `app/build.gradle` must be set before building:

```groovy
buildConfigField "String", "SERVER_URL", "\"https://your-cloud-run-url.com\""
buildConfigField "String", "GOOGLE_CLIENT_ID", "\"YOUR_WEB_CLIENT_ID\""
```

- **SERVER_URL** — Your deployed Cloud Run URL (same one serving the web frontend).
- **GOOGLE_CLIENT_ID** — Same Web Client ID from Google Cloud Console used in the web app.

---

## Local Development & Testing

### 1. Set up Application Default Credentials (ADC)

To let the local server write to Google Sheets:

```bash
gcloud auth application-default login \
  --impersonate-service-account=cloud-run-sa@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --scopes=https://www.googleapis.com/auth/spreadsheets,https://www.googleapis.com/auth/cloud-platform
```

This stores temporary credentials — no key file needed.

### 2. Run the server locally

```bash
cd CloudRun_Backend_WebApp
npm install
set SPREADSHEET_ID=your_sheet_id
set AUTH_TOKEN=rak123
node server.js
```

### 3. Test the frontend

Open `http://localhost:8080` in a browser. Sign in with Google.

### 4. Connect the ESP32 locally

In the ESP32 sketch:
- Set `#define USE_TLS false`
- Set `ws_host` to your PC's local IP (e.g., `192.168.1.100`)
- Set `ws_port` to `8080`

Upload and observe the Serial Monitor for the WebSocket handshake.

---

## How It Works

### The Manual WebSocket Implementation

The ESP32 does **not** use any WebSocket library. Here's what the firmware does:

1. **TCP/TLS connection** — Opens a raw socket to the Cloud Run host on port 443 (or local port 8080).
   - For production: `WiFiClientSecure` with `setInsecure()` (skips certificate validation to save flash).
   - For local: plain `WiFiClient`.

2. **WebSocket upgrade handshake** — Sends an HTTP GET request with standard upgrade headers:
   ```
   GET /?token=rak123 HTTP/1.1
   Host: <cloud-run-url>
   Upgrade: websocket
   Connection: Upgrade
   Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
   Sec-WebSocket-Version: 13
   ```
   The token in the query string authenticates the ESP32 to the server.

3. **Frame construction** — Every outgoing message is wrapped in a WebSocket frame:
   - **Byte 0:** `0x81` (FIN bit + text opcode)
   - **Byte 1:** Payload length with MASK bit (`0x80 | len`)
   - **Bytes 2-5:** 4-byte random mask key
   - **Remaining bytes:** Masked payload (`data[i] ^ maskKey[i % 4]`)
   - Extended length encoding (2 bytes) is supported for messages up to 65535 bytes.

4. **Frame parsing** — Incoming frames are parsed manually:
   - First byte: FIN + opcode (close frame = `0x8` triggers disconnect)
   - Second byte: mask bit + payload length (supports 7-bit, 16-bit extended)
   - Remaining bytes: unmasked payload (server frames are never masked per RFC)

5. **Registration** — After upgrade, the ESP32 sends:
   ```json
   {"type":"register","node_id":"esp32-1"}
   ```
   The server stores this connection for command forwarding.

6. **Keep-alive** — A WebSocket ping is sent every 30 seconds. The server's `ws` library responds with a pong automatically.

### Latency Measurement

When a user clicks the toggle button:
1. Server sends `{"command":"on"}` to ESP32 and records `Date.now()`.
2. ESP32 toggles the LED and replies `{"status":"on"}`.
3. Server receives the status and calculates `latencyMs = Date.now() - pendingCommand.timestamp`.
4. The latency (along with user email, action, and status) is logged to the `logs` sheet.

This gives you real visibility into the round-trip performance of your IoT system.

---

## Troubleshooting

| Symptom | Likely Cause | Solution |
|---|---|---|
| ESP32 never connects (TLS connect failed) | Wrong hostname or network firewall | Verify `ws_host` is exactly the Cloud Run hostname (no `wss://`). Ensure port 443 is not blocked. |
| Server responds with 403 (LED Controller) | User's email not in `allowed_users` sheet | Add the email to the sheet. Ensure the user is a test user on the OAuth consent screen. |
| Logs sheet not updating | Sheet not shared with service account, or `SPREADSHEET_ID` incorrect | Re-share the sheet with `cloud-run-sa@...` as Editor. Double-check the ID in the environment variable. |
| LED logic appears reversed | GPIO active-high vs. active-low | Check your board's LED polarity and adjust `HIGH`/`LOW` in the sketch's command handler. |
| Connection drops after a while | No keep-alive pings | The ESP32 sends a ping every 30 seconds. Check the Serial Monitor for "ping sent" logs. |
| Deployment fails with "Permission denied" | Missing `iam.serviceAccountUser` role | Run the IAM binding command for your email. |
| Android build fails | Missing SDK or build tools | Open the project in Android Studio — it will prompt you to install required SDK components. |
| Android OAuth fails | SHA-1 fingerprint not registered | Run `keytool` (see [OAuth Setup](#5-set-up-oauth-20)) and add the SHA-1 to the OAuth client in Google Cloud Console. |

---

## Flowcharts

### ESP32 LED Controller Logic

![ESP32 LED Controller Logic](A_docs/ESP32%20LED%20Controller%20Logic.png)

### Complete System Interaction (LED Controller)

![Complete System Interaction](A_docs/Complete%20System%20Interaction.png)

---

## License

This project is for educational and internal industrial use. No license is applied.
