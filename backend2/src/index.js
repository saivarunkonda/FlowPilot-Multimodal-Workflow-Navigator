import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import { workflowRouter } from './routes/workflow.js';

const app = express();
const port = process.env.PORT || 8080;

app.use(
  cors({
    origin: process.env.ALLOWED_ORIGIN || '*'
  })
);
app.use(express.json({ limit: '10mb' }));

app.get('/', (_req, res) => {
  res.json({
    status: 'ok',
    service: 'flowpilot-backend2',
    message: 'FlowPilot backend2 is running.',
    endpoints: {
      health: '/health',
      plan: 'POST /api/workflows/plan',
      execute: 'POST /api/workflows/:workflowId/execute'
    }
  });
});

app.get('/health', (_req, res) => {
  res.json({ status: 'ok', service: 'flowpilot-backend2' });
});

app.use('/api/workflows', workflowRouter);

app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(500).json({
    error: 'Internal server error',
    details: process.env.NODE_ENV === 'development' ? err.message : undefined
  });
});

app.listen(port, () => {
  console.log(`FlowPilot backend2 listening on port ${port}`);
});
