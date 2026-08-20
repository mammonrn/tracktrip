import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { WebSocket } from 'ws';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { attachWebSocketServer } from '../src/ws/index.js';
import { PositionHub, noopHub } from '../src/ws/hub.js';
import { MessageBudget, MAX_SUBSCRIPTIONS } from '../src/ws/messageBudget.js';
import { parseClientMessage, CLOSE_RATE_LIMITED, CLOSE_UNAUTHORIZED } from '../src/ws/protocol.js';

const JWT_SECRET = 'test-secret';

// ------------------------------------------------------- the grammar, alone

test('a frame that is not a message this server has is refused by name', (t) => {
  assert.equal(parseClientMessage('not json').error, 'expected JSON');
  assert.equal(parseClientMessage('[]').error, 'expected a JSON object');
  assert.equal(parseClientMessage('{}').error, 'type is required');
  assert.equal(parseClientMessage('{"type":"dance"}').error, 'unknown message type: dance');
  assert.equal(parseClientMessage(null).error, 'expected a text frame');
});

test('a subscribe without a usable trip id is refused', (t) => {
  assert.ok(parseClientMessage('{"type":"subscribe"}').error);
  assert.ok(parseClientMessage('{"type":"subscribe","trip_id":0}').error);
  assert.ok(parseClientMessage('{"type":"subscribe","trip_id":"seven"}').error);
  assert.ok(parseClientMessage('{"type":"subscribe","trip_id":1.5}').error);

  assert.deepEqual(parseClientMessage('{"type":"subscribe","trip_id":7}').value, {
    type: 'subscribe',
    tripId: 7,
  });
});

test('an enormous frame is refused before it is parsed', (t) => {
  // A socket is an open door; the size check has to come first or a client
  // can make the server parse a megabyte of JSON per frame.
  const huge = JSON.stringify({ type: 'subscribe', trip_id: 1, pad: 'x'.repeat(5000) });

  assert.equal(parseClientMessage(huge).error, 'message too large');
});

test('reporting a position over the socket is refused, and says where to go', (t) => {
  // Deliberate: one write path for a fix, so there is one place that can
  // credit a kilometre twice. See src/ws/protocol.js.
  const refusal = parseClientMessage('{"type":"position","lat":18.79,"lng":98.98}').error;

  assert.match(refusal, /POST \/trips\/:id\/positions/);
});

// --------------------------------------------------------------- the budget

test('a budget runs out and refills over time', (t) => {
  let clock = 0;
  const budget = new MessageBudget({ perMinute: 60, now: () => clock });

  for (let i = 0; i < 60; i += 1) {
    assert.equal(budget.spend(), true, `message ${i} should be allowed`);
  }
  assert.equal(budget.spend(), false, 'the 61st in the same instant is not');

  // Half a minute later, half the allowance is back — not none of it, which
  // is what a fixed window would give.
  clock += 30_000;
  assert.equal(budget.spend(), true);
});

test('the budget cannot be built up beyond its size', (t) => {
  // An hour of silence must not buy an hour's worth of shouting.
  let clock = 0;
  const budget = new MessageBudget({ perMinute: 10, now: () => clock });
  clock += 3_600_000;

  for (let i = 0; i < 10; i += 1) {
    assert.equal(budget.spend(), true);
  }
  assert.equal(budget.spend(), false);
});

// ------------------------------------------------------------------ the hub

test('the hub delivers to everyone watching a trip and nobody else', (t) => {
  const hub = new PositionHub();
  const watchingOne = [];
  const watchingTwo = [];

  hub.subscribe(1, (p) => watchingOne.push(p));
  hub.subscribe(1, (p) => watchingOne.push(p));
  hub.subscribe(2, (p) => watchingTwo.push(p));

  const delivered = hub.publishPosition(1, { lat: 18.79 });

  assert.equal(delivered, 2);
  assert.equal(watchingOne.length, 2);
  assert.equal(watchingTwo.length, 0);
});

test('unsubscribing stops delivery and leaves nothing behind', (t) => {
  // A server up for a month must not carry one empty entry per trip anybody
  // has ever opened.
  const hub = new PositionHub();
  const stop = hub.subscribe(1, () => {});
  assert.equal(hub.subscriberCount(1), 1);

  stop();

  assert.equal(hub.subscriberCount(1), 0);
  assert.equal(hub.subscribers.has(1), false);
});

