# FlowPilot Electron Desktop

Desktop client for FlowPilot using Electron with an embedded browser (`webview`).

## Features

- Open target web app URLs (Gmail, Trello, etc.) inside desktop app
- Capture visible webview screenshot and send it to backend for planning
- Execute returned steps directly in webview
- Works with `backend2` (no `gcloud` required)

## Run

1. Start backend (`backend2`):

```bash
cd ../backend2
npm run dev
```

2. Start desktop app:

```bash
cd ../electron-app
npm install
npm start
```

## Usage

1. Set **Backend URL** (`http://localhost:8080`)
2. Set **Target URL** (`https://mail.google.com`) and click **Open**
3. Enter instruction and click **Capture + Plan**
4. Keep **Strict safety mode** enabled (default)
5. Click **Run Plan** to execute generated actions

## Notes

- The app does not auto-send actions unless the plan includes send/click steps.
- Strict safety mode blocks risky actions (`send/delete/pay/purchase/transfer`) until you confirm.
- Add explicit "ask for confirmation before sending/deleting" in your instructions for safe usage.
