const actionExecutorUrl = process.env.ACTION_EXECUTOR_URL;
const actionExecutorApiKey = process.env.ACTION_EXECUTOR_API_KEY;

export async function executePlan({ workflowId, currentUrl, steps }) {
  if (!actionExecutorUrl) {
    return {
      mode: 'dry-run',
      message: 'ACTION_EXECUTOR_URL not configured; returning simulated execution.',
      workflowId,
      currentUrl,
      executedSteps: steps.length
    };
  }

  const response = await fetch(actionExecutorUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(actionExecutorApiKey ? { 'x-api-key': actionExecutorApiKey } : {})
    },
    body: JSON.stringify({ workflowId, startUrl: currentUrl, steps })
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Executor failed (${response.status}): ${body}`);
  }

  return response.json();
}
