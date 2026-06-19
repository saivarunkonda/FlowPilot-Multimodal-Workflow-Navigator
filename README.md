# FlowPilot – Multimodal Workflow Navigator

FlowPilot is a UI Navigator agent that acts as a user’s hands on screen. It observes browser UI with screenshots, interprets elements with Gemini multimodal, and executes actions from natural language instructions.

## Project Structure

- `backend/` Cloud Run orchestration API (Gemini planning + Firestore history)
- `backend2/` No-cloud local API (Gemini API key + local JSON history)
- `extension/chrome/` Chrome extension for screenshot capture, command input, and on-page action execution
- `electron-app/` Electron desktop client with embedded browser automation workspace
- `cloud-functions/ui-action-executor/` Cloud Function to execute web actions (Playwright)
- `terraform/` Infrastructure-as-Code for core GCP resources
- `docs/` Architecture, demo script, checklist, and blog draft

## MVP Workflow

1. User enters an instruction in extension popup.
2. Extension captures visible tab screenshot.
3. Backend sends screenshot + instruction to Gemini on Vertex AI.
4. Gemini returns structured action plan with confidence and optional clarification.
5. Extension executes plan on active tab.
6. Backend persists workflow history in Firestore.

## Prerequisites

- Node.js 20+
- Google Cloud SDK (`gcloud`)
- Terraform 1.7+
- GCP project with billing enabled

### Windows setup for `gcloud`

If PowerShell shows `gcloud is not recognized`, install the SDK and reopen terminal:

```powershell
winget install --id Google.CloudSDK --exact --source winget --accept-source-agreements --accept-package-agreements
```

If PATH still has not refreshed in the current shell, run:

```powershell
$env:Path = "C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin;$env:Path"
gcloud --version
```

## 1) a) Run Backend Locally

```bash
cd backend
npm install
cp .env.example .env
# set GOOGLE_CLOUD_PROJECT and GOOGLE_CLOUD_LOCATION
npm run dev
```

Backend health check:

```bash
curl http://localhost:8080/health
```

## b) Run Backend2 Locally

Use this mode if you want to run FlowPilot without any Google Cloud setup.

```bash
cd backend2
npm install
cp .env.example .env
# set GEMINI_API_KEY
npm run dev
```

Then in the extension popup, set backend URL to `http://localhost:8080`.

## 2) Load Chrome Extension

1. Open `chrome://extensions`
2. Enable **Developer mode**
3. Click **Load unpacked**
4. Select `extension/chrome`
5. In popup, set backend URL (`http://localhost:8080` for local)
6. Keep **Strict safety mode** enabled (default) to require confirmation before risky actions

## 2b) Run Electron Desktop App

Use this desktop mode if you want FlowPilot without Chrome extension.

```bash
cd electron-app
npm install
npm start
```

In the app:

1. Set backend URL (`http://localhost:8080`)
2. Open target URL (for example `https://mail.google.com`)
3. Enter instruction and click **Capture + Plan**
4. Keep **Strict safety mode** enabled (default)
5. Click **Run Plan** and confirm if risky actions are detected

## 3) Deploy Cloud Run Backend

```bash
PROJECT_ID="your-project-id"
REGION="us-central1"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/flowpilot/backend:latest"

cd backend
gcloud auth login
gcloud config set project ${PROJECT_ID}
gcloud services enable run.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com aiplatform.googleapis.com firestore.googleapis.com

gcloud artifacts repositories create flowpilot --repository-format=docker --location=${REGION} --description="FlowPilot images" || true

gcloud builds submit --tag ${IMAGE}

gcloud run deploy flowpilot-backend \
  --image ${IMAGE} \
  --region ${REGION} \
  --allow-unauthenticated \
  --set-env-vars GOOGLE_CLOUD_PROJECT=${PROJECT_ID},GOOGLE_CLOUD_LOCATION=${REGION}
```

If `gcloud services enable ...` fails with `UREQ_PROJECT_BILLING_NOT_FOUND`:

```powershell
gcloud billing projects describe $env:PROJECT_ID
gcloud billing accounts list
```

If `billingEnabled: false` and no billing accounts are listed, create a billing account in Google Cloud Console and make sure your user has access to it. Then link it:

```powershell
gcloud billing projects link $env:PROJECT_ID --billing-account=XXXXXX-XXXXXX-XXXXXX
gcloud billing projects describe $env:PROJECT_ID
```

After billing is linked, rerun the API enable command.

PowerShell equivalents for variables:

```powershell
$env:PROJECT_ID="your-project-id"
$env:REGION="us-central1"
$env:IMAGE="$env:REGION-docker.pkg.dev/$env:PROJECT_ID/flowpilot/backend:latest"
```

## 4) Deploy Cloud Function Executor

```bash
cd cloud-functions/ui-action-executor
npm install

gcloud functions deploy flowpilot-ui-executor \
  --gen2 \
  --runtime=nodejs20 \
  --region=us-central1 \
  --entry-point=executeWorkflow \
  --source=. \
  --trigger-http \
  --allow-unauthenticated \
  --set-env-vars EXECUTOR_API_KEY=replace-with-strong-key
```

Set backend env vars after deploy:

- `ACTION_EXECUTOR_URL`
- `ACTION_EXECUTOR_API_KEY`

## 5) Terraform IaC

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
# fill values in terraform.tfvars
terraform init
terraform plan
terraform apply
```

## API Endpoints

- `GET /health`
- `POST /api/workflows/plan`
  - body: `{ userInstruction, screenshotDataUrl, currentUrl }`
- `POST /api/workflows/:workflowId/execute`
  - body: `{ steps, currentUrl }`

## Submission Assets

- Architecture source: `docs/architecture.mmd`
- Architecture image: `docs/architecture.png`
- Demo script: `docs/demo-script.md`
- Blog draft: `docs/blog-post-draft.md`
- Checklist: `docs/submission-checklist.md`

## Security Notes

- Keep extension and backend restricted to trusted domains for production.
- Add explicit allow-lists and per-action confirmation for sensitive actions.
- Store secrets in Secret Manager for production deployment.
