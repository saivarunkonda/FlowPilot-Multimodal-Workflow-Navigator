# Backend2 (No GCP)

`backend2` replicates the `backend` API contract without Cloud Run, Vertex AI, Firestore, or Cloud Functions.

## What it uses

- Gemini Developer API key (`GEMINI_API_KEY`) on free tier
- Local JSON file storage (`data/workflows.json`)
- Optional external executor URL (or dry-run mode)

## Endpoints

- `GET /health`
- `POST /api/workflows/plan`
- `POST /api/workflows/:workflowId/execute`

## Setup

```bash
cd backend2
npm install
cp .env.example .env
```

Set in `.env`:

- `GEMINI_API_KEY=...`
- optional `GEMINI_MODEL=gemini-2.0-flash`
- optional `ACTION_EXECUTOR_URL=...`

## Run

```bash
npm run dev
```

Health check:

```bash
curl http://localhost:8080/health
```

## Extension usage

In Chrome extension popup, set **Backend URL** to:

- `http://localhost:8080`

No `gcloud` commands are needed for local `backend2` mode.
