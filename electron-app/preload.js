import { contextBridge } from 'electron';

contextBridge.exposeInMainWorld('flowpilotDesktop', {
  appVersion: '0.1.0'
});
