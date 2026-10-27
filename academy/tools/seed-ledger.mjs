#!/usr/bin/env node
/**
 * seed-ledger.mjs — populate a live VegaDūta tenant with "The Ledger"
 * newspaper demo data for Academy video capture.
 *
 * Idempotent: every entity is looked up by name/content first and skipped
 * when it already exists. Safe to re-run.
 *
 * Env:
 *   ACADEMY_PASSWORD  (required) local-auth password
 *   ACADEMY_USER      (default owner@vegaduta.local)
 *   WEB_BASE          (default http://localhost:3000) — login endpoint
 *   API_BASE          (default http://localhost:8080) — core API
 *   SEED_TURNS        (default 5) real model turns to run, 0 to skip
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB_BASE = process.env.WEB_BASE || 'http://localhost:3000';
const API_BASE = process.env.API_BASE || 'http://localhost:8080';
const ACADEMY_USER = process.env.ACADEMY_USER || 'owner@vegaduta.local';
const ACADEMY_PASSWORD = process.env.ACADEMY_PASSWORD;
const SEED_TURNS = Number.parseInt(process.env.SEED_TURNS ?? '5', 10);
const FIXTURE_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'fixtures', 'ledger');

if (!ACADEMY_PASSWORD) {
  console.error('ACADEMY_PASSWORD env var is required (no secrets in the repo).');
  process.exit(2);
}

const results = []; // {status, kind, name, detail}
let hardFailures = 0;

function record(status, kind, name, detail = '') {
  results.push({ status, kind, name, detail });
  const tag = status.padEnd(7);
  console.log(`  ${tag} ${kind}: ${name}${detail ? ` — ${detail}` : ''}`);
  if (status === 'FAILED') hardFailures += 1;
}

let token = null;

async function req(method, url, { body, headers = {}, raw = false, timeoutMs = 30000 } = {}) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method,
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body !== undefined && !(body instanceof FormData)
          ? { 'Content-Type': 'application/json' }
          : {}),
        ...headers,
      },
      body: body instanceof FormData ? body : body !== undefined ? JSON.stringify(body) : undefined,
      signal: ac.signal,
    });
    if (raw) return res;
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* non-JSON (SSE etc.) */ }
    return { status: res.status, json, text };
  } finally {
    clearTimeout(t);
  }
}

const api = (method, path, opts) => req(method, `${API_BASE}${path}`, opts);

function brief(jsonOrText, max = 160) {
  const s = typeof jsonOrText === 'string' ? jsonOrText : JSON.stringify(jsonOrText);
  return (s || '').slice(0, max);
}

// ---------------------------------------------------------------- login

async function login() {
  const r = await req('POST', `${WEB_BASE}/api/local-auth/login`, {
    body: { identifier: ACADEMY_USER, password: ACADEMY_PASSWORD },
  });
  if (r.status !== 200 || !r.json?.accessToken) {
    throw new Error(`login failed (${r.status}): ${brief(r.text)}`);
  }
  token = r.json.accessToken;
  console.log(`Logged in as ${ACADEMY_USER}`);
}

// ---------------------------------------------------------------- helpers

const norm = (s) => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();

// ---------------------------------------------------------------- 1. knowledge

async function ensureCollection() {
  const list = await api('GET', '/api/knowledge/collections');
  const existing = (list.json || []).find((c) => norm(c.name) === norm('Ledger Style Guide'));
  if (existing) {
    record('SKIPPED', 'collection', 'Ledger Style Guide', existing.id);
    return existing.id;
  }
  const r = await api('POST', '/api/knowledge/collections', {
    body: {
      name: 'Ledger Style Guide',
      description: 'House style rules, source archive and past editions for The Ledger.',
    },
  });
  if (r.status === 201 || r.status === 200) {
    record('CREATED', 'collection', 'Ledger Style Guide', r.json.id);
    return r.json.id;
  }
  record('FAILED', 'collection', 'Ledger Style Guide', `${r.status} ${brief(r.text)}`);
  return null;
}

