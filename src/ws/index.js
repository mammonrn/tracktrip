import { WebSocketServer } from 'ws';
import { verifyAccessToken } from '../auth/jwt.js';
import { readableTrip } from '../auth/tripAccess.js';
import { MessageBudget, MAX_SUBSCRIPTIONS } from './messageBudget.js';
import {
  CLOSE_RATE_LIMITED,
  CLOSE_UNAUTHORIZED,
  parseClientMessage,
  serverMessage,
} from './protocol.js';

/**
 * How often the server checks that a socket is still there.
 *
 * A phone that rides into a tunnel does not close its connection — the TCP
 * socket simply stops, and both ends can sit holding it open for many minutes.
 * A ping every half-minute, with the connection dropped when the previous one
 * was never answered, means a dead socket costs at most a minute of a
 * subscription table entry, and the phone finds out it needs to reconnect
 * from the close rather than from silence.
 */
const HEARTBEAT_MS = 30_000;

/**
 * The token, from wherever the client could put it.
 *
 * The header is the right place and is what the Android client uses — OkHttp
 * can set headers on the upgrade request. The query parameter is accepted as
 * well because browsers cannot: `new WebSocket(url)` takes no headers, and a
 * web client is the obvious next thing to want. It is second, and worth
 * knowing that a token in a URL reaches access logs — which is why the header
 * is what the app sends.
 */
function tokenFrom(request) {
  const header = request.headers?.authorization || '';
  const [scheme, value] = header.split(' ');
  if (scheme === 'Bearer' && value) {
    return value;
  }

  try {
    const url = new URL(request.url, 'http://localhost');
    return url.searchParams.get('token');
  } catch {
    return null;
  }
}

/**
 * Live position delivery.
 *
 * ## What this is, and what it deliberately is not
 *
 * It is a **delivery shortcut**. Fixes are written by `POST
 * /trips/:id/positions` exactly as before, and read by `GET
 * /trips/:id/positions` exactly as before. This socket carries a copy of each
 * accepted fix to whoever is watching that trip, so a rider sees a friend move
 * the moment the server hears about it rather than on the next poll.
 *
 * It is **not** the source of truth and nothing depends on it. A client whose
 * socket dies mid-ride keeps polling and keeps working; it just goes back to
 * finding out within a poll instead of within a moment. That is the whole
 * backward-compatibility story, and it is a property of the design rather than
 * a fallback bolted on: the socket adds nothing to the state, so losing it
 * cannot lose any.
 *
 * It also does **not** accept positions. See protocol.js for why.
 *
 * ## Abuse
 *
 * Every connection carries a [MessageBudget] and a cap of [MAX_SUBSCRIPTIONS]
 * trips. A client that exceeds the budget is closed rather than answered:
 * replying "slow down" to a client in a loop is another message for it to
 * ignore.
 */
export function attachWebSocketServer(httpServer, { db, config, hub }) {
  // `noServer: false` — ws handles the upgrade for us, and there is only one
  // socket path on this server. Verified per connection below rather than in
  // `verifyClient`, which cannot report *why* it refused.
  const wss = new WebSocketServer({ server: httpServer, path: '/ws' });

  wss.on('connection', (socket, request) => {
    const token = tokenFrom(request);
    let user = null;
    if (token) {
      try {
        const payload = verifyAccessToken(token, config.jwtSecret);
        user = db.prepare('SELECT * FROM users WHERE id = ?').get(payload.sub);
      } catch {
        user = null;
      }
    }

    if (!user) {
      socket.close(CLOSE_UNAUTHORIZED, 'unauthorized');
      return;
    }

    const budget = new MessageBudget();
    /** trip id -> the function that stops that subscription. */
    const subscriptions = new Map();
    let alive = true;

    const send = (message) => {
      if (socket.readyState === socket.OPEN) {
        socket.send(JSON.stringify(message));
      }
    };

    send(serverMessage.ready(user.id));

    socket.on('pong', () => {
      alive = true;
    });

    socket.on('message', (data, isBinary) => {
      if (!budget.spend()) {
        socket.close(CLOSE_RATE_LIMITED, 'too many messages');
        return;
      }

      const parsed = parseClientMessage(isBinary ? null : String(data));
      if (parsed.error) {
        send(serverMessage.error(parsed.error));
        return;
      }
      const message = parsed.value;

      if (message.type === 'ping') {
        send(serverMessage.pong());
        return;
      }

      if (message.type === 'unsubscribe') {
        subscriptions.get(message.tripId)?.();
        subscriptions.delete(message.tripId);
        send(serverMessage.unsubscribed(message.tripId));
        return;
      }

      // subscribe
      if (subscriptions.has(message.tripId)) {
        // Idempotent on purpose: a client reconnecting after a dropped
        // connection resubscribes to what it had, and it should not have to
        // remember whether this one survived.
        send(serverMessage.subscribed(message.tripId));
        return;
      }

      if (subscriptions.size >= MAX_SUBSCRIPTIONS) {
        send(serverMessage.error('too many subscriptions'));
        return;
      }

      // The same rule the HTTP routes use, asked through the same function —
      // membership, or the super-user role. Re-read per subscribe rather than
      // cached: a socket can outlive somebody's membership, and the cheap
      // read is worth not having to think about that.
      const access = readableTrip(db, user, message.tripId);
      if (!access) {
        send(serverMessage.error('not a member of this trip'));
        return;
      }

      const stop = hub.subscribe(message.tripId, (position) => {
        send(serverMessage.position(message.tripId, position));
      });
      subscriptions.set(message.tripId, stop);
      send(serverMessage.subscribed(message.tripId));
    });

    socket.on('close', () => {
      for (const stop of subscriptions.values()) {
        stop();
      }
      subscriptions.clear();
    });

    // A socket whose errors are unhandled takes the process down with it.
    socket.on('error', () => {
      socket.terminate();
    });

    socket.heartbeat = setInterval(() => {
      if (!alive) {
        socket.terminate();
        return;
      }
      alive = false;
      socket.ping();
    }, HEARTBEAT_MS);
    // Node keeps the event loop alive for a pending timer; this one must not
    // be a reason the process refuses to exit.
    socket.heartbeat.unref?.();

    socket.on('close', () => clearInterval(socket.heartbeat));
  });

  return wss;
}

export default attachWebSocketServer;
