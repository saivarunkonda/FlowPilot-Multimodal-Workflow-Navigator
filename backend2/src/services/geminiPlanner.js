import { GoogleGenerativeAI } from '@google/generative-ai';

const apiKey = process.env.GEMINI_API_KEY;

function normalizeModelName(value) {
  const raw = (value || '').trim();
  if (!raw) {
    return 'gemini-2.5-flash-lite';
  }

  if (raw.startsWith('models/')) {
    return raw.slice('models/'.length);
  }

  const slashIndex = raw.lastIndexOf('/');
  if (slashIndex > -1) {
    return raw.slice(slashIndex + 1);
  }

  return raw;
}

const modelName = normalizeModelName(process.env.GEMINI_MODEL);

function buildPrompt(userInstruction, currentUrl) {
  return `
You are FlowPilot, a visual UI automation planner.
Given a screenshot and user instruction, output STRICT JSON only with shape:
{
  "summary": "short summary",
  "confidence": 0.0,
  "needsConfirmation": true|false,
  "clarifyingQuestion": "optional",
  "steps": [
    {
      "action": "click|type|keypress|wait|dragAndDrop|navigate",
      "selector": "CSS selector when needed",
      "text": "text for type",
      "key": "key for keypress",
      "durationMs": 800,
      "targetSelector": "for dragAndDrop",
      "url": "for navigate",
      "reason": "brief reason"
    }
  ]
}

Constraints:
- Keep steps minimal and deterministic.
- If uncertain about target element, set needsConfirmation=true and ask one clarifying question.
- Never include markdown or explanations outside JSON.

User instruction: ${userInstruction}
Current URL: ${currentUrl || 'unknown'}
`;
}

function normalizePlan(plan) {
  return {
    summary: plan?.summary || 'Generated plan',
    confidence: Number(plan?.confidence ?? 0.5),
    needsConfirmation: Boolean(plan?.needsConfirmation),
    clarifyingQuestion: plan?.clarifyingQuestion || null,
    steps: Array.isArray(plan?.steps) ? plan.steps : []
  };
}

function getSafeErrorMessage(error) {
  const message = error?.message || String(error || 'Unknown Gemini error');
  return message.replace(/\s+/g, ' ').trim().slice(0, 220);
}

export async function generateActionPlan({ userInstruction, screenshotBase64, mimeType, currentUrl }) {
  if (!apiKey) {
    return {
      summary: 'Planner fallback mode: GEMINI_API_KEY is missing.',
      confidence: 0.4,
      needsConfirmation: true,
      clarifyingQuestion: 'Set GEMINI_API_KEY in backend2/.env to enable live planning.',
      steps: []
    };
  }

  try {
    const genAI = new GoogleGenerativeAI(apiKey);
    const model = genAI.getGenerativeModel({
      model: modelName,
      generationConfig: {
        temperature: 0.1,
        maxOutputTokens: 1024
      }
    });

    const result = await model.generateContent([
      { text: buildPrompt(userInstruction, currentUrl) },
      {
        inlineData: {
          data: screenshotBase64,
          mimeType: mimeType || 'image/png'
        }
      }
    ]);

    const raw = result.response?.text?.() || '';
    if (!raw) {
      throw new Error('Gemini returned empty response');
    }

    const sanitized = raw.replace(/^```json\s*|```$/g, '').trim();
    return normalizePlan(JSON.parse(sanitized));
  } catch (error) {
    const reason = getSafeErrorMessage(error);
    return {
      summary: 'Planner fallback mode: Gemini call failed or output was not parseable.',
      confidence: 0.35,
      needsConfirmation: true,
      clarifyingQuestion: `I could not generate a reliable plan right now. Gemini error: ${reason}`,
      steps: []
    };
  }
}