async function seedDocuments(collectionId) {
  const docs = await api('GET', `/api/knowledge/collections/${collectionId}/documents`);
  const have = new Set((docs.json || []).map((d) => d.filename));
  for (const file of readdirSync(FIXTURE_DIR).filter((f) => f.endsWith('.md'))) {
    const path = join(FIXTURE_DIR, file);
    const text = readFileSync(path, 'utf8');
    if (have.has(file)) {
      record('SKIPPED', 'document', file, 'already in collection');
      continue;
    }
    const fd = new FormData();
    fd.append('file', new Blob([text], { type: 'text/markdown' }), file);
    fd.append('collectionId', collectionId);
    const up = await api('POST', '/api/knowledge/upload', { body: fd, timeoutMs: 120000 });
    if (up.status === 200 || up.status === 201) {
      record('CREATED', 'document', file, `uploaded (${up.json?.chunkCount ?? '?'} chunks)`);
      continue;
    }
    // Fallback: plain-text ingest (no document row, same knowledge chunks).
    const ing = await api('POST', '/api/knowledge/ingest', {
      body: { source: file, text, collectionId },
      timeoutMs: 120000,
    });
    if (ing.status === 200 || ing.status === 201) {
      record('CREATED', 'document', file, `ingested via /ingest (upload said ${up.status})`);
    } else {
      record('FAILED', 'document', file, `upload ${up.status} ${brief(up.text)}; ingest ${ing.status} ${brief(ing.text)}`);
    }
  }
}

// ---------------------------------------------------------------- 2. agents

const AGENTS = [
  {
    name: 'Senior Reporter',
    systemPrompt:
      'You are the Senior Reporter of The Ledger, a daily AI newspaper for Bangalore. ' +
      'Write tight news copy: the figure first, then context, one wry aside at most. ' +
      'Cite a named source for every claim; never write "sources say". ' +
      'If two independent confirmations are missing, mark the line [UNVERIFIED] and stop.',
  },
  {
    name: 'Fact-Checker',
    systemPrompt:
      'You are the Fact-Checker of The Ledger, a daily AI newspaper. ' +
      'Verify every claim in a draft against the newsroom knowledge base (style guide, ' +
      'source archive, past editions). Reply with VERDICT: PASS or HOLD, then a numbered ' +
      'list of claims with their evidence. HOLD anything without a cited source — ' +
      'never soften an unverifiable claim into approval.',
  },
];

async function ensureAgents(anchor) {
  const out = {};
  for (const spec of AGENTS) {
    const existing = anchor.all.find((a) => norm(a.name) === norm(spec.name));
    if (existing) {
      record('SKIPPED', 'agent', spec.name, existing.id);
      out[spec.name] = existing;
      continue;
    }
    const r = await api('POST', '/api/agents', {
      body: {
        name: spec.name,
        systemPrompt: spec.systemPrompt,
        providerName: anchor.providerName,
        model: anchor.model,
        knowledgeBaseEnabled: true,
      },
    });
    if (r.status === 201 || r.status === 200) {
      record('CREATED', 'agent', spec.name, r.json.id);
      out[spec.name] = r.json;
    } else {
      record('FAILED', 'agent', spec.name, `${r.status} ${brief(r.text)}`);
    }
  }
  return out;
}

// ---------------------------------------------------------------- 3. memory

const SHARED_FACTS = [
  'The Ledger serves Bangalore readers; datelines use neighbourhood names (Indiranagar, Whitefield), never PIN codes.',
  'House tone is precise plus wry: short sentences, numbers first, at most one wry aside per page.',
  'Embargo: The Ledger never publishes before 06:00 IST; the front page locks at 05:30 IST for fact-check sign-off.',
  'The editor persona answers as a working newsroom editor — lead with what is known, flag what is unverified.',
];

const BLOCKS = {
  'project:ledger':
    'The Ledger: daily AI newspaper for Bangalore. Desks: City, Markets, Saturday Opinion. ' +
    'Staff agents: Editor-in-Chief, Senior Reporter, Fact-Checker. ' +
    'Embargo 06:00 IST. Corrections run top of next edition.',
};

