const backendUrlInput = document.getElementById('backendUrl');
const targetUrlInput = document.getElementById('targetUrl');
const instructionInput = document.getElementById('instruction');
const strictSafetyInput = document.getElementById('strictSafety');
const openBtn = document.getElementById('openBtn');
const planBtn = document.getElementById('planBtn');
const runBtn = document.getElementById('runBtn');
const resultEl = document.getElementById('result');
const webview = document.getElementById('webview');

const STORAGE_KEYS = {
  backendUrl: 'flowpilot_desktop_backend_url',
  targetUrl: 'flowpilot_desktop_target_url',
  strictSafety: 'flowpilot_desktop_strict_safety',
  lastPlan: 'flowpilot_desktop_last_plan',
  lastPlanUrl: 'flowpilot_desktop_last_plan_url',
  lastWorkflowId: 'flowpilot_desktop_last_workflow_id'
};

const RISKY_KEYWORDS = ['send', 'delete', 'remove', 'trash', 'pay', 'payment', 'purchase', 'checkout', 'buy', 'transfer'];

let lastPlan = [];
let lastWorkflowId = null;
let lastPlanUrl = null;

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

function renderPlan(payload) {
  const lines = [];
  lines.push(`Summary: ${payload.summary || 'n/a'}`);
  lines.push(`Confidence: ${Math.round((payload.confidence || 0) * 100)}%`);
  if (payload.needsConfirmation && payload.clarifyingQuestion) {
    lines.push(`Clarification: ${payload.clarifyingQuestion}`);
  }
  lines.push('Steps:');
  (payload.steps || []).forEach((step, index) => {
    lines.push(`${index + 1}. ${step.action} ${step.selector || step.url || ''} ${step.text || ''}`.trim());
  });

  resultEl.textContent = lines.join('\n');
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function runStep(step) {
  if (step.action === 'navigate' && step.url) {
    webview.loadURL(step.url);
    await sleep(1200);
    return;
  }

  if (step.action === 'wait') {
    await sleep(Number(step.durationMs || 800));
    return;
  }

  const code = `
    (async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const step = ${JSON.stringify(step)};

      const waitForElement = async (selector, timeoutMs = 8000) => {
        if (!selector) return null;
        const started = Date.now();
        while (Date.now() - started < timeoutMs) {
          const node = document.querySelector(selector);
          if (node) return node;
          await sleep(150);
        }
        return null;
      };

      const clickElement = (element) => {
        if (!element) return false;
        element.scrollIntoView({ block: 'center', inline: 'center' });
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach((eventName) => {
          element.dispatchEvent(new MouseEvent(eventName, { bubbles: true, cancelable: true, view: window }));
        });
        if (typeof element.click === 'function') {
          element.click();
        }
        return true;
      };

      const setNativeInputValue = (element, value) => {
        if (element instanceof HTMLTextAreaElement) {
          const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
          if (setter) {
            setter.call(element, value);
            return;
          }
        }
        if (element instanceof HTMLInputElement) {
          const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
          if (setter) {
            setter.call(element, value);
            return;
          }
        }
        element.value = value;
      };

      const typeIntoElement = (element, text) => {
        if (!element) return { ok: false, error: 'selector not found for type' };
        const value = text || '';
        element.focus();

        if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) {
          setNativeInputValue(element, value);
          element.dispatchEvent(new Event('input', { bubbles: true }));
          element.dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true };
        }

        const editable =
          element.isContentEditable ||
          element.getAttribute('contenteditable') === 'true' ||
          element.getAttribute('role') === 'textbox';

        if (editable) {
          try {
            document.execCommand('selectAll', false);
            document.execCommand('insertText', false, value);
          } catch (_error) {
          }

          if ((element.textContent || '') !== value) {
            element.textContent = value;
          }

          element.dispatchEvent(new InputEvent('input', { bubbles: true, data: value, inputType: 'insertText' }));
          element.dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true };
        }

        return { ok: false, error: 'target is not text-editable' };
      };

      const pressKey = (element, keyValue) => {
        const target = element || document.activeElement || document.body;
        ['keydown', 'keypress', 'keyup'].forEach((eventName) => {
          target.dispatchEvent(new KeyboardEvent(eventName, { key: keyValue, bubbles: true, cancelable: true }));
        });
      };

      switch (step.action) {
        case 'click': {
          const element = await waitForElement(step.selector);
          if (!element && step.selector) return { ok: false, error: 'selector not found for click' };
          clickElement(element);
          return { ok: true };
        }
        case 'type':
          return typeIntoElement(await waitForElement(step.selector), step.text);
        case 'keypress': {
          const element = await waitForElement(step.selector, 1500);
          pressKey(element, step.key || 'Enter');
          return { ok: true };
        }
        case 'dragAndDrop': {
          const element = await waitForElement(step.selector);
          const target = await waitForElement(step.targetSelector);
          if (!element || !target) return { ok: false, error: 'source or target not found for dragAndDrop' };
          const dragStart = new DragEvent('dragstart', { bubbles: true });
          const drop = new DragEvent('drop', { bubbles: true });
          element.dispatchEvent(dragStart);
          target.dispatchEvent(drop);
          return { ok: true };
        }
        default:
          return { ok: true };
      }
    })();
  `;

  const stepResult = await webview.executeJavaScript(code, true);
  if (!stepResult?.ok) {
    throw new Error(stepResult?.error || `Step failed: ${step.action}`);
  }

  await sleep(350);
}

openBtn.addEventListener('click', async () => {
  const targetUrl = targetUrlInput.value.trim();
  if (!targetUrl) {
    return;
  }

  localStorage.setItem(STORAGE_KEYS.targetUrl, targetUrl);
  webview.loadURL(targetUrl);
});

strictSafetyInput.addEventListener('change', () => {
  localStorage.setItem(STORAGE_KEYS.strictSafety, JSON.stringify(strictSafetyInput.checked));
});

planBtn.addEventListener('click', async () => {
  try {
    const backendUrl = backendUrlInput.value.trim().replace(/\/$/, '');
    const userInstruction = instructionInput.value.trim();

    if (!backendUrl || !userInstruction) {
      resultEl.textContent = 'Backend URL and instruction are required.';
      return;
    }

    localStorage.setItem(STORAGE_KEYS.backendUrl, backendUrl);

    const image = await webview.capturePage();
    const screenshotDataUrl = image.toDataURL();
    const currentUrl = webview.getURL() || targetUrlInput.value.trim();

    const response = await fetch(`${backendUrl}/api/workflows/plan`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userInstruction, screenshotDataUrl, currentUrl })
    });

    const payload = await response.json();
    if (!response.ok) {
      throw new Error(payload.error || 'Planning failed');
    }

    lastPlan = payload.steps || [];
    lastWorkflowId = payload.workflowId;

    localStorage.setItem(STORAGE_KEYS.lastPlan, JSON.stringify(lastPlan));
    lastPlanUrl = currentUrl;
    localStorage.setItem(STORAGE_KEYS.lastPlanUrl, currentUrl);
    localStorage.setItem(STORAGE_KEYS.lastWorkflowId, String(lastWorkflowId));

    renderPlan(payload);
  } catch (error) {
    resultEl.textContent = `Error: ${error.message}`;
  }
});

