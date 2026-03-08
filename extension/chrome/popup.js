const backendUrlInput = document.getElementById('backendUrl');
const instructionInput = document.getElementById('instruction');
const strictSafetyInput = document.getElementById('strictSafety');
const planBtn = document.getElementById('planBtn');
const runBtn = document.getElementById('runBtn');
const result = document.getElementById('result');

const STORAGE_KEYS = {
  backendUrl: 'flowpilot_backend_url',
  strictSafety: 'flowpilot_strict_safety',
  lastPlan: 'flowpilot_last_plan',
  lastPlanUrl: 'flowpilot_last_plan_url',
  lastWorkflowId: 'flowpilot_last_workflow_id'
};

const RISKY_KEYWORDS = ['send', 'delete', 'remove', 'trash', 'pay', 'payment', 'purchase', 'checkout', 'buy', 'transfer'];

function isRiskyStep(step) {
  const action = String(step?.action || '').toLowerCase();
  if (['delete', 'pay', 'purchase', 'transfer', 'checkout', 'submit'].includes(action)) {
    return true;
  }

  const haystack = [step?.selector, step?.text, step?.key, step?.url, step?.targetSelector, step?.reason]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();

  return RISKY_KEYWORDS.some((keyword) => haystack.includes(keyword));
}

function formatRiskySteps(riskySteps) {
  return riskySteps
    .map((step, index) => `${index + 1}. ${step.action} ${step.selector || step.url || step.text || ''}`.trim())
    .join('\n');
}

async function getActiveTab() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  return tabs[0];
}

function renderPlan(data) {
  const lines = [];
  lines.push(`Summary: ${data.summary || 'n/a'}`);
  lines.push(`Confidence: ${Math.round((data.confidence || 0) * 100)}%`);
  if (data.needsConfirmation && data.clarifyingQuestion) {
    lines.push(`Clarification: ${data.clarifyingQuestion}`);
  }
  lines.push('Steps:');
  (data.steps || []).forEach((step, index) => {
    lines.push(`${index + 1}. ${step.action} ${step.selector || step.url || ''} ${step.text || ''}`.trim());
  });
  result.textContent = lines.join('\n');
}

async function loadState() {
  const data = await chrome.storage.local.get([STORAGE_KEYS.backendUrl, STORAGE_KEYS.strictSafety]);
  if (data[STORAGE_KEYS.backendUrl]) {
    backendUrlInput.value = data[STORAGE_KEYS.backendUrl];
  }

  if (typeof data[STORAGE_KEYS.strictSafety] === 'boolean') {
    strictSafetyInput.checked = data[STORAGE_KEYS.strictSafety];
  }
}

strictSafetyInput.addEventListener('change', async () => {
  await chrome.storage.local.set({ [STORAGE_KEYS.strictSafety]: strictSafetyInput.checked });
});

planBtn.addEventListener('click', async () => {
  try {
    const backendUrl = backendUrlInput.value.trim().replace(/\/$/, '');
    const userInstruction = instructionInput.value.trim();

    if (!backendUrl || !userInstruction) {
      result.textContent = 'Backend URL and instruction are required.';
      return;
    }

    await chrome.storage.local.set({ [STORAGE_KEYS.backendUrl]: backendUrl });

    const tab = await getActiveTab();
    const screenshotDataUrl = await chrome.tabs.captureVisibleTab(tab.windowId, { format: 'png' });

    const response = await fetch(`${backendUrl}/api/workflows/plan`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userInstruction,
        screenshotDataUrl,
        currentUrl: tab.url
      })
    });

    const payload = await response.json();
    if (!response.ok) {
      throw new Error(payload.error || 'Planning failed');
    }

    await chrome.storage.local.set({
      [STORAGE_KEYS.lastPlan]: payload.steps || [],
      [STORAGE_KEYS.lastPlanUrl]: tab.url || '',
      [STORAGE_KEYS.lastWorkflowId]: payload.workflowId
    });

    renderPlan(payload);
  } catch (error) {
    result.textContent = `Error: ${error.message}`;
  }
});

runBtn.addEventListener('click', async () => {
  try {
    const data = await chrome.storage.local.get([
      STORAGE_KEYS.lastPlan,
      STORAGE_KEYS.lastPlanUrl,
      STORAGE_KEYS.backendUrl,
      STORAGE_KEYS.lastWorkflowId
    ]);

    const steps = data[STORAGE_KEYS.lastPlan] || [];
    const plannedUrl = data[STORAGE_KEYS.lastPlanUrl] || '';
    const backendUrl = (data[STORAGE_KEYS.backendUrl] || '').replace(/\/$/, '');
    const workflowId = data[STORAGE_KEYS.lastWorkflowId];

    if (!steps.length) {
      result.textContent = 'No planned steps found. Run Capture + Plan first.';
      return;
    }

    if (strictSafetyInput.checked) {
      const riskySteps = steps.filter(isRiskyStep);
      if (riskySteps.length > 0) {
        const ok = window.confirm(
          `Strict safety mode blocked risky actions until confirmation.\n\n${formatRiskySteps(riskySteps)}\n\nProceed with execution?`
        );
        if (!ok) {
          result.textContent = 'Execution cancelled by user due to strict safety mode.';
          return;
        }
      }
    }

    const tab = await getActiveTab();
    if (plannedUrl && tab.url && plannedUrl !== tab.url) {
      const continueRun = window.confirm(
        `This plan was generated on a different page.\nPlanned URL: ${plannedUrl}\nCurrent URL: ${tab.url}\n\nRun anyway?`
      );
      if (!continueRun) {
        result.textContent = 'Execution cancelled. Capture + Plan again on the current page.';
        return;
      }
    }

    const execution = await chrome.runtime.sendMessage({ type: 'FLOWPILOT_RUN_STEPS', tabId: tab.id, steps });
    if (!execution?.ok) {
      result.textContent = `Execution failed: ${execution?.error || 'Unknown error'}`;
      return;
    }

    if (backendUrl && workflowId) {
      await fetch(`${backendUrl}/api/workflows/${workflowId}/execute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ steps, currentUrl: tab.url })
      });
    }

    result.textContent += `\n\nExecution sent. Steps executed: ${execution.executed || steps.length}.`;
  } catch (error) {
    result.textContent = `Error: ${error.message}`;
  }
});

loadState();