async function seedMemory() {
  const ov = await api('GET', '/api/memory');
  if (ov.status !== 200) {
    record('FAILED', 'memory', 'GET /api/memory', `${ov.status} ${brief(ov.text)}`);
    return;
  }
  const existingFacts = new Set((ov.json.facts || []).map((f) => norm(f.content)));
  for (const content of SHARED_FACTS) {
    if (existingFacts.has(norm(content))) {
      record('SKIPPED', 'memory fact', content.slice(0, 60) + '…');
      continue;
    }
    const r = await api('POST', '/api/memory/facts', { body: { content, scope: 'shared' } });
    if (r.status === 201 || r.status === 200) {
      record('CREATED', 'memory fact', content.slice(0, 60) + '…');
    } else {
      record('FAILED', 'memory fact', content.slice(0, 40), `${r.status} ${brief(r.text)}`);
    }
  }
  const existingBlocks = new Map((ov.json.blocks || []).map((b) => [b.label, b.content]));
  for (const [label, content] of Object.entries(BLOCKS)) {
    if (norm(existingBlocks.get(label)) === norm(content)) {
      record('SKIPPED', 'memory block', label);
      continue;
    }
    const r = await api('PUT', `/api/memory/blocks/${label}`, { body: { content } });
    if (r.status === 200) {
      record(existingBlocks.has(label) ? 'UPDATED' : 'CREATED', 'memory block', label);
    } else {
      record('FAILED', 'memory block', label, `${r.status} ${brief(r.text)}`);
    }
  }
}

// ---------------------------------------------------------------- 4. scheduled task

async function ensureScheduledTask(editorId) {
  const list = await api('GET', '/api/scheduled-tasks');
  const existing = (list.json || []).find((t) => norm(t.name) === norm('Morning front-page draft'));
  if (existing) {
    record('SKIPPED', 'scheduled task', existing.name, existing.id);
    return;
  }
  const r = await api('POST', '/api/scheduled-tasks', {
    body: {
      agentId: editorId,
      name: 'Morning front-page draft',
      prompt:
        'Draft today\'s front page for The Ledger: pick the top City and Markets stories ' +
        'from the knowledge base, write a 40-word lede for each, and list every claim ' +
        'that still needs Fact-Checker sign-off. Respect the 06:00 IST embargo.',
      frequency: 'daily',
      time: '05:30',
      timezone: 'Asia/Kolkata',
      delivery: 'in_app',
    },
  });
  if (r.status === 201 || r.status === 200) {
    record('CREATED', 'scheduled task', 'Morning front-page draft', `next run ${r.json.nextRunAt ?? '?'}`);
  } else {
    record('FAILED', 'scheduled task', 'Morning front-page draft', `${r.status} ${brief(r.text)}`);
  }
}

// ---------------------------------------------------------------- 5. guardrail

async function ensureGuardrailRule() {
  const spec = {
    name: 'No guaranteed-returns claims',
    description:
      'Blocks copy promising guaranteed or sure-fire financial returns - The Ledger ' +
      'never prints certainty about markets.',
    pattern: '(?i)(guaranteed|sure[- ]?fire|risk[- ]?free)\\s+(returns?|profits?|gains?)',
    action: 'BLOCK',
    enabled: true,
  };
  const list = await api('GET', '/api/guardrails/rules');
  if (list.status !== 200) {
    record('FAILED', 'guardrail rule', spec.name, `GET ${list.status} ${brief(list.text)}`);
    return;
  }
  if ((list.json || []).some((r) => norm(r.name) === norm(spec.name))) {
    record('SKIPPED', 'guardrail rule', spec.name);
    return;
  }
  const r = await api('POST', '/api/guardrails/rules', { body: spec });
  if (r.status === 201 || r.status === 200) {
    record('CREATED', 'guardrail rule', spec.name, r.json.id);
  } else if (r.status === 403) {
    record('SKIPPED', 'guardrail rule', spec.name, 'plan-gated (custom rules need PREMIUM+)');
  } else {
    record('FAILED', 'guardrail rule', spec.name, `${r.status} ${brief(r.text)}`);
  }
}

