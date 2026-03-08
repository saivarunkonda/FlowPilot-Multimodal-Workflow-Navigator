import { chromium } from 'playwright';

function assertApiKey(req) {
  const expected = process.env.EXECUTOR_API_KEY;
  if (!expected) {
    return true;
  }
  return req.get('x-api-key') === expected;
}

async function runStep(page, step) {
  switch (step.action) {
    case 'navigate':
      if (step.url) {
        await page.goto(step.url, { waitUntil: 'domcontentloaded' });
      }
      break;
    case 'click':
      await page.click(step.selector, { timeout: 6000 });
      break;
    case 'type':
      await page.fill(step.selector, step.text || '');
      break;
    case 'keypress':
      await page.press(step.selector, step.key || 'Enter');
      break;
    case 'wait':
      await page.waitForTimeout(Number(step.durationMs || 800));
      break;
    case 'dragAndDrop':
      await page.dragAndDrop(step.selector, step.targetSelector, { timeout: 8000 });
      break;
    default:
      break;
  }
}

export async function executeWorkflow(req, res) {
  if (!assertApiKey(req)) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }

  if (req.method !== 'POST') {
    res.status(405).json({ error: 'Method not allowed' });
    return;
  }

  const { workflowId, startUrl, steps = [] } = req.body || {};
  if (!startUrl || !Array.isArray(steps)) {
    res.status(400).json({ error: 'startUrl and steps[] are required' });
    return;
  }

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();
  const logs = [];

  try {
    await page.goto(startUrl, { waitUntil: 'domcontentloaded' });

    for (const [index, step] of steps.entries()) {
      await runStep(page, step);
      logs.push({ index, action: step.action, status: 'ok' });
    }

    res.json({
      workflowId,
      status: 'success',
      executedSteps: steps.length,
      finalUrl: page.url(),
      logs
    });
  } catch (error) {
    res.status(500).json({
      workflowId,
      status: 'failed',
      message: error.message,
      logs
    });
  } finally {
    await context.close();
    await browser.close();
  }
}
