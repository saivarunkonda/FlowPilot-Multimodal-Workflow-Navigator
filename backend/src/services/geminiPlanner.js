import { VertexAI } from '@google-cloud/vertexai';

const project = process.env.GOOGLE_CLOUD_PROJECT;
const location = process.env.GOOGLE_CLOUD_LOCATION || 'us-central1';
const modelName = process.env.GEMINI_MODEL || 'gemini-2.0-flash-001';

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
      "targetSelector": "for dragAndDrop",
      "url": "for navigate",
      "reason": "brief reason"
    }
  ]
}

Constraints:
- Keep steps minimal and deterministic.
- If uncertain about target element, set needsConfirmation=true and ask one clarifying question.
- Never include markdown.

User instruction: ${userInstruction}
Current URL: ${currentUrl || 'unknown'}
`;
}

export async function generateActionPlan({ userInstruction, screenshotBase64, mimeType, currentUrl }) {
  if (!project) {
    return {
      summary: 'Planner running in fallback mode (missing GOOGLE_CLOUD_PROJECT).',
      confidence: 0.55,
      needsConfirmation: true,
      clarifyingQuestion: 'Please set GOOGLE_CLOUD_PROJECT to enable Gemini planning.',
      steps: []
    };
  }

  const vertexAI = new VertexAI({ project, location });
  const model = vertexAI.getGenerativeModel({ model: modelName });

  const request = {
    contents: [
      {
        role: 'user',
        parts: [
          { text: buildPrompt(userInstruction, currentUrl) },
          {
            inlineData: {
              mimeType: mimeType || 'image/png',
              data: screenshotBase64
            }
          }
        ]
      }
    ],
    generationConfig: {
      temperature: 0.1,
      maxOutputTokens: 1024
    }
  };

  const response = await model.generateContent(request);
  const text = response.response.candidates?.[0]?.content?.parts?.[0]?.text?.trim();

  if (!text) {
    throw new Error('Gemini returned empty response');
  }

  let plan;
  try {
    const sanitized = text.replace(/^```json\s*|```$/g, '').trim();
    plan = JSON.parse(sanitized);
  } catch (_error) {
    plan = {
      summary: 'Unable to parse planner output safely.',
      confidence: 0.2,
      needsConfirmation: true,
      clarifyingQuestion: 'I could not reliably parse the UI plan. Should I try again?',
      steps: []
    };
  }

  return {
    summary: plan.summary || 'Generated plan',
    confidence: Number(plan.confidence ?? 0.5),
    needsConfirmation: Boolean(plan.needsConfirmation),
    clarifyingQuestion: plan.clarifyingQuestion || null,
    steps: Array.isArray(plan.steps) ? plan.steps : []
  };
}
