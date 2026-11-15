const { subtle } = globalThis.crypto;
const canon = (v) => {
  if (Array.isArray(v)) return '[' + v.map(canon).join(',') + ']';
  if (v && typeof v === 'object') return '{' + Object.keys(v).sort().map(k => JSON.stringify(k) + ':' + canon(v[k])).join(',') + '}';
  return JSON.stringify(v);
};
const kp = await subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
const spki = Buffer.from(await subtle.exportKey('spki', kp.publicKey)).toString('base64');
const event = { cursor: 42, type: 'result', task_id: 'tk_01', agent_id: 'kdot', conn_id: 'cn_01', client_name: 'ChatGPT', at: '2026-11-15T18:00:00.000Z',
  payload: { summary: 'Café "ok"\n— done ✓ 😀', claims: [{ text: 'x', confidence: 0.85, sources: [{ url: 'https://a.example/?q=1&r=2', title: 'A' }] }], status: 'done', n: 1e21, small: 1e-7, z: null, t: true } };
const c = canon(event);
const sig = Buffer.from(await subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, kp.privateKey, new TextEncoder().encode(c))).toString('base64url');
const out = { spki, canonical: c, event: { ...event, relay_sig: sig } };
// Wire form: different key order + whitespace, like a server would send.
const wire = JSON.stringify({ relay_sig: sig, payload: event.payload, at: event.at, client_name: event.client_name, conn_id: event.conn_id, agent_id: event.agent_id, task_id: event.task_id, type: event.type, cursor: event.cursor }, null, 2);
console.log(JSON.stringify({ spki, canonical: c, sig, wire }));
