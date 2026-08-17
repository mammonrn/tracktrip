import { WebSocketServer } from 'ws';

// TODO: implement real-time position broadcast/auth logic in a later change.
export function attachWebSocketServer(httpServer) {
  const wss = new WebSocketServer({ server: httpServer });
  return wss;
}

export default attachWebSocketServer;
