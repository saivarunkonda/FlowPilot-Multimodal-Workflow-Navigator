import { Firestore } from '@google-cloud/firestore';

const firestore = new Firestore();
const collectionName = process.env.WORKFLOWS_COLLECTION || 'workflows';

export async function saveWorkflowRun(workflowRun) {
  const now = new Date().toISOString();
  const payload = {
    ...workflowRun,
    createdAt: now,
    updatedAt: now
  };

  const docRef = await firestore.collection(collectionName).add(payload);
  return { id: docRef.id, ...payload };
}

export async function updateWorkflowRun(id, patch) {
  const payload = {
    ...patch,
    updatedAt: new Date().toISOString()
  };

  await firestore.collection(collectionName).doc(id).set(payload, { merge: true });
  const snapshot = await firestore.collection(collectionName).doc(id).get();
  return { id: snapshot.id, ...snapshot.data() };
}
