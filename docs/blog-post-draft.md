# Building FlowPilot with Gemini Multimodal

FlowPilot is a multimodal workflow navigator that treats natural language as high-level intent and translates it into visual UI actions.

## Why we built it
Traditional automation relies on APIs or brittle selectors. FlowPilot combines screenshot understanding with Gemini multimodal planning so users can say what they want, not how to script it.

## Architecture
- Chrome extension captures screenshots + user instructions.
- Cloud Run backend sends multimodal context to Gemini on Vertex AI.
- Gemini returns an executable action plan with confidence + clarification prompts.
- Cloud Functions executes workflow steps.
- Firestore stores workflow history for observability and replay.

## What worked well
- Confidence scoring + clarification step prevented unsafe actions.
- Cloud-native split made each layer independently deployable.
- Terraform made infrastructure reproducible.

## Next improvements
- Live screen stream mode (not just snapshot).
- Stronger policy guardrails before sensitive actions.
- Human-in-the-loop approval queue for low-confidence plans.

#GeminiLiveAgentChallenge