// ---------------------------------------------------------------- 6. contacts

const CONTACTS = [
  { channel: 'WHATSAPP', identifier: '+919845012345', displayName: 'Meera Krishnan (subscriber)' },
  { channel: 'WHATSAPP', identifier: '+919845067890', displayName: 'Arjun Rao (subscriber)' },
  { channel: 'EMAIL', identifier: 'subscriptions@theledger.example', displayName: 'Ledger subscriptions desk' },
];

async function ensureContacts(agentId) {
  const list = await api('GET', '/api/channels/contacts');
  if (list.status !== 200) {
    record('FAILED', 'contact', 'GET /api/channels/contacts', `${list.status} ${brief(list.text)}`);
    return;
  }
  const have = new Set((list.json || []).map((c) => `${c.channel}|${norm(c.identifier)}`));
  for (const c of CONTACTS) {
    if (have.has(`${c.channel}|${norm(c.identifier)}`)) {
      record('SKIPPED', 'contact', `${c.channel} ${c.identifier}`);
      continue;
    }
    const r = await api('POST', '/api/channels/contacts', {
      body: { ...c, agentId },
    });
    if (r.status === 200 || r.status === 201) {
      record('CREATED', 'contact', `${c.channel} ${c.identifier}`, c.displayName);
    } else {
      record('FAILED', 'contact', `${c.channel} ${c.identifier}`, `${r.status} ${brief(r.text)}`);
    }
  }
}

// ---------------------------------------------------------------- 7. real model turns

const TURNS = [
  { agent: 'Editor-in-Chief', message: 'Write a 40-word lede about metro ridership hitting a record 8.2 lakh trips yesterday.' },
  { agent: 'Senior Reporter', message: 'Write a 40-word City desk brief on BESCOM announcing planned feeder outages in Whitefield this weekend. Cite the source.' },
  { agent: 'Fact-Checker', message: 'Check this claim against the knowledge base: "The Ledger publishes at 05:00 IST." Verdict and evidence.' },
  { agent: 'Senior Reporter', message: 'Give me one wry headline for a story about IMD predicting the season\'s first heavy rain over Bangalore.' },
  { agent: 'Editor-in-Chief', message: 'Draft the correction line for tomorrow\'s edition: we wrote metro ridership was 8.2 lakh, the real figure was 8.4 lakh.' },
];

async function modelTurn(agent, message) {
  // Preferred surface: OpenAI-compatible /v1/chat/completions (model = agent name).
  const r = await api('POST', '/v1/chat/completions', {
    body: { model: agent.name, messages: [{ role: 'user', content: message }] },
    timeoutMs: 180000,
  });
  if (r.status === 404) return { unsupported: true };
  if (r.status === 200) {
    const content = r.json?.choices?.[0]?.message?.content || '';
    return { content };
  }
  return { error: `${r.status} ${brief(r.text)}` };
}

async function chatTurn(agentId, message) {
  // Fallback: the console chat SSE endpoint. Same AgentChatService under it,
  // so the turn still lands in usage/turns/insights.
  const r = await api('POST', `/api/agents/${agentId}/chat`, {
    body: { message },
    headers: { Accept: 'text/event-stream' },
    timeoutMs: 180000,
  });
  if (r.status !== 200) return { error: `${r.status} ${brief(r.text)}` };
  const chunks = (r.text || '')
    .split('\n')
    .filter((l) => l.startsWith('data:'))
    .map((l) => l.slice(5))
    .filter((c) => !c.includes('@@AGENT_CHAT_STREAM_ERROR'));
  return { content: chunks.join('') };
}

