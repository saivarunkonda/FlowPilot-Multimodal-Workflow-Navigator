import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

const workflowsFile = path.resolve(process.cwd(), process.env.WORKFLOWS_FILE || 'data/workflows.json');

async function ensureStoreFile() {
  const dir = path.dirname(workflowsFile);
  await fs.mkdir(dir, { recursive: true });

  try {
    await fs.access(workflowsFile);
  } catch {
    await fs.writeFile(workflowsFile, '[]', 'utf8');
  }
}

async function readAll() {
  await ensureStoreFile();
  const content = await fs.readFile(workflowsFile, 'utf8');
  try {
    const parsed = JSON.parse(content);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

async function writeAll(items) {
  await ensureStoreFile();
  await fs.writeFile(workflowsFile, `${JSON.stringify(items, null, 2)}\n`, 'utf8');
}

export async function saveWorkflowRun(workflowRun) {
  const now = new Date().toISOString();
  const payload = {
    id: randomUUID(),
    ...workflowRun,
    createdAt: now,
    updatedAt: now
  };

  const all = await readAll();
  all.push(payload);
  await writeAll(all);
  return payload;
}

export async function updateWorkflowRun(id, patch) {
  const all = await readAll();
  const index = all.findIndex((item) => item.id === id);

  if (index < 0) {
    throw new Error(`Workflow run not found: ${id}`);
  }

  all[index] = {
    ...all[index],
    ...patch,
    updatedAt: new Date().toISOString()
  };

  await writeAll(all);
  return all[index];
}
