async function runStepOnTab(tabId, step) {
  const [{ result } = {}] = await chrome.scripting.executeScript({
    target: { tabId },
    func: async (currentStep) => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

      const waitForElement = async (selector, timeoutMs = 8000) => {
        if (!selector) {
          return null;
        }

        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
          const element = document.querySelector(selector);
          if (element) {
            return element;
          }
          await sleep(150);
        }

        return null;
      };

      const clickElement = (element) => {
        if (!element) {
          return false;
        }

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
        if (!element) {
          return { ok: false, error: 'Type failed: target element not found.' };
        }

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

        return { ok: false, error: 'Type failed: target element is not a text control.' };
      };

      const pressKey = (element, keyValue) => {
        const target = element || document.activeElement || document.body;
        ['keydown', 'keypress', 'keyup'].forEach((eventName) => {
          target.dispatchEvent(
            new KeyboardEvent(eventName, {
              key: keyValue,
              bubbles: true,
              cancelable: true
            })
          );
        });
      };

      switch (currentStep.action) {
        case 'navigate':
          if (currentStep.url) {
            window.location.href = currentStep.url;
          }
          return { ok: true };
        case 'click': {
          const element = await waitForElement(currentStep.selector);
          if (!element && currentStep.selector) {
            return { ok: false, error: `Click failed: selector not found (${currentStep.selector}).` };
          }
          clickElement(element);
          return { ok: true };
        }
        case 'type':
          return typeIntoElement(await waitForElement(currentStep.selector), currentStep.text);
        case 'keypress': {
          const element = await waitForElement(currentStep.selector, 1500);
          pressKey(element, currentStep.key || 'Enter');
          return { ok: true };
        }
        case 'wait':
          await sleep(Number(currentStep.durationMs || 800));
          return { ok: true };
        case 'dragAndDrop': {
          const source = await waitForElement(currentStep.selector);
          const target = await waitForElement(currentStep.targetSelector);
          if (source && target) {
            const dragStart = new DragEvent('dragstart', { bubbles: true });
            const drop = new DragEvent('drop', { bubbles: true });
            source.dispatchEvent(dragStart);
            target.dispatchEvent(drop);
            return { ok: true };
          }
          return { ok: false, error: 'Drag-and-drop failed: source or target not found.' };
        }
        default:
          return { ok: true };
      }
    },
    args: [step]
  });

  if (result && result.ok === false) {
    throw new Error(result.error || `Step failed for action: ${step.action}`);
  }
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.type !== 'FLOWPILOT_RUN_STEPS') {
    return false;
  }

  (async () => {
    try {
      const { tabId, steps } = message;
      for (let index = 0; index < steps.length; index += 1) {
        const step = steps[index];
        await runStepOnTab(tabId, step);
      }
      sendResponse({ ok: true, executed: steps.length });
    } catch (error) {
      sendResponse({ ok: false, error: error.message });
    }
  })();

  return true;
});