async function seedTurns(agentsByName) {
  if (SEED_TURNS <= 0) {
    record('SKIPPED', 'model turns', 'all', 'SEED_TURNS=0');
    return;
  }
  let useOpenAiCompat = true;
  let done = 0;
  for (const turn of TURNS.slice(0, SEED_TURNS)) {
    const agent = agentsByName[turn.agent];
    if (!agent) {
      record('SKIPPED', 'model turn', turn.agent, 'agent not present');
      continue;
    }
    let out;
    if (useOpenAiCompat) {
      out = await modelTurn(agent, turn.message);
      if (out.unsupported) {
        console.log('  NOTE    /v1/chat/completions is disabled (app.openai-compat.enabled!=true); using /api/agents/{id}/chat SSE instead.');
        useOpenAiCompat = false;
        out = await chatTurn(agent.id, turn.message);
      }
    } else {
      out = await chatTurn(agent.id, turn.message);
    }
    if (out.error) {
      record('FAILED', 'model turn', turn.agent, out.error);
    } else {
      done += 1;
      record('CREATED', 'model turn', turn.agent, `"${(out.content || '').replace(/\s+/g, ' ').trim().slice(0, 80)}…"`);
    }
  }
  return done;
}

// ---------------------------------------------------------------- verify + summary

async function verify(collectionId) {
  const [agents, collections, memory, tasks, contacts, rules] = await Promise.all([
    api('GET', '/api/agents'),
    api('GET', '/api/knowledge/collections'),
    api('GET', '/api/memory'),
    api('GET', '/api/scheduled-tasks'),
    api('GET', '/api/channels/contacts'),
    api('GET', '/api/guardrails/rules'),
  ]);
  const docs = collectionId
    ? await api('GET', `/api/knowledge/collections/${collectionId}/documents`)
    : { json: [] };
  const ledgerCollection = (collections.json || []).find((c) => norm(c.name) === norm('Ledger Style Guide'));
  console.log('\n=== Verified live state ===');
  console.log(`  agents:            ${(agents.json || []).length} (${(agents.json || []).map((a) => a.name).join(', ')})`);
  console.log(`  collections:       ${(collections.json || []).length} (Ledger Style Guide: ${ledgerCollection ? 'present' : 'MISSING'})`);
  console.log(`  ledger documents:  ${(docs.json || []).length} files`);
  console.log(`  memory facts:      ${(memory.json?.facts || []).length} + ${(memory.json?.blocks || []).length} blocks`);
  console.log(`  scheduled tasks:   ${(tasks.json || []).length}`);
  console.log(`  contacts:          ${(contacts.json || []).length}`);
  console.log(`  guardrail rules:   ${(rules.json || []).length}`);
  return { agents: (agents.json || []).length, docs: (docs.json || []).length };
}

async function main() {
  console.log(`Seeding The Ledger into ${API_BASE} (login via ${WEB_BASE})\n`);
  await login();

  const agentsRes = await api('GET', '/api/agents');
  const all = agentsRes.json || [];
  const editor = all.find((a) => norm(a.name) === norm('Editor-in-Chief')) || all[0];
  if (!editor) throw new Error('no agents visible - cannot seed');

  console.log('\n[1/7] Knowledge collection + fixture documents');
  const collectionId = await ensureCollection();
  if (collectionId) await seedDocuments(collectionId);

  console.log('\n[2/7] Draft agents');
  const created = await ensureAgents({
    all,
    providerName: editor.providerName,
    model: editor.model,
  });
  const agentsByName = { 'Editor-in-Chief': editor, ...created };

  console.log('\n[3/7] Memory facts + blocks');
  await seedMemory();

  console.log('\n[4/7] Scheduled task');
  await ensureScheduledTask(editor.id);

  console.log('\n[5/7] Guardrail rule');
  await ensureGuardrailRule();

  console.log('\n[6/7] Channel contacts');
  await ensureContacts(created['Senior Reporter']?.id ?? editor.id);

  console.log('\n[7/7] Real model turns');
  await seedTurns(agentsByName);

  await verify(collectionId);

  const counts = results.reduce((m, r) => ((m[r.status] = (m[r.status] || 0) + 1), m), {});
  console.log('\n=== Summary ===');
  for (const [k, v] of Object.entries(counts)) console.log(`  ${k}: ${v}`);
  console.log(hardFailures === 0 ? '\nSeed completed OK.' : `\nSeed finished with ${hardFailures} failure(s).`);
  process.exit(hardFailures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(`\nFATAL: ${e.message}`);
  process.exit(1);
});