test('one broken listener cannot fail the rider who reported', (t) => {
  // These run inside the HTTP request that stored the position. A socket in a
  // bad state must not turn a successful report into a 500.
  const hub = new PositionHub();
  hub.subscribe(1, () => {
    throw new Error('this socket is having a bad day');
  });
  const good = [];
  hub.subscribe(1, (p) => good.push(p));

  const delivered = hub.publishPosition(1, { lat: 1 });

  assert.equal(delivered, 1);
  assert.equal(good.length, 1);
});

test('publishing to a trip nobody is watching is free and harmless', (t) => {
  assert.equal(new PositionHub().publishPosition(99, {}), 0);
  assert.equal(noopHub.publishPosition(99, {}), 0);
});

// --------------------------------------------------------- the live server

/**
 * A real HTTP server with the socket attached, on a port the OS picks.
 *
 * The socket is worth testing against the genuine article: everything
 * interesting about it — the upgrade, the auth on that upgrade, the close
 * codes — lives in the parts a fake would have to reimplement.
 */
async function liveServer() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare('INSERT INTO users (google_sub, email) VALUES (?, ?)');
  const riderId = Number(insertUser.run('sub-rider', 'rider@gmail.com').lastInsertRowid);
  const watcherId = Number(insertUser.run('sub-watcher', 'watcher@gmail.com').lastInsertRowid);
  const strangerId = Number(insertUser.run('sub-stranger', 'stranger@gmail.com').lastInsertRowid);

  const config = { jwtSecret: JWT_SECRET, googleClientIds: ['test'], superuserEmails: [] };
  const hub = new PositionHub();
  const app = createApp({
    db,
    config,
    hub,
    verifyGoogleIdToken: async () => {
      throw new Error('unused');
    },
  });

  const server = http.createServer(app);
  attachWebSocketServer(server, { db, config, hub });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();

  const tokenFor = (id) => signAccessToken(id, JWT_SECRET);
  const request = supertest(app);
  const as = (id) => ({
    get: (path) => request.get(path).set('Authorization', `Bearer ${tokenFor(id)}`),
    post: (path) => request.post(path).set('Authorization', `Bearer ${tokenFor(id)}`),
  });

  const created = await as(riderId).post('/trips').send({ name: 'Socket ride' });
  const tripId = created.body.id;
  db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')").run(
    tripId,
    watcherId
  );

  const connect = (id) =>
    new Frames(
      new WebSocket(`ws://127.0.0.1:${port}/ws`, {
        headers: id === null ? {} : { Authorization: `Bearer ${tokenFor(id)}` },
      })
    );

  return {
    db,
    hub,
    tripId,
    riderId,
    watcherId,
    strangerId,
    as,
    connect,
    close: () =>
      new Promise((resolve) => {
        server.closeAllConnections?.();
        server.close(resolve);
      }),
  };
}

/**
 * A socket whose frames are collected from the moment it exists.
 *
 * Not `socket.once('message', …)` per expectation, which is the obvious shape
 * and is racy: the server's first frame is written the instant the connection
 * is accepted, and it can arrive in the same TCP segment as the handshake
 * response — so `ws` emits 'open' and 'message' inside one turn of the event
 * loop, and a listener attached after awaiting 'open' has already missed it.
 * Queueing from construction removes the race rather than sleeping around it.
 */
class Frames {
  constructor(socket) {
    this.socket = socket;
    this.queue = [];
    this.waiting = [];
    this.closeCode = null;
    this.closeWaiting = [];

    socket.on('message', (data) => {
      const frame = JSON.parse(String(data));
      const waiter = this.waiting.shift();
      if (waiter) waiter.resolve(frame);
      else this.queue.push(frame);
    });

    socket.on('close', (code) => {
      this.closeCode = code;
      this.closeWaiting.forEach((resolve) => resolve(code));
      this.closeWaiting = [];
      this.waiting.forEach((waiter) => waiter.reject(new Error(`closed with ${code}`)));
      this.waiting = [];
    });
  }

  opened() {
    if (this.socket.readyState === this.socket.OPEN) return Promise.resolve();
    return new Promise((resolve, reject) => {
      this.socket.once('open', resolve);
      this.socket.once('error', reject);
    });
  }

  next() {
    const queued = this.queue.shift();
    if (queued) return Promise.resolve(queued);
    return new Promise((resolve, reject) => this.waiting.push({ resolve, reject }));
  }

