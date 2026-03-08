import express from 'express';
import { generateActionPlan } from '../services/geminiPlanner.js';
import { executePlan } from '../services/executorClient.js';
import { saveWorkflowRun, updateWorkflowRun } from '../services/firestoreStore.js';

export const workflowRouter = express.Router();

workflowRouter.post('/plan', async (req, res, next) => {
  try {
    const { userInstruction, screenshotDataUrl, currentUrl } = req.body;

    if (!userInstruction || !screenshotDataUrl) {
      return res.status(400).json({
        error: 'userInstruction and screenshotDataUrl are required'
      });
    }

    const [header, base64] = screenshotDataUrl.split(',');
    const mimeMatch = header?.match(/data:(.*);base64/);
    const mimeType = mimeMatch?.[1] || 'image/png';

    const plan = await generateActionPlan({
      userInstruction,
      screenshotBase64: base64,
      mimeType,
      currentUrl
    });

    const run = await saveWorkflowRun({
      userInstruction,
      currentUrl,
      plan,
      status: 'planned'
    });

    return res.json({ workflowId: run.id, ...plan });
  } catch (error) {
    return next(error);
  }
});

workflowRouter.post('/:workflowId/execute', async (req, res, next) => {
  try {
    const { workflowId } = req.params;
    const { steps, currentUrl } = req.body;

    if (!Array.isArray(steps)) {
      return res.status(400).json({ error: 'steps must be an array' });
    }

    const result = await executePlan({ workflowId, currentUrl, steps });
    const saved = await updateWorkflowRun(workflowId, {
      status: 'executed',
      executionResult: result
    });

    return res.json({ workflowId: saved.id, result });
  } catch (error) {
    return next(error);
  }
});