runBtn.addEventListener('click', async () => {
  try {
    const backendUrl = backendUrlInput.value.trim().replace(/\/$/, '');
    if (!lastPlan.length) {
      const storedPlan = localStorage.getItem(STORAGE_KEYS.lastPlan);
      if (storedPlan) {
        lastPlan = JSON.parse(storedPlan);
      }
      lastPlanUrl = localStorage.getItem(STORAGE_KEYS.lastPlanUrl);
      lastWorkflowId = localStorage.getItem(STORAGE_KEYS.lastWorkflowId);
    }

    if (!lastPlan.length) {
      resultEl.textContent = 'No planned steps found. Run Capture + Plan first.';
      return;
    }

    const currentUrl = webview.getURL() || targetUrlInput.value.trim();
    if (lastPlanUrl && currentUrl && lastPlanUrl !== currentUrl) {
      const continueRun = window.confirm(
        `This plan was generated on a different page.\nPlanned URL: ${lastPlanUrl}\nCurrent URL: ${currentUrl}\n\nRun anyway?`
      );
      if (!continueRun) {
        resultEl.textContent = 'Execution cancelled. Capture + Plan again on the current page.';
        return;
      }
    }

    if (strictSafetyInput.checked) {
      const riskySteps = lastPlan.filter(isRiskyStep);
      if (riskySteps.length > 0) {
        const ok = window.confirm(
          `Strict safety mode blocked risky actions until confirmation.\n\n${formatRiskySteps(riskySteps)}\n\nProceed with execution?`
        );
        if (!ok) {
          resultEl.textContent = 'Execution cancelled by user due to strict safety mode.';
          return;
        }
      }
    }

    for (const step of lastPlan) {
      await runStep(step);
    }

    if (backendUrl && lastWorkflowId) {
      await fetch(`${backendUrl}/api/workflows/${lastWorkflowId}/execute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ steps: lastPlan, currentUrl })
      });
    }

    resultEl.textContent += '\n\nExecution sent.';
  } catch (error) {
    resultEl.textContent = `Error: ${error.message}`;
  }
});

(function bootstrap() {
  const savedBackendUrl = localStorage.getItem(STORAGE_KEYS.backendUrl);
  const savedTargetUrl = localStorage.getItem(STORAGE_KEYS.targetUrl);
  const savedStrictSafety = localStorage.getItem(STORAGE_KEYS.strictSafety);
  const savedPlanUrl = localStorage.getItem(STORAGE_KEYS.lastPlanUrl);

  if (savedBackendUrl) {
    backendUrlInput.value = savedBackendUrl;
  }

  if (savedTargetUrl) {
    targetUrlInput.value = savedTargetUrl;
    webview.loadURL(savedTargetUrl);
  }

  if (savedStrictSafety !== null) {
    strictSafetyInput.checked = JSON.parse(savedStrictSafety);
  }

  if (savedPlanUrl) {
    lastPlanUrl = savedPlanUrl;
  }
})();