  /** Everything received so far, without waiting for more. */
  drain() {
    const frames = this.queue;
    this.queue = [];
    return frames;
  }

  send(message) {
    this.socket.send(JSON.stringify(message));
  }

  closed() {
    if (this.closeCode !== null) return Promise.resolve(this.closeCode);
    return new Promise((resolve) => this.closeWaiting.push(resolve));
  }

  close() {
    this.socket.close();
  }
}

/** Waits for the server's greeting, subscribes, and returns the answer. */
async function subscribed(client, tripId) {
  await client.opened();
  await client.next(); // ready
  client.send({ type: 'subscribe', trip_id: tripId });
  return client.next();
}

test('a position posted over HTTP arrives on a subscribed socket', async (t) => {
  // The whole feature in one test: the write path is unchanged, and the
  // watcher hears about it without asking.
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const socket = ctx.connect(ctx.watcherId);
  const ack = await subscribed(socket, ctx.tripId);
  assert.deepEqual(ack, { type: 'subscribed', trip_id: ctx.tripId });

  const incoming = socket.next();
  const posted = await ctx
    .as(ctx.riderId)
    .post(`/trips/${ctx.tripId}/positions`)
    .send({ lat: 18.79, lng: 98.98, timestamp: new Date().toISOString() });
  assert.equal(posted.status, 200);

  const event = await incoming;
  assert.equal(event.type, 'position');
  assert.equal(event.trip_id, ctx.tripId);
  assert.equal(event.position.user_id, ctx.riderId);
  assert.equal(event.position.lat, 18.79);

  socket.close();
});

test('what the socket delivers is what the server stored', async (t) => {
  // A stale fix is refused by the position row, and the socket must announce
  // the row rather than the submission — otherwise a backlog being flushed
  // would drag everybody's map backwards while the database stayed right.
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const now = new Date();
  const older = new Date(now.getTime() - 60_000);

  await ctx
    .as(ctx.riderId)
    .post(`/trips/${ctx.tripId}/positions`)
    .send({ lat: 18.79, lng: 98.98, timestamp: now.toISOString() });

  const socket = ctx.connect(ctx.watcherId);
  await subscribed(socket, ctx.tripId);

  const incoming = socket.next();
  await ctx
    .as(ctx.riderId)
    .post(`/trips/${ctx.tripId}/positions`)
    .send({ lat: 0.1, lng: 0.1, timestamp: older.toISOString() });

  const event = await incoming;
  assert.equal(event.position.lat, 18.79, 'the stored fix, not the stale one');

  socket.close();
});

test('a socket with no token is closed, and told why', async (t) => {
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const socket = ctx.connect(null);
  const code = await socket.closed();

  assert.equal(code, CLOSE_UNAUTHORIZED);
});

test('a stranger may not subscribe to a trip they are not on', async (t) => {
  // Same rule as the HTTP routes, asked through the same function.
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const socket = ctx.connect(ctx.strangerId);
  const answer = await subscribed(socket, ctx.tripId);

  assert.equal(answer.type, 'error');
  assert.equal(answer.error, 'not a member of this trip');
  assert.equal(ctx.hub.subscriberCount(ctx.tripId), 0);

  socket.close();
});

test('a refused subscription receives nothing afterwards', async (t) => {
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const socket = ctx.connect(ctx.strangerId);
  await subscribed(socket, ctx.tripId);

  await ctx
    .as(ctx.riderId)
    .post(`/trips/${ctx.tripId}/positions`)
    .send({ lat: 18.79, lng: 98.98, timestamp: new Date().toISOString() });
  await new Promise((resolve) => setTimeout(resolve, 50));

  assert.deepEqual(socket.drain(), []);
  socket.close();
});

test('unsubscribing stops the stream without closing the socket', async (t) => {
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const socket = ctx.connect(ctx.watcherId);
  await subscribed(socket, ctx.tripId);

  socket.send({ type: 'unsubscribe', trip_id: ctx.tripId });
  const ack = await socket.next();
  assert.deepEqual(ack, { type: 'unsubscribed', trip_id: ctx.tripId });
  assert.equal(ctx.hub.subscriberCount(ctx.tripId), 0);

  // Still usable: a ping is answered.
  socket.send({ type: 'ping' });
  assert.deepEqual(await socket.next(), { type: 'pong' });

  socket.close();
});

