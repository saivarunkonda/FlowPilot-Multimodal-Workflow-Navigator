# FlowPilot Android App

This module is a basic Android UI Navigator client for FlowPilot.

## What it does

- Requests screen capture permission (MediaProjection)
- Captures a screenshot of the current display
- Sends screenshot + instruction to FlowPilot backend (`/api/workflows/plan`)
- Displays generated action steps
- Executes step actions through Accessibility Service (tap/type/back/home/wait)

## Setup

1. Start backend (`backend2`) on your machine.
2. Open `FlowPilot/` in Android Studio.
3. Build and run on emulator or physical Android device.

## Backend URL notes

- Android emulator to local machine backend: `http://10.0.2.2:8080`
- Physical device: use your machine LAN IP, for example `http://192.168.1.10:8080`

## First-time permissions

1. Tap **Grant Capture** and approve screen capture.
2. Tap **Accessibility** and enable **FlowPilot Navigator** service.
3. Enter instruction and tap **Capture + Plan**.
4. Keep **Strict safety mode** enabled (default).
5. Tap **Execute** to run returned steps and confirm risky actions if prompted.

## Floating icon workflow (no 5-second app countdown)

When accessibility is enabled, a floating **FP** icon appears over other apps.

1. Open FlowPilot once and set **Backend URL** + **Instruction**.
2. Tap **Grant Capture** in FlowPilot (required for each capture run).
3. Go to the target app screen.
4. Tap floating **FP** icon and choose:
	- **Capture + Plan**: capture target screen and generate steps.
	- **Capture + Execute**: capture, plan, and execute immediately (if not blocked by strict safety).
	- **Execute Last**: run the most recent captured plan.
	- **Open FlowPilot**: return to app UI.

If capture fails with token/projection messages, tap **Grant Capture** again in FlowPilot and retry.

## Important

- For reliable mobile execution, instruction should ask for coordinate-based actions.
- Add safety phrasing such as "ask before send/delete" in your instruction text.