test('closing a socket releases every subscription it held', async (t) => {
  // Otherwise a phone that rides into a tunnel leaves an entry behind for
  // every trip it ever watched.
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const socket = ctx.connect(ctx.watcherId);
  await subscribed(socket, ctx.tripId);
  assert.equal(ctx.hub.subscriberCount(ctx.tripId), 1);

  socket.close();
  await new Promise((resolve) => setTimeout(resolve, 80));

  assert.equal(ctx.hub.subscriberCount(ctx.tripId), 0);
});

test('subscribing twice is not an error and does not double the delivery', async (t) => {
  // A client reconnecting resubscribes to what it had; it should not have to
  // remember whether a particular subscription survived.
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const socket = ctx.connect(ctx.watcherId);
  await subscribed(socket, ctx.tripId);
  socket.send({ type: 'subscribe', trip_id: ctx.tripId });
  await socket.next();

  assert.equal(ctx.hub.subscriberCount(ctx.tripId), 1);
  socket.close();
});

test('a client that will not stop talking is closed', async (t) => {
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const socket = ctx.connect(ctx.watcherId);
  await socket.opened();
  await socket.next(); // ready

  for (let i = 0; i < 200; i += 1) {
    socket.send({ type: 'ping' });
  }

  const code = await socket.closed();
  assert.equal(code, CLOSE_RATE_LIMITED);
});

test('one socket cannot hold more trips than the cap', async (t) => {
  const ctx = await liveServer();
  t.after(() => ctx.close());

  // Enough trips to go past the cap, all of them the rider's own.
  //
  // Ended as they are created — and the setup's own trip with them — because a
  // rider may only be on one active trip at a time (migration 0013). It makes
  // no difference to what is under test: subscribing asks for membership, not
  // for a running trip, so a socket watching last year's rides hits the same
  // cap.
  await ctx.as(ctx.riderId).post(`/trips/${ctx.tripId}/end`).send();

  const tripIds = [];
  for (let i = 0; i < MAX_SUBSCRIPTIONS + 1; i += 1) {
    const created = await ctx.as(ctx.riderId).post('/trips').send({ name: `Trip ${i}` });
    assert.equal(created.status, 201, JSON.stringify(created.body));
    tripIds.push(created.body.id);
    await ctx.as(ctx.riderId).post(`/trips/${created.body.id}/end`).send();
  }

  const socket = ctx.connect(ctx.riderId);
  await socket.opened();
  await socket.next(); // ready

  const answers = [];
  for (const tripId of tripIds) {
    socket.send({ type: 'subscribe', trip_id: tripId });
    answers.push(await socket.next());
  }

  assert.equal(answers.filter((a) => a.type === 'subscribed').length, MAX_SUBSCRIPTIONS);
  assert.equal(answers.at(-1).type, 'error');
  assert.equal(answers.at(-1).error, 'too many subscriptions');

  socket.close();
});

test('a nonsense frame is answered, not fatal', async (t) => {
  // A client with a bug should learn what it got wrong and stay connected;
  // only abuse closes the door.
  const ctx = await liveServer();
  t.after(() => ctx.close());

  const socket = ctx.connect(ctx.watcherId);
  await socket.opened();
  await socket.next(); // ready

  socket.socket.send('this is not json');
  assert.deepEqual(await socket.next(), { type: 'error', error: 'expected JSON' });

  socket.send({ type: 'ping' });
  assert.deepEqual(await socket.next(), { type: 'pong' });

  socket.close();
});

test('the HTTP API works exactly the same with no socket attached', async (t) => {
  // The backward-compatibility claim, stated as a test: an app built without
  // a hub stores and reads positions as it always did.
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);
  const riderId = Number(
    db.prepare("INSERT INTO users (google_sub, email) VALUES ('s', 'r@gmail.com')").run()
      .lastInsertRowid
  );

  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test'], superuserEmails: [] },
    verifyGoogleIdToken: async () => {
      throw new Error('unused');
    },
  });
  const request = supertest(app);
  const auth = `Bearer ${signAccessToken(riderId, JWT_SECRET)}`;

  const created = await request.post('/trips').set('Authorization', auth).send({ name: 'No socket' });
  const posted = await request
    .post(`/trips/${created.body.id}/positions`)
    .set('Authorization', auth)
    .send({ lat: 18.79, lng: 98.98, timestamp: new Date().toISOString() });

  assert.equal(posted.status, 200);
  assert.equal(posted.body.lat, 18.79);
});
