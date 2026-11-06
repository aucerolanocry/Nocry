// =====================================================================
//  NOCRY · servidor de keys
//  Cloudflare Worker + banco D1. Nada aqui tem segredo: os segredos
//  (OWNER_KEY e SIGN_KEY) ficam nas configurações do Worker.
// =====================================================================
const ENC = new TextEncoder();
const ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const DAY = 86400000;
const WINDOW = 10 * 60 * 1000; // janela do limite de tentativas erradas
const MAX_FAILS = 15; // tentativas erradas por IP dentro da janela
const ONLINE_MS = 150 * 1000; // sem ping ha mais que isso = offline (o app pinga a cada 60 s)
const SESSION_GAP = 5 * 60 * 1000; // sumiu por mais que isso = a proxima vez e uma sessao nova
const KEEP = 30 * DAY; // historico guardado por 30 dias
const OWNER_REC = { key: "OWNER", role: "owner", label: "aucerola", exp: null, max_dev: 99, revoked: 0 };
// eventos que o app pode mandar (o resto e ignorado)
const KINDS = ["bypass_on", "bypass_off", "bypass_aplicou", "bypass_restaurou", "modo_on", "modo_off", "recurso"];

let ready = false;
let signKeyCache = null;

// ---------------------------------------------------------------- util
function json(o, status) {
  return new Response(JSON.stringify(o), {
    status: status || 200,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function clean(s, n) {
  return String(s == null ? "" : s).replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, n);
}

function normKey(k) {
  return String(k == null ? "" : k).trim().toUpperCase().replace(/\s+/g, "");
}

function toInt(v, def) {
  const n = parseInt(v, 10);
  return isNaN(n) ? def : n;
}

function clamp(n, lo, hi) {
  return Math.min(hi, Math.max(lo, n));
}

function b64(buf) {
  const a = new Uint8Array(buf);
  let s = "";
  for (let i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);
  return btoa(s);
}

async function sha256hex(s) {
  const d = await crypto.subtle.digest("SHA-256", ENC.encode(s));
  return Array.from(new Uint8Array(d)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// comparacao em tempo constante
function ctEq(a, b) {
  const x = ENC.encode(String(a));
  const y = ENC.encode(String(b));
  let d = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) d |= (x[i] || 0) ^ (y[i] || 0);
  return d === 0;
}

function randomKey() {
  const a = new Uint8Array(12);
  crypto.getRandomValues(a);
  let s = "";
  for (let i = 0; i < 12; i++) {
    s += ALPHA[a[i] % 32];
    if (i === 3 || i === 7) s += "-";
  }
  return "NOCRY-" + s;
}

function ownerKey(env) {
  const k = normKey(env.OWNER_KEY || "");
  return k.length >= 8 ? k : "";
}

// ---------------------------------------------------------------- banco
async function init(env) {
  if (ready) return;
  await env.DB.batch([
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS keys (key TEXT PRIMARY KEY, role TEXT NOT NULL DEFAULT 'user', label TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, created_by TEXT NOT NULL DEFAULT '', exp INTEGER, max_dev INTEGER NOT NULL DEFAULT 1, revoked INTEGER NOT NULL DEFAULT 0)"
    ),
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS devices (key TEXT NOT NULL, dev TEXT NOT NULL, brand TEXT, model TEXT, android TEXT, sdk INTEGER, app TEXT, lang TEXT, ip TEXT, country TEXT, first_seen INTEGER, last_seen INTEGER, n INTEGER NOT NULL DEFAULT 1, blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (key, dev))"
    ),
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS log (id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER, key TEXT, dev TEXT, ip TEXT, ok INTEGER, reason TEXT)"
    ),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS fails (ip TEXT PRIMARY KEY, n INTEGER NOT NULL, t INTEGER NOT NULL)"),
    // uma linha por "vez que o app ficou aberto/rodando": comeca no 1o ping e estica a cada ping
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL, dev TEXT NOT NULL, start INTEGER NOT NULL, last INTEGER NOT NULL, fg INTEGER NOT NULL DEFAULT 0, app TEXT, ip TEXT, country TEXT, st TEXT, pings INTEGER NOT NULL DEFAULT 1)"
    ),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS sessions_last ON sessions (last)"),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS sessions_kd ON sessions (key, dev, last)"),
    // o que a pessoa fez no app (bypass e modos), com horario
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, key TEXT NOT NULL, dev TEXT NOT NULL, kind TEXT NOT NULL, detail TEXT)"
    ),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS events_t ON events (t)"),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS log_t ON log (t)"),
  ]);
  ready = true;
}

async function tooMany(env, ip, now) {
  const r = await env.DB.prepare("SELECT n, t FROM fails WHERE ip = ?").bind(ip).first();
  return !!(r && now - r.t < WINDOW && r.n >= MAX_FAILS);
}

async function addFail(env, ip, now) {
  await env.DB.prepare(
    "INSERT INTO fails (ip, n, t) VALUES (?1, 1, ?2) ON CONFLICT(ip) DO UPDATE SET n = CASE WHEN ?2 - t > ?3 THEN 1 ELSE n + 1 END, t = ?2"
  )
    .bind(ip, now, WINDOW)
    .run();
}

async function addLog(env, now, key, dev, ip, ok, reason) {
  await env.DB.prepare("INSERT INTO log (t, key, dev, ip, ok, reason) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(now, key, dev, ip, ok ? 1 : 0, reason)
    .run();
}

// ---------------------------------------------------------------- assinatura
async function sign(env, payload) {
  if (!signKeyCache) {
    signKeyCache = await crypto.subtle.importKey(
      "jwk",
      JSON.parse(env.SIGN_KEY),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["sign"]
    );
  }
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, signKeyCache, ENC.encode(payload));
  return b64(sig); // 64 bytes (r||s)
}

// ---------------------------------------------------------------- /v1/auth (o app)
async function authRoute(req, env, ip, country) {
  let b;
  try {
    b = await req.json();
  } catch (e) {
    return json({ ok: false, reason: "bad" }, 400);
  }
  const now = Date.now();
  if (await tooMany(env, ip, now)) return json({ ok: false, reason: "limit" }, 429);

  const key = normKey(b.key);
  const dev = clean(b.dev, 128).toLowerCase();
  const nonce = clean(b.nonce, 64);
  if (!/^[A-Z0-9-]{4,64}$/.test(key) || !/^[a-f0-9]{16,128}$/.test(dev) || !/^[A-Za-z0-9_-]{8,64}$/.test(nonce)) {
    await addFail(env, ip, now);
    return json({ ok: false, reason: "invalid" });
  }

  const ok = ownerKey(env);
  const isOwner = ok !== "" && ctEq(key, ok);
  const rec = isOwner
    ? OWNER_REC
    : await env.DB.prepare("SELECT key, role, label, exp, max_dev, revoked FROM keys WHERE key = ?").bind(key).first();

  if (!rec) {
    await addFail(env, ip, now);
    await addLog(env, now, "?", dev.slice(0, 12), ip, false, "invalid");
    return json({ ok: false, reason: "invalid" });
  }

  const deny = async (reason) => {
    await addLog(env, now, rec.key, dev.slice(0, 12), ip, false, reason);
    return json({ ok: false, reason: reason });
  };

  if (rec.revoked) return deny("revoked");
  if (rec.exp && rec.exp < now) return deny("expired");

  const brand = clean(b.brand, 40);
  const model = clean(b.model, 60);
  const android = clean(b.android, 20);
  const sdk = clamp(toInt(b.sdk, 0), 0, 99);
  const app = clean(b.app, 20);
  const lang = clean(b.lang, 20);

  const row = await env.DB.prepare("SELECT blocked FROM devices WHERE key = ? AND dev = ?").bind(rec.key, dev).first();
  if (row) {
    if (row.blocked) return deny("blocked");
    await env.DB.prepare(
      "UPDATE devices SET last_seen = ?, n = n + 1, ip = ?, country = ?, brand = ?, model = ?, android = ?, sdk = ?, app = ?, lang = ? WHERE key = ? AND dev = ?"
    )
      .bind(now, ip, country, brand, model, android, sdk, app, lang, rec.key, dev)
      .run();
  } else {
    const c = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE key = ?").bind(rec.key).first();
    if (c && c.n >= rec.max_dev) return deny("device_limit");
    await env.DB.prepare(
      "INSERT INTO devices (key, dev, brand, model, android, sdk, app, lang, ip, country, first_seen, last_seen, n, blocked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0)"
    )
      .bind(rec.key, dev, brand, model, android, sdk, app, lang, ip, country, now, now)
      .run();
  }
  // "renew" = o app so reconfirmou a key sozinho (a cada 6 h); "ok" = a pessoa entrou com a key
  await addLog(env, now, rec.key, dev.slice(0, 12), ip, true, b.modo === "renovar" ? "renew" : "ok");

  const exp = rec.exp || 0;
  const kh = (await sha256hex(key)).slice(0, 16);
  const payload = ["NOCRY1", dev, kh, rec.role, exp, now, nonce].join("|");
  return json({ ok: true, role: rec.role, label: rec.label || "", exp: exp, ts: now, p: payload, sig: await sign(env, payload) });
}

// ---------------------------------------------------------------- /v1/ping (o app avisa que esta em uso)
// Corpo: { key, dev, app, fg, agora, estado: {...}, ev: [{i, t, k, d}] }
// Atualiza "visto por ultimo", abre/estica a sessao e grava os eventos que vieram juntos.
async function pingRoute(req, env, ip, country) {
  let b;
  try {
    b = await req.json();
  } catch (e) {
    return json({ ok: false, reason: "bad" }, 400);
  }
  const now = Date.now();
  if (await tooMany(env, ip, now)) return json({ ok: false, reason: "limit" }, 429);

  const key = normKey(b.key);
  const dev = clean(b.dev, 128).toLowerCase();
  if (!/^[A-Z0-9-]{4,64}$/.test(key) || !/^[a-f0-9]{16,128}$/.test(dev)) {
    await addFail(env, ip, now);
    return json({ ok: false, reason: "invalid" });
  }
  const ok = ownerKey(env);
  const isOwner = ok !== "" && ctEq(key, ok);
  const rec = isOwner
    ? OWNER_REC
    : await env.DB.prepare("SELECT key, role, exp, revoked FROM keys WHERE key = ?").bind(key).first();
  if (!rec) {
    await addFail(env, ip, now);
    return json({ ok: false, reason: "invalid" });
  }
  if (rec.revoked) return json({ ok: false, reason: "revoked" });
  if (rec.exp && rec.exp < now) return json({ ok: false, reason: "expired" });
  // so vale pra aparelho que ja entrou com essa key
  const d = await env.DB.prepare("SELECT blocked FROM devices WHERE key = ? AND dev = ?").bind(rec.key, dev).first();
  if (!d) return json({ ok: false, reason: "invalid" });
  if (d.blocked) return json({ ok: false, reason: "blocked" });

  const app = clean(b.app, 20);
  const fg = b.fg ? 1 : 0;
  const st = clean(JSON.stringify(b.estado && typeof b.estado === "object" ? b.estado : {}), 600);

  const ops = [
    env.DB.prepare("UPDATE devices SET last_seen = ?, ip = ?, country = ?, app = ? WHERE key = ? AND dev = ?").bind(
      now, ip, country, app, rec.key, dev
    ),
  ];
  const s = await env.DB.prepare("SELECT id, last FROM sessions WHERE key = ? AND dev = ? ORDER BY last DESC LIMIT 1")
    .bind(rec.key, dev)
    .first();
  if (s && now - s.last < SESSION_GAP) {
    ops.push(
      env.DB.prepare("UPDATE sessions SET last = ?, fg = ?, app = ?, ip = ?, country = ?, st = ?, pings = pings + 1 WHERE id = ?").bind(
        now, fg, app, ip, country, st, s.id
      )
    );
  } else {
    ops.push(
      env.DB.prepare(
        "INSERT INTO sessions (key, dev, start, last, fg, app, ip, country, st, pings) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
      ).bind(rec.key, dev, now, now, fg, app, ip, country, st)
    );
  }

  // eventos: o horario vem do celular; corrige pelo relogio do servidor (o celular pode estar adiantado/atrasado)
  const agoraCel = toInt(b.agora, now);
  const ev = Array.isArray(b.ev) ? b.ev.slice(0, 60) : [];
  for (const e of ev) {
    if (!e || KINDS.indexOf(e.k) < 0) continue;
    const atras = clamp(agoraCel - toInt(e.t, agoraCel), 0, 7 * DAY);
    ops.push(
      env.DB.prepare("INSERT INTO events (t, key, dev, kind, detail) VALUES (?, ?, ?, ?, ?)").bind(
        now - atras, rec.key, dev, e.k, clean(e.d, 120)
      )
    );
  }
  await env.DB.batch(ops);
  return json({ ok: true, ts: now });
}

// ---------------------------------------------------------------- /api (o painel)
async function who(env, req) {
  const k = normKey(req.headers.get("X-Key"));
  if (!k) return null;
  const ok = ownerKey(env);
  if (ok !== "" && ctEq(k, ok)) return { role: "owner", key: "OWNER", label: "aucerola" };
  const r = await env.DB.prepare("SELECT key, role, label, exp, revoked FROM keys WHERE key = ?").bind(k).first();
  if (r && r.role === "staff" && !r.revoked && (!r.exp || r.exp > Date.now())) {
    return { role: "staff", key: r.key, label: r.label || r.key };
  }
  return null;
}

async function targetOf(env, me, keyStr) {
  const k = normKey(keyStr);
  if (k === "OWNER") return me.role === "owner" ? OWNER_REC : null;
  const r = await env.DB.prepare("SELECT key, role, label, exp, max_dev, revoked FROM keys WHERE key = ?").bind(k).first();
  if (!r) return null;
  if (me.role !== "owner" && r.role !== "user") return null;
  return r;
}

/** nome que aparece no painel pra uma linha com key/label/role (a key do dono nao fica na tabela keys) */
function quem(r) {
  if (r.key === "OWNER") {
    r.label = OWNER_REC.label;
    r.role = "owner";
  }
  return r;
}

/** tira IP e pais das linhas quando quem pede e staff */
function semIp(rows, owner) {
  if (owner) return rows;
  for (const r of rows) {
    delete r.ip;
    delete r.country;
  }
  return rows;
}

async function apiRoute(req, env, ip, op) {
  const now = Date.now();
  if (await tooMany(env, ip, now)) return json({ ok: false, reason: "limit" }, 429);
  const me = await who(env, req);
  if (!me) {
    await addFail(env, ip, now);
    return json({ ok: false, reason: "denied" }, 401);
  }
  let b = {};
  try {
    b = await req.json();
  } catch (e) {
    b = {};
  }
  const owner = me.role === "owner";
  const ow = owner ? 1 : 0; // staff so ve keys de usuario
  const FILTRO = "(?2 = 1 OR k.role = 'user')";

  if (op === "me") return json({ ok: true, role: me.role, label: me.label });

  if (op === "list") {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM log WHERE t < ?").bind(now - KEEP),
      env.DB.prepare("DELETE FROM fails WHERE t < ?").bind(now - WINDOW),
      env.DB.prepare("DELETE FROM events WHERE t < ?").bind(now - KEEP),
      env.DB.prepare("DELETE FROM sessions WHERE last < ?").bind(now - KEEP),
    ]);
    const keys = (
      await env.DB.prepare(
        owner
          ? "SELECT * FROM keys ORDER BY created_at DESC LIMIT 500"
          : "SELECT * FROM keys WHERE role = 'user' ORDER BY created_at DESC LIMIT 500"
      ).all()
    ).results;
    const devs = (
      await env.DB.prepare(
        "SELECT key, dev, brand, model, android, sdk, app, lang, ip, country, first_seen, last_seen, n, blocked FROM devices ORDER BY last_seen DESC LIMIT 3000"
      ).all()
    ).results;
    const by = {};
    for (const d of devs) {
      if (!owner) {
        delete d.ip;
        delete d.country;
      }
      (by[d.key] = by[d.key] || []).push(d);
    }
    const out = keys.map((k) => Object.assign({}, k, { devices: by[k.key] || [] }));
    if (owner) out.unshift(Object.assign({}, OWNER_REC, { created_at: 0, created_by: "", devices: by["OWNER"] || [] }));
    return json({ ok: true, me: { role: me.role, label: me.label }, keys: out, now: now, online_ms: ONLINE_MS });
  }

  // quem esta com o app aberto/rodando agora + numeros das ultimas 24 h
  if (op === "live") {
    const rows = (
      await env.DB.prepare(
        "SELECT s.key, s.dev, s.start, s.last, s.fg, s.app, s.ip, s.country, s.st, k.label, k.role, d.brand, d.model, d.android " +
          "FROM sessions s LEFT JOIN keys k ON k.key = s.key LEFT JOIN devices d ON d.key = s.key AND d.dev = s.dev " +
          "WHERE s.last >= ?1 AND " + FILTRO + " ORDER BY s.start DESC LIMIT 300"
      )
        .bind(now - ONLINE_MS, ow)
        .all()
    ).results.map(quem);
    // quem saiu nas ultimas 6 h (pra ver que horas entrou e que horas saiu)
    const recentes = (
      await env.DB.prepare(
        "SELECT s.key, s.dev, s.start, s.last, s.app, s.ip, s.country, k.label, k.role, d.brand, d.model " +
          "FROM sessions s LEFT JOIN keys k ON k.key = s.key LEFT JOIN devices d ON d.key = s.key AND d.dev = s.dev " +
          "WHERE s.last < ?1 AND s.last >= ?3 AND " + FILTRO + " ORDER BY s.last DESC LIMIT 40"
      )
        .bind(now - ONLINE_MS, ow, now - 6 * 3600 * 1000)
        .all()
    ).results.map(quem);
    const dia = now - DAY;
    const r = await env.DB.batch([
      env.DB.prepare(
        "SELECT COUNT(*) AS n FROM log l LEFT JOIN keys k ON k.key = l.key WHERE l.ok = 1 AND l.reason = 'ok' AND l.t >= ?1 AND " + FILTRO
      ).bind(dia, ow),
      env.DB.prepare(
        "SELECT COUNT(*) AS n FROM events e LEFT JOIN keys k ON k.key = e.key WHERE e.kind = 'bypass_aplicou' AND e.t >= ?1 AND " + FILTRO
      ).bind(dia, ow),
      env.DB.prepare(
        "SELECT COUNT(*) AS n, COUNT(DISTINCT s.key || s.dev) AS u FROM sessions s LEFT JOIN keys k ON k.key = s.key WHERE s.start >= ?1 AND " + FILTRO
      ).bind(dia, ow),
    ]);
    const n = (i, c) => (r[i].results[0] && r[i].results[0][c || "n"]) || 0;
    return json({
      ok: true,
      now: now,
      online: semIp(rows, owner),
      recentes: semIp(recentes, owner),
      online_ms: ONLINE_MS,
      stats: { entradas: n(0), aplicados: n(1), sessoes: n(2), pessoas: n(2, "u") },
    });
  }

  // historico: tipo = tudo | entradas | bypass | modos | sessoes; key opcional; antes = paginacao (t)
  if (op === "history") {
    const tipo = ["tudo", "entradas", "bypass", "modos", "sessoes"].indexOf(b.tipo) >= 0 ? b.tipo : "tudo";
    const antes = toInt(b.antes, 0) > 0 ? toInt(b.antes, 0) : now + 1;
    const kf = b.key ? normKey(b.key) : "";
    const lim = 150;
    let items = [];
    if (tipo === "sessoes") {
      items = (
        await env.DB.prepare(
          "SELECT 'sessao' AS tipo, s.start AS t, s.key, s.dev, s.start, s.last, s.fg, s.app, s.ip, s.country, s.st, k.label, k.role, d.brand, d.model " +
            "FROM sessions s LEFT JOIN keys k ON k.key = s.key LEFT JOIN devices d ON d.key = s.key AND d.dev = s.dev " +
            "WHERE s.start < ?1 AND " + FILTRO + " AND (?3 = '' OR s.key = ?3) ORDER BY s.start DESC LIMIT ?4"
        )
          .bind(antes, ow, kf, lim)
          .all()
      ).results;
    } else {
      if (tipo === "tudo" || tipo === "entradas") {
        items = items.concat(
          (
            await env.DB.prepare(
              "SELECT 'entrada' AS tipo, l.t, l.key, l.dev, l.ip, k.label, k.role, d.brand, d.model " +
                "FROM log l LEFT JOIN keys k ON k.key = l.key LEFT JOIN devices d ON d.key = l.key AND substr(d.dev, 1, 12) = l.dev " +
                "WHERE l.ok = 1 AND l.reason = 'ok' AND l.t < ?1 AND " + FILTRO + " AND (?3 = '' OR l.key = ?3) ORDER BY l.t DESC LIMIT ?4"
            )
              .bind(antes, ow, kf, lim)
              .all()
          ).results
        );
      }
      if (tipo !== "entradas") {
        const kinds =
          tipo === "bypass" ? "e.kind LIKE 'bypass%'" : tipo === "modos" ? "(e.kind LIKE 'modo%' OR e.kind = 'recurso')" : "1 = 1";
        items = items.concat(
          (
            await env.DB.prepare(
              "SELECT 'evento' AS tipo, e.t, e.key, e.dev, e.kind, e.detail, k.label, k.role, d.brand, d.model " +
                "FROM events e LEFT JOIN keys k ON k.key = e.key LEFT JOIN devices d ON d.key = e.key AND d.dev = e.dev " +
                "WHERE e.t < ?1 AND " + FILTRO + " AND (?3 = '' OR e.key = ?3) AND " + kinds + " ORDER BY e.t DESC LIMIT ?4"
            )
              .bind(antes, ow, kf, lim)
              .all()
          ).results
        );
      }
      items.sort((a, c) => c.t - a.t);
      items = items.slice(0, lim);
    }
    return json({ ok: true, now: now, items: semIp(items.map(quem), owner), mais: items.length >= lim });
  }

  if (op === "create") {
    const role = b.role === "staff" ? "staff" : "user";
    if (role === "staff" && !owner) return json({ ok: false, reason: "denied" }, 403);
    const count = clamp(toInt(b.count, 1), 1, 50);
    const days = clamp(toInt(b.days, 0), 0, 3650);
    const maxDev = clamp(toInt(b.maxDev, 1), 1, owner ? 99 : 5);
    const label = clean(b.label, 60);
    const exp = days > 0 ? now + days * DAY : null;
    const by = me.key === "OWNER" ? "aucerola" : me.label;
    const made = [];
    for (let i = 0; i < count; i++) {
      for (let tryN = 0; tryN < 3; tryN++) {
        const k = randomKey();
        const r = await env.DB.prepare(
          "INSERT OR IGNORE INTO keys (key, role, label, created_at, created_by, exp, max_dev, revoked) VALUES (?, ?, ?, ?, ?, ?, ?, 0)"
        )
          .bind(k, role, label, now, by, exp, maxDev)
          .run();
        if (r.meta && r.meta.changes > 0) {
          made.push(k);
          break;
        }
      }
    }
    return json({ ok: true, keys: made });
  }

  if (op === "revoke" || op === "reset" || op === "block" || op === "delete" || op === "update") {
    const t = await targetOf(env, me, b.key);
    if (!t) return json({ ok: false, reason: "not_found" }, 404);
    const isOwnerRec = t.key === "OWNER";

    if (op === "revoke") {
      if (isOwnerRec) return json({ ok: false, reason: "denied" }, 403);
      await env.DB.prepare("UPDATE keys SET revoked = ? WHERE key = ?").bind(b.revoked ? 1 : 0, t.key).run();
      return json({ ok: true });
    }
    if (op === "reset") {
      await env.DB.prepare("DELETE FROM devices WHERE key = ?").bind(t.key).run();
      return json({ ok: true });
    }
    if (op === "block") {
      const dev = clean(b.dev, 128).toLowerCase();
      await env.DB.prepare("UPDATE devices SET blocked = ? WHERE key = ? AND dev = ?").bind(b.blocked ? 1 : 0, t.key, dev).run();
      return json({ ok: true });
    }
    if (op === "delete") {
      if (!owner || isOwnerRec) return json({ ok: false, reason: "denied" }, 403);
      await env.DB.batch([
        env.DB.prepare("DELETE FROM devices WHERE key = ?").bind(t.key),
        env.DB.prepare("DELETE FROM sessions WHERE key = ?").bind(t.key),
        env.DB.prepare("DELETE FROM events WHERE key = ?").bind(t.key),
        env.DB.prepare("DELETE FROM keys WHERE key = ?").bind(t.key),
      ]);
      return json({ ok: true });
    }
    if (op === "update") {
      if (isOwnerRec) return json({ ok: false, reason: "denied" }, 403);
      const label = b.label == null ? t.label : clean(b.label, 60);
      const maxDev = b.maxDev == null ? t.max_dev : clamp(toInt(b.maxDev, t.max_dev), 1, owner ? 99 : 5);
      let exp = t.exp;
      if (b.days != null) {
        const d = clamp(toInt(b.days, 0), 0, 3650);
        exp = d > 0 ? now + d * DAY : null;
      }
      await env.DB.prepare("UPDATE keys SET label = ?, max_dev = ?, exp = ? WHERE key = ?").bind(label, maxDev, exp, t.key).run();
      return json({ ok: true });
    }
  }

  if (op === "log") {
    if (!owner) return json({ ok: false, reason: "denied" }, 403);
    const rows = (
      await env.DB.prepare(
        "SELECT l.t, l.key, l.dev, l.ip, l.ok, l.reason, k.label FROM log l LEFT JOIN keys k ON k.key = l.key ORDER BY l.id DESC LIMIT 300"
      ).all()
    ).results;
    return json({ ok: true, log: rows });
  }

  return json({ ok: false, reason: "not_found" }, 404);
}

// ---------------------------------------------------------------- entrada
export default {
  async fetch(req, env) {
    try {
      const url = new URL(req.url);
      const path = url.pathname;
      const ip = req.headers.get("CF-Connecting-IP") || "?";
      const country = (req.cf && req.cf.country) || "";

      if (req.method === "GET" && path === "/health") {
        return json({ ok: true, db: !!env.DB, owner: ownerKey(env) !== "", sign: !!env.SIGN_KEY });
      }
      if (req.method === "GET" && (path === "/" || path === "/admin")) {
        return new Response(PANEL, {
          headers: {
            "content-type": "text/html; charset=utf-8",
            "cache-control": "no-store",
            "content-security-policy":
              "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            "x-content-type-options": "nosniff",
            "referrer-policy": "no-referrer",
          },
        });
      }

      await init(env);
      if (req.method === "POST" && path === "/v1/auth") return await authRoute(req, env, ip, country);
      if (req.method === "POST" && path === "/v1/ping") return await pingRoute(req, env, ip, country);
      if (req.method === "POST" && path.indexOf("/api/") === 0) return await apiRoute(req, env, ip, path.slice(5));
      return json({ ok: false, reason: "not_found" }, 404);
    } catch (e) {
      console.log("erro", e && e.message ? e.message : String(e));
      return json({ ok: false, reason: "server" }, 500);
    }
  },
};

// ---------------------------------------------------------------- painel
const PANEL = String.raw`<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex">
<meta name="theme-color" content="#05060B">
<title>Nocry · painel da staff</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Instrument+Sans:wght@400;500;600;700&family=Pixelify+Sans:wght@500;600;700&family=Red+Hat+Mono:wght@400;500;600&display=swap">
<style>
/* mesmo visual do app: fundo quase preto, degrade branco -> prata, cartao com borda em degrade,
   Pixelify Sans nos titulos, Instrument Sans no texto, tudo em maiusculo */
:root{
  --bg:#05060B;--bg2:#080A12;--card:#0B0D17;--card2:#10131F;--card3:#161A29;
  --line:#1B1F2E;--line2:#272C3F;
  --text:#F2F3F8;--mut:#9195A9;--mut2:#5D6177;
  --c1:#FFFFFF;--c2:#A8ADBF;
  --ok:#3DDC84;--okbg:rgba(61,220,132,.10);--okln:rgba(61,220,132,.28);
  --warn:#F2B84B;--warnbg:rgba(242,184,75,.10);--warnln:rgba(242,184,75,.28);
  --bad:#FF5A75;--badbg:rgba(255,90,117,.10);--badln:rgba(255,90,117,.30);
  --info:#86ACFF;--infobg:rgba(134,172,255,.10);--infoln:rgba(134,172,255,.28);
  --roxo:#BDA3FF;--roxobg:rgba(189,163,255,.10);--roxoln:rgba(189,163,255,.28);
  --disp:'Pixelify Sans',ui-monospace,Menlo,monospace;
  --body:'Instrument Sans',system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;
  --mono:'Red Hat Mono',ui-monospace,Menlo,Consolas,monospace;
  --r:11px;
}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html{background:var(--bg)}
body{margin:0;min-height:100vh;color:var(--text);font:500 12.5px/1.45 var(--body);letter-spacing:.035em;text-transform:uppercase;
  background:radial-gradient(1000px 520px at 50% -14%,rgba(255,255,255,.075),transparent 62%),var(--bg);-webkit-font-smoothing:antialiased}
button{font:inherit;color:inherit}
svg{flex:none;display:block}
.wrap{max-width:900px;margin:0 auto;padding:0 16px calc(100px + env(safe-area-inset-bottom))}
.row{display:flex;align-items:center;gap:8px;min-width:0}
.grow{flex:1;min-width:0}
.mono{font-family:var(--mono);text-transform:none;letter-spacing:.02em}
.ell{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}

/* topo */
.top{position:sticky;top:0;z-index:5;display:flex;align-items:center;gap:10px;padding:14px 0 14px;
  background:linear-gradient(var(--bg) 72%,rgba(5,6,11,0))}
.mark{flex:none;width:34px;height:34px;border-radius:10px;display:grid;place-items:center;font:700 18px var(--disp);color:#07080D;
  background:linear-gradient(135deg,var(--c1),var(--c2));box-shadow:0 0 20px -4px rgba(255,255,255,.4),inset 0 1px 0 rgba(255,255,255,.8)}
.brand{font:700 18px/1 var(--disp);letter-spacing:.16em;background:linear-gradient(90deg,var(--c1),var(--c2));-webkit-background-clip:text;background-clip:text;color:transparent}
.brandsub{font:600 9px var(--body);letter-spacing:.22em;color:var(--mut);margin-top:4px}
.quem{max-width:44vw}

/* navegacao de baixo */
.nav{position:fixed;left:0;right:0;bottom:0;z-index:6;padding:8px 10px calc(8px + env(safe-area-inset-bottom));
  background:rgba(6,7,12,.88);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);border-top:1px solid var(--line)}
.nav .in{max-width:600px;margin:0 auto;display:flex;gap:4px}
.nav button{flex:1;min-width:0;display:flex;flex-direction:column;align-items:center;gap:4px;padding:7px 0 6px;border:0;background:none;color:var(--mut2);
  font:700 8.5px var(--body);letter-spacing:.14em;border-radius:12px;cursor:pointer;transition:color .15s,background .15s}
.nav button.on{color:var(--c1);background:rgba(255,255,255,.055)}
.nav button.on svg{filter:drop-shadow(0 0 6px rgba(255,255,255,.55))}
.ni{position:relative}
.nb{position:absolute;top:-5px;right:-11px;min-width:16px;height:16px;padding:0 4px;border-radius:8px;background:var(--ok);color:#04130A;
  font:700 9px/16px var(--body);letter-spacing:0;text-align:center;box-shadow:0 0 10px rgba(61,220,132,.6)}

/* titulos */
.h1{font:700 21px var(--disp);letter-spacing:.09em;margin:4px 2px 0}
.lead{color:var(--mut);font-size:10.5px;letter-spacing:.07em;margin:5px 2px 0;line-height:1.5}
.sec{display:flex;align-items:center;gap:8px;margin:24px 2px 0;font:700 9.5px var(--body);letter-spacing:.22em;color:var(--mut)}
.sec::after{content:"";flex:1;height:1px;background:linear-gradient(90deg,var(--line2),transparent)}
.sec b{color:var(--c1)}

/* cartoes */
.card{position:relative;border-radius:var(--r);padding:14px;margin-top:10px;
  background:linear-gradient(160deg,var(--card2),var(--card)) padding-box,
    linear-gradient(140deg,rgba(255,255,255,.30),rgba(255,255,255,.05) 42%,rgba(255,255,255,.15)) border-box;
  border:1px solid transparent;box-shadow:0 14px 30px -20px rgba(0,0,0,.9)}
.grid{display:grid;gap:0 10px}
@media(min-width:760px){.grid{grid-template-columns:1fr 1fr}}
.hero{padding:16px;overflow:hidden}
.hero::before{content:"";position:absolute;right:-60px;top:-80px;width:220px;height:220px;border-radius:50%;
  background:radial-gradient(circle,rgba(61,220,132,.16),transparent 65%)}
.lbl{font:700 9.5px var(--body);letter-spacing:.2em;color:var(--mut)}
.big{font:700 40px/1.05 var(--disp);letter-spacing:.04em;margin-top:4px}
.big small{font:700 12px var(--body);letter-spacing:.16em;color:var(--mut);margin-left:6px}

.stats{display:grid;grid-template-columns:repeat(2,1fr);gap:8px;margin-top:8px}
@media(min-width:640px){.stats{grid-template-columns:repeat(4,1fr)}}
.stat{position:relative;border-radius:var(--r);padding:12px;background:var(--card);border:1px solid var(--line)}
.stat .n{font:700 22px/1.1 var(--disp);letter-spacing:.04em}
.stat .l{font:700 8.5px var(--body);letter-spacing:.16em;color:var(--mut);margin-top:5px;line-height:1.35;padding-right:18px}
.stat svg{position:absolute;right:11px;top:12px;color:var(--mut2)}
.stat .l2{font:700 8px var(--body);letter-spacing:.16em;color:var(--mut2);margin-top:2px}

/* chips */
.chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:12px}
.chip{display:inline-flex;align-items:center;gap:5px;height:22px;padding:0 9px;border-radius:999px;font:700 9px var(--body);letter-spacing:.12em;
  background:var(--card3);color:var(--mut);border:1px solid var(--line2);white-space:nowrap;max-width:100%}
.chip .dot{width:6px;height:6px;border-radius:50%;background:currentColor;flex:none}
.chip.ok{background:var(--okbg);color:var(--ok);border-color:var(--okln)}
.chip.warn{background:var(--warnbg);color:var(--warn);border-color:var(--warnln)}
.chip.bad{background:var(--badbg);color:var(--bad);border-color:var(--badln)}
.chip.info{background:var(--infobg);color:var(--info);border-color:var(--infoln)}
.chip.roxo{background:var(--roxobg);color:var(--roxo);border-color:var(--roxoln)}
.chip .x{border:0;background:none;padding:0 0 0 2px;cursor:pointer;color:inherit;display:grid;place-items:center}

/* filtros */
.pills{display:flex;gap:6px;overflow-x:auto;margin:14px -16px 0;padding:2px 16px;scrollbar-width:none}
.pills::-webkit-scrollbar{display:none}
.pill{flex:none;height:32px;padding:0 13px;border-radius:999px;border:1px solid var(--line2);background:var(--card);color:var(--mut);
  font:700 9.5px var(--body);letter-spacing:.15em;cursor:pointer;display:inline-flex;align-items:center;gap:6px}
.pill i{font-style:normal;color:var(--mut2)}
.pill.on{background:linear-gradient(135deg,var(--c1),var(--c2));color:#07080D;border-color:transparent}
.pill.on i{color:#3A3D48}

/* botoes */
.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;height:34px;padding:0 12px;border-radius:9px;border:1px solid var(--line2);
  background:var(--card3);color:var(--text);font:700 9.5px var(--body);letter-spacing:.14em;cursor:pointer;transition:transform .08s,border-color .15s,opacity .15s}
.btn:hover{border-color:#3B415A}
.btn:active{transform:scale(.97)}
.btn[disabled]{opacity:.5;pointer-events:none}
.btn.p{background:linear-gradient(135deg,var(--c1),var(--c2));border-color:transparent;color:#07080D;box-shadow:0 8px 22px -10px rgba(255,255,255,.5)}
.btn.d{background:var(--badbg);border-color:var(--badln);color:#FF8EA0}
.btn.g{background:none;border-color:transparent;color:var(--mut)}
.btn.big{height:46px;width:100%;font-size:11px;border-radius:11px}
.btn.ic{width:34px;padding:0}
.acts{display:flex;flex-wrap:wrap;gap:6px;margin-top:12px}
.acts.end{justify-content:flex-end}
.acts.linha{flex-wrap:nowrap}
.btn.f{flex:1;min-width:0;padding:0 8px;letter-spacing:.1em}
.btn.on{border-color:#C7CBD8;background:var(--card2)}
.keycp{display:inline-flex;align-items:center;gap:6px;max-width:100%;margin-top:3px;padding:0;border:0;background:none;color:var(--mut2);cursor:pointer;text-align:left}
.keycp .keytxt{margin:0}
.keycp:hover{color:var(--c1)}
.pills.solto{flex-wrap:wrap;overflow:visible;margin:0;padding:0}

/* campos */
.field{margin-top:14px}
.field>label{display:block;font:700 9px var(--body);letter-spacing:.2em;color:var(--mut);margin:0 0 7px 2px}
input{width:100%;height:42px;border-radius:10px;border:1px solid var(--line2);background:var(--bg2);color:var(--text);padding:0 12px;
  font:500 12.5px var(--body);letter-spacing:.05em;text-transform:uppercase;transition:border-color .15s,box-shadow .15s}
input:focus{outline:none;border-color:#C7CBD8;box-shadow:0 0 0 3px rgba(255,255,255,.06)}
input::placeholder{color:var(--mut2)}
input[type=password]{font-family:var(--mono);text-transform:none}
.search{position:relative;margin-top:12px}
.search svg{position:absolute;left:13px;top:13px;color:var(--mut2)}
.search input{padding-left:38px}
.step{display:flex;gap:6px}
.step .btn{width:42px;height:42px;padding:0;font-size:17px;letter-spacing:0}
.step input{text-align:center;font-weight:700}
.seg{display:flex;gap:4px;padding:4px;border-radius:12px;background:var(--bg2);border:1px solid var(--line2)}
.seg button{flex:1;height:34px;border:0;border-radius:9px;background:none;color:var(--mut);font:700 9.5px var(--body);letter-spacing:.15em;cursor:pointer}
.seg button.on{background:linear-gradient(135deg,var(--c1),var(--c2));color:#07080D}

/* pessoa */
.pess{display:flex;gap:12px;align-items:flex-start}
.av{position:relative;flex:none;width:42px;height:42px;border-radius:13px;display:grid;place-items:center;font:700 15px var(--disp);letter-spacing:.04em;color:#07080D;
  background:linear-gradient(135deg,var(--c1),var(--c2));box-shadow:inset 0 1px 0 rgba(255,255,255,.8)}
.av.s{width:34px;height:34px;border-radius:10px;font-size:12px}
.av .st{position:absolute;right:-3px;bottom:-3px;width:13px;height:13px;border-radius:50%;border:2.5px solid var(--card);background:var(--mut2)}
.av .st.on{background:var(--ok);box-shadow:0 0 10px var(--ok)}
.av .st.bg{background:var(--warn)}
.nome{font:700 13px var(--body);letter-spacing:.07em}
.meta{color:var(--mut);font-size:10px;letter-spacing:.07em;margin-top:2px}
.keytxt{font:600 11.5px var(--mono);color:var(--c2);word-break:break-all;letter-spacing:.05em;margin-top:3px}

.info{display:grid;grid-template-columns:repeat(2,1fr);gap:6px;margin-top:12px}
@media(min-width:520px){.info{grid-template-columns:repeat(4,1fr)}}
.grid .info{grid-template-columns:repeat(2,1fr)}
.info div{background:var(--bg2);border:1px solid var(--line);border-radius:9px;padding:8px 10px;min-width:0}
.info small{display:block;font:700 8px var(--body);letter-spacing:.2em;color:var(--mut2)}
.info span{display:block;font:700 11px var(--body);letter-spacing:.05em;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.info span.ok{color:var(--ok)}

/* aparelhos */
.devs{margin-top:12px;border-top:1px solid var(--line);padding-top:4px}
.dev{padding:10px 0;border-bottom:1px dashed var(--line)}
.dev:last-child{border-bottom:0}
.dev b{font-weight:700;letter-spacing:.06em}
.ds{width:8px;height:8px;border-radius:50%;background:var(--mut2);flex:none}
.ds.on{background:var(--ok);box-shadow:0 0 8px var(--ok)}

/* linha do tempo */
.dia{position:sticky;top:62px;z-index:2;display:flex;align-items:center;gap:8px;margin:18px 0 0;padding:6px 2px;
  font:700 9.5px var(--body);letter-spacing:.2em;color:var(--mut);background:linear-gradient(var(--bg) 70%,rgba(5,6,11,0))}
.dia b{color:var(--c1)}
.dia i{font-style:normal;margin-left:auto;color:var(--mut2)}
.ev{position:relative;display:flex;gap:12px;align-items:flex-start;padding:11px 12px;border-radius:var(--r);background:var(--card);border:1px solid var(--line);margin-top:6px}
.ev.click{cursor:pointer;transition:border-color .15s}
.ev.click:hover{border-color:var(--line2)}
.ic{flex:none;width:32px;height:32px;border-radius:10px;display:grid;place-items:center;background:var(--card3);color:var(--c2);border:1px solid var(--line2)}
.ic.ok{background:var(--okbg);color:var(--ok);border-color:var(--okln)}
.ic.warn{background:var(--warnbg);color:var(--warn);border-color:var(--warnln)}
.ic.bad{background:var(--badbg);color:var(--bad);border-color:var(--badln)}
.ic.info{background:var(--infobg);color:var(--info);border-color:var(--infoln)}
.ic.roxo{background:var(--roxobg);color:var(--roxo);border-color:var(--roxoln)}
.tt{font:700 11.5px var(--body);letter-spacing:.08em;display:flex;align-items:center;gap:6px;flex-wrap:wrap}
.dd{color:var(--c2);font-size:10px;letter-spacing:.08em;margin-top:2px;font-weight:600}
.sb{color:var(--mut);font-size:10px;letter-spacing:.07em;margin-top:2px}
.hr{flex:none;margin-left:auto;text-align:right;font:600 11.5px var(--mono);color:var(--c1);letter-spacing:.03em}
.hr small{display:block;font:700 8.5px var(--body);letter-spacing:.14em;color:var(--mut2);margin-top:3px}
.bar{height:4px;border-radius:4px;background:var(--line);margin-top:8px;overflow:hidden}
.bar i{display:block;height:100%;border-radius:4px;background:linear-gradient(90deg,var(--c2),var(--c1))}
.bar.ok i{background:linear-gradient(90deg,#1F9E5A,var(--ok))}

/* vazio / carregando */
.vazio{text-align:center;padding:30px 18px;color:var(--mut);font-size:10.5px;letter-spacing:.07em;line-height:1.6}
.vazio svg{margin:0 auto 10px;color:var(--mut2)}
.vazio b{display:block;color:var(--text);font:700 12px var(--body);letter-spacing:.12em;margin-bottom:4px}
.load{display:flex;align-items:center;justify-content:center;gap:10px;padding:34px;color:var(--mut);font:700 9.5px var(--body);letter-spacing:.2em}
.spin{width:16px;height:16px;border-radius:50%;border:2px solid var(--line2);border-top-color:var(--c1);animation:spin .8s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
.pulse{position:relative;display:inline-block;width:7px;height:7px;border-radius:50%;background:var(--ok);flex:none}
.pulse::after{content:"";position:absolute;inset:-4px;border-radius:50%;border:2px solid var(--ok);opacity:0;animation:pulse 1.8s ease-out infinite}
@keyframes pulse{0%{transform:scale(.5);opacity:.9}100%{transform:scale(1.7);opacity:0}}

/* modal */
.ov{position:fixed;inset:0;z-index:20;background:rgba(2,3,6,.74);backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);
  display:flex;align-items:flex-end;justify-content:center;padding:12px;animation:fade .15s}
@media(min-width:640px){.ov{align-items:center}}
.modal{width:100%;max-width:440px;max-height:90vh;overflow:auto;border-radius:18px;padding:18px;
  background:linear-gradient(160deg,var(--card3),var(--card)) padding-box,
    linear-gradient(140deg,rgba(255,255,255,.38),rgba(255,255,255,.06) 50%,rgba(255,255,255,.2)) border-box;
  border:1px solid transparent;box-shadow:0 30px 60px -20px rgba(0,0,0,.9);animation:up .2s ease-out}
.modal h3{margin:0;font:700 15px var(--disp);letter-spacing:.09em}
.opc{display:flex;gap:12px;align-items:center;width:100%;padding:12px;margin-top:8px;border-radius:12px;border:1px solid var(--line2);background:var(--bg2);
  text-align:left;cursor:pointer;transition:border-color .15s}
.opc:hover{border-color:#3B415A}
.opc b{display:block;font:700 11px var(--body);letter-spacing:.1em}
.opc span{display:block;color:var(--mut);font-size:9.5px;letter-spacing:.07em;margin-top:2px}
.opc.d b{color:#FF8EA0}
@keyframes fade{from{opacity:0}}
@keyframes up{from{transform:translateY(14px);opacity:0}}

.toast{position:fixed;left:50%;bottom:calc(86px + env(safe-area-inset-bottom));z-index:30;transform:translateX(-50%);display:flex;align-items:center;gap:8px;
  padding:10px 16px;border-radius:999px;background:var(--card3);border:1px solid var(--line2);color:var(--text);white-space:nowrap;
  font:700 10px var(--body);letter-spacing:.15em;box-shadow:0 14px 34px -12px rgba(0,0,0,.9);animation:up .2s ease-out}
.toast svg{color:var(--ok)}
.toast.bad svg{color:var(--bad)}

/* entrar */
.login{min-height:100vh;display:flex;flex-direction:column;justify-content:center;max-width:400px;margin:0 auto;padding:24px 16px}
.login .mark{width:58px;height:58px;font-size:30px;border-radius:17px;margin:0 auto}
.login .brand{font-size:30px;text-align:center;margin-top:18px}
.login .brandsub{text-align:center;margin-top:8px}
.err{display:flex;gap:8px;align-items:center;margin-top:12px;color:var(--bad);font:700 10px var(--body);letter-spacing:.1em}
.foot{text-align:center;color:var(--mut2);font-size:9.5px;letter-spacing:.1em;margin-top:16px;line-height:1.6}
.made{display:flex;align-items:center;gap:8px;padding:9px 0;border-bottom:1px dashed var(--line)}
.made:last-child{border-bottom:0}
.resumo{margin-top:14px;padding:10px 12px;border-radius:10px;background:var(--bg2);border:1px dashed var(--line2);color:var(--c2);font-size:10px;letter-spacing:.08em;line-height:1.55}
</style>
</head>
<body>
<div id="app"></div>
<script>
(function () {
  "use strict";
  var KEY = "";
  try { KEY = sessionStorage.getItem("nk") || ""; } catch (e) {}
  var ONLINE = 150000;
  var S = {
    me: null, tab: "live", list: null, live: null, log: null, made: null, off: 0, err: "",
    hist: { tipo: "tudo", key: "", nome: "", items: null, mais: false, carregando: false, q: "", seq: 0 },
    kq: "", kf: "todas", open: {}, logf: "tudo"
  };
  var NOVO = { role: "user", label: "", dias: "30", outro: "", dev: 1, qtd: 1 };
  try { var t0 = sessionStorage.getItem("ntab"); if (t0) S.tab = t0; } catch (e) {}
  var app = document.getElementById("app");

  // ------------------------------------------------------------ elementos
  var NS = "http://www.w3.org/2000/svg";
  var ICO = {
    live: "M2 12h4l3-8 4 16 3-8h6",
    hist: "M3 12a9 9 0 1 0 2.6-6.4M3 4v4.5h4.5M12 7.5V12l3 2",
    key: "M4 15a4 4 0 1 0 8 0a4 4 0 1 0-8 0M10.8 12.2L20 3M17 6l2.5 2.5M14.5 8.5l2 2",
    plus: "M12 5v14M5 12h14",
    list: "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
    out: "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9",
    login: "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3",
    search: "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0M20 20l-3.5-3.5",
    phone: "M9 2h6a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zM11 18h2",
    shield: "M12 3l8 3v6c0 5-3.4 8.2-8 9-4.6-.8-8-4-8-9V6z",
    zap: "M13 2L4 14h7l-1 8 9-12h-7z",
    copy: "M11 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zM5 15h-.5A1.5 1.5 0 0 1 3 13.5v-9A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5V5",
    edit: "M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z",
    ban: "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M5.6 5.6l12.8 12.8",
    unlink: "M9 15l6-6M10 6l1-1a5 5 0 0 1 7.1 7.1l-1 1M14 18l-1 1A5 5 0 0 1 5.9 11.9l1-1",
    trash: "M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14M10 11v5M14 11v5",
    x: "M18 6L6 18M6 6l12 12",
    down: "M12 3v12M7 10l5 5 5-5M5 21h14",
    power: "M12 2v10M18.4 6.6a9 9 0 1 1-12.8 0",
    check: "M20 6L9 17l-5-5",
    undo: "M9 14L4 9l5-5M4 9h11a5 5 0 0 1 0 10h-1",
    app: "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM3 9h18",
    users: "M5 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0M2 21a7 7 0 0 1 14 0M16 3.1a4 4 0 0 1 0 7.8M22 21a7 7 0 0 0-5-6.7",
    more: "M4 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M17 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0",
    dot: "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    clock: "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 7v5l3 2"
  };
  function ico(n, s) {
    var v = document.createElementNS(NS, "svg");
    v.setAttribute("viewBox", "0 0 24 24");
    v.setAttribute("width", s || 18);
    v.setAttribute("height", s || 18);
    v.setAttribute("fill", "none");
    v.setAttribute("stroke", "currentColor");
    v.setAttribute("stroke-width", "1.8");
    v.setAttribute("stroke-linecap", "round");
    v.setAttribute("stroke-linejoin", "round");
    var p = document.createElementNS(NS, "path");
    p.setAttribute("d", ICO[n] || ICO.dot);
    v.appendChild(p);
    return v;
  }
  function add(e, kids) {
    if (kids == null) return;
    if (!Array.isArray(kids)) kids = [kids];
    kids.forEach(function (c) {
      if (c == null || c === false) return;
      if (Array.isArray(c)) return add(e, c);
      e.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
    });
  }
  function h(tag, a, kids) {
    var e = document.createElement(tag);
    if (a) for (var k in a) {
      var v = a[k];
      if (v == null || v === false) continue;
      if (k === "class") e.className = v;
      else if (k === "text") e.textContent = v;
      else if (k === "value") e.value = v;
      else if (k.slice(0, 2) === "on") e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? "" : v);
    }
    add(e, kids);
    return e;
  }
  function chip(cor, ic, txt) {
    return h("span", { class: "chip " + (cor || "") }, [ic === "dot" ? h("span", { class: "dot" }) : ic ? ico(ic, 11) : null, txt]);
  }
  function sec(txt, n) { return h("div", { class: "sec" }, [txt, n != null ? h("b", { text: String(n) }) : null]); }
  function inf(rot, val, mono, cor) {
    return h("div", null, [h("small", { text: rot }), h("span", { class: (mono ? "mono " : "") + (cor || ""), text: val })]);
  }
  function stat(n, l, ic, sub) { return h("div", { class: "stat" }, [ico(ic, 15), h("div", { class: "n", text: String(n == null ? 0 : n) }), h("div", { class: "l", text: l }), sub ? h("div", { class: "l2", text: sub }) : null]); }
  function carregando() { return h("div", { class: "load" }, [h("span", { class: "spin" }), "CARREGANDO"]); }
  function vazio(ic, t, s) { return h("div", { class: "card vazio" }, [ico(ic, 30), h("b", { text: t }), s]); }
  function campoBusca(ph, val, onIn) {
    var i = h("input", { placeholder: ph, value: val, autocomplete: "off", spellcheck: "false" });
    i.addEventListener("input", function () { onIn(i.value); });
    return h("div", { class: "search" }, [ico("search", 16), i]);
  }

  // ------------------------------------------------------------ servidor
  function api(op, body) {
    return fetch("/api/" + op, {
      method: "POST",
      headers: { "content-type": "application/json", "X-Key": KEY },
      body: JSON.stringify(body || {})
    }).then(function (r) {
      return r.json().catch(function () { return { ok: false, reason: "server" }; }).then(function (j) {
        if (!r.ok || j.ok === false) throw new Error(j.reason || ("http " + r.status));
        return j;
      });
    });
  }
  function motivo(r) {
    var m = { denied: "KEY NÃO AUTORIZADA", limit: "MUITAS TENTATIVAS · ESPERE 10 MIN", not_found: "NÃO ENCONTRADO", server: "ERRO NO SERVIDOR" };
    if (/fetch|network/i.test(String(r))) return "SEM CONEXÃO";
    return m[r] || String(r).toUpperCase();
  }
  function falha(e) {
    if (e && e.message === "denied") return sair(true);
    toast(motivo(e && e.message), true);
  }
  function toast(msg, ruim) {
    var old = document.querySelector(".toast");
    if (old) old.remove();
    var t = h("div", { class: "toast" + (ruim ? " bad" : "") }, [ico(ruim ? "ban" : "check", 14), msg]);
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 2000);
  }
  function copiar(s) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(s).then(function () { toast("COPIADO"); }, function () { window.prompt("Copie:", s); });
    } else window.prompt("Copie:", s);
  }

  // ------------------------------------------------------------ tempo
  function agora() { return Date.now() - S.off; }
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function hora(t) { var d = new Date(t); return pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  var DIAS = ["DOMINGO", "SEGUNDA", "TERÇA", "QUARTA", "QUINTA", "SEXTA", "SÁBADO"];
  function diaKey(t) { var d = new Date(t); return d.getFullYear() + "-" + d.getMonth() + "-" + d.getDate(); }
  function diaNome(t) {
    var d = new Date(t), dm = pad(d.getDate()) + "/" + pad(d.getMonth() + 1);
    var hoje = agora();
    if (diaKey(t) === diaKey(hoje)) return ["HOJE", DIAS[d.getDay()] + " · " + dm];
    if (diaKey(t) === diaKey(hoje - 86400000)) return ["ONTEM", DIAS[d.getDay()] + " · " + dm];
    return [DIAS[d.getDay()], dm];
  }
  function data(t) {
    if (!t) return "—";
    var d = new Date(t);
    return pad(d.getDate()) + "/" + pad(d.getMonth() + 1) + "/" + String(d.getFullYear()).slice(2) + " " + hora(t);
  }
  function dur(ms, curto) {
    ms = Math.max(0, ms);
    var m = Math.floor(ms / 60000);
    if (m < 1) return "MENOS DE 1 MIN";
    if (m < 60) return m + " MIN";
    var hh = Math.floor(m / 60), mm = m % 60;
    if (hh < 24) return hh + " H" + (mm && !curto ? " " + mm + " MIN" : "");
    var dd = Math.floor(hh / 24);
    return dd + (dd === 1 ? " DIA" : " DIAS") + (hh % 24 && !curto && dd < 3 ? " " + (hh % 24) + " H" : "");
  }
  function ha(t) {
    if (!t) return "NUNCA";
    var ms = agora() - t;
    if (ms < 60000) return "AGORA";
    return "HÁ " + dur(ms, true);
  }
  function vence(k) {
    if (!k.exp) return "NUNCA";
    var ms = k.exp - agora();
    if (ms <= 0) return "VENCEU";
    return "EM " + dur(ms, true);
  }

  // ------------------------------------------------------------ pessoas
  function nome(r) {
    if (r.key === "OWNER") return "AUCEROLA";
    return (r.label && String(r.label).trim()) || "SEM NOME";
  }
  function iniciais(r) {
    var s = r.key === "OWNER" ? "AUCEROLA" : String(r.label || "").trim();
    if (!s) return String(r.key || "?").replace(/[^A-Z0-9]/gi, "").slice(-2) || "?";
    var p = s.split(/\s+/);
    return ((p[0][0] || "") + (p.length > 1 ? p[p.length - 1][0] : (p[0][1] || ""))).toUpperCase();
  }
  function avatar(r, st, pequeno) {
    return h("div", { class: "av" + (pequeno ? " s" : "") }, [iniciais(r), st != null ? h("span", { class: "st " + st }) : null]);
  }
  function aparelho(r) {
    var s = ((r.brand || "") + " " + (r.model || "")).trim();
    return s || "APARELHO " + String(r.dev || "").slice(0, 6).toUpperCase();
  }
  function papel(role) {
    if (role === "owner") return chip("roxo", null, "DONO");
    if (role === "staff") return chip("info", null, "STAFF");
    return null;
  }
  function lerSt(s) { try { return JSON.parse(s || "{}") || {}; } catch (e) { return {}; } }
  var GAT = { dnd: "NÃO PERTURBE", bt: "BLUETOOTH", carga: "CARREGADOR", volume: "VOLUME" };
  function estado(stStr) {
    var s = lerSt(stStr), out = [];
    if (s.byp) {
      var p = ["BYPASS", GAT[s.gat] || String(s.gat || "").toUpperCase()];
      if (s.ff) p.push(s.ff === "max" ? "FF MAX" : "FF NORMAL");
      if (s.tipo) p.push(String(s.tipo).toUpperCase());
      out.push(chip("ok", "shield", p.filter(Boolean).join(" · ")));
    }
    if (s.apl) out.push(chip("warn", "check", "APLICADO NO JOGO"));
    if (s.comp) out.push(chip("roxo", "zap", "COMPETITIVO"));
    if (s.vol) out.push(chip("roxo", "zap", "TOQUE VIA VOLUME"));
    if (s.carga) out.push(chip("roxo", "zap", "TOQUE VIA CARREGADOR"));
    if (s.mc) out.push(chip("roxo", "zap", "MODO CARREGADOR"));
    if (s.otm) out.push(chip("roxo", "zap", "OTIMIZAÇÃO"));
    if (!out.length) out.push(chip("", null, "NADA LIGADO"));
    return out;
  }
  var EV = {
    bypass_on: ["shield", "ok", "LIGOU O BYPASS"],
    bypass_off: ["power", "", "DESLIGOU O BYPASS"],
    bypass_aplicou: ["check", "warn", "APLICOU O BYPASS NO JOGO"],
    bypass_restaurou: ["undo", "info", "RESTAUROU O JOGO"],
    modo_on: ["zap", "roxo", "LIGOU"],
    modo_off: ["power", "", "DESLIGOU"],
    recurso: ["down", "ok", "BAIXOU OS RECURSOS"]
  };
  function descreve(it) {
    if (it.tipo === "entrada") return { ic: "login", cor: "info", t: "ENTROU COM A KEY", d: "" };
    if (it.tipo === "sessao") return { ic: "app", cor: "", t: "USOU O APP", d: "" };
    var m = EV[it.kind] || ["dot", "", String(it.kind || "").toUpperCase()];
    var t = m[2], d = String(it.detail || "").toUpperCase();
    if ((it.kind === "modo_on" || it.kind === "modo_off") && d) { t = t + " " + d; d = ""; }
    return { ic: m[0], cor: m[1], t: t, d: d };
  }

  // ------------------------------------------------------------ modal
  function modal(titulo, sub, corpo, botoes) {
    var ov = h("div", { class: "ov" });
    function fecha() { ov.remove(); }
    ov.addEventListener("click", function (e) { if (e.target === ov) fecha(); });
    ov.appendChild(h("div", { class: "modal" }, [
      h("div", { class: "row" }, [h("h3", { class: "grow", text: titulo }), h("button", { class: "btn ic g", title: "Fechar", onclick: fecha }, [ico("x", 16)])]),
      sub ? h("div", { class: "lead", text: sub }) : null,
      corpo,
      botoes ? h("div", { class: "acts end", style: "margin-top:18px" }, botoes(fecha)) : null
    ]));
    document.body.appendChild(ov);
    return fecha;
  }
  function confirma(titulo, texto, rotulo, perigo, acao) {
    modal(titulo, texto, null, function (fecha) {
      return [
        h("button", { class: "btn g", text: "CANCELAR", onclick: fecha }),
        h("button", { class: "btn " + (perigo ? "d" : "p"), text: rotulo, onclick: function () { fecha(); acao(); } })
      ];
    });
  }
  function opc(ic, t, s, perigo, fn) {
    return h("button", { class: "opc" + (perigo ? " d" : ""), onclick: fn }, [
      h("span", { class: "ic" + (perigo ? " bad" : "") }, [ico(ic, 16)]),
      h("div", { class: "grow" }, [h("b", { text: t }), h("span", { text: s })])
    ]);
  }

  // ------------------------------------------------------------ carregar
  function carregaVivo() {
    return api("live").then(function (j) {
      S.live = j; S.off = Date.now() - j.now; ONLINE = j.online_ms || ONLINE;
      if (S.tab === "live") render(true); else atualizaNav();
    }).catch(falha);
  }
  function carregaLista() {
    return api("list").then(function (j) {
      S.list = j; S.me = j.me; S.off = Date.now() - j.now;
      if (S.tab === "keys" || S.tab === "new") render(true);
    }).catch(falha);
  }
  function carregaHist(mais) {
    var H = S.hist;
    if (mais && H.carregando) return;
    var pedido = { tipo: H.tipo };
    if (H.key) pedido.key = H.key;
    if (mais && H.items && H.items.length) pedido.antes = H.items[H.items.length - 1].t;
    else { H.items = null; H.mais = false; }
    H.carregando = true;
    var seq = ++H.seq;
    if (S.tab === "hist") render(true);
    api("history", pedido).then(function (j) {
      if (seq !== H.seq) return;
      H.carregando = false;
      H.items = (mais && H.items ? H.items : []).concat(j.items || []);
      H.mais = !!j.mais;
      if (S.tab === "hist") render(true);
    }).catch(function (e) { H.carregando = false; if (!H.items) H.items = []; falha(e); if (S.tab === "hist") render(true); });
  }
  function carregaLog() {
    return api("log").then(function (j) { S.log = j.log || []; if (S.tab === "log") render(true); }).catch(falha);
  }
  function verHist(key, n) {
    S.hist.key = key; S.hist.nome = n; S.hist.tipo = "tudo"; S.hist.q = "";
    irPara("hist", true);
  }

  // ------------------------------------------------------------ telas
  var TABS = [["live", "AO VIVO", "live"], ["hist", "HISTÓRICO", "hist"], ["keys", "KEYS", "key"], ["new", "CRIAR", "plus"], ["log", "REGISTROS", "list"]];
  function irPara(t, recarregaHist) {
    if (t === "log" && S.me.role !== "owner") t = "live";
    S.tab = t;
    try { sessionStorage.setItem("ntab", t); } catch (e) {}
    window.scrollTo(0, 0);
    render();
    if (t === "live") carregaVivo();
    if (t === "keys" || t === "new") carregaLista();
    if (t === "hist" && (recarregaHist || !S.hist.items)) carregaHist(false);
    if (t === "log") carregaLog();
  }
  function topo() {
    var owner = S.me.role === "owner";
    return h("div", { class: "top" }, [
      h("div", { class: "mark", text: "N" }),
      h("div", { class: "grow" }, [h("div", { class: "brand", text: "NOCRY" }), h("div", { class: "brandsub", text: "PAINEL DA STAFF" })]),
      h("span", { class: "chip quem " + (owner ? "roxo" : "info") }, [h("span", { class: "dot" }), h("span", { class: "ell", text: (owner ? "DONO · " : "STAFF · ") + String(S.me.label || "").toUpperCase() })]),
      h("button", { class: "btn ic g", title: "Sair", onclick: function () { confirma("SAIR DO PAINEL?", "VOCÊ VAI PRECISAR COLAR A KEY DE NOVO PRA ENTRAR.", "SAIR", false, function () { sair(false); }); } }, [ico("out", 17)])
    ]);
  }
  var navEl = null;
  function nav() {
    var owner = S.me.role === "owner";
    var on = S.live ? (S.live.online || []).length : 0;
    navEl = h("div", { class: "nav" }, h("div", { class: "in" }, TABS.filter(function (t) { return owner || t[0] !== "log"; }).map(function (t) {
      return h("button", { class: S.tab === t[0] ? "on" : null, onclick: function () { irPara(t[0], t[0] === "hist"); } }, [
        h("span", { class: "ni" }, [ico(t[2], 19), t[0] === "live" && on ? h("b", { class: "nb", text: String(on) }) : null]),
        t[1]
      ]);
    })));
    return navEl;
  }
  function atualizaNav() { var velho = navEl; if (velho && velho.parentNode) velho.parentNode.replaceChild(nav(), velho); }

  function render(manter) {
    if (!S.me) return;
    var y = window.scrollY;
    app.textContent = "";
    var wrap = h("div", { class: "wrap" });
    wrap.appendChild(topo());
    var t = S.tab;
    wrap.appendChild(t === "hist" ? historico() : t === "keys" ? keysView() : t === "new" ? criar() : t === "log" ? registros() : vivo());
    app.appendChild(wrap);
    app.appendChild(nav());
    if (manter) window.scrollTo(0, y);
  }

  // ---- ao vivo
  function vivo() {
    var box = h("div");
    box.appendChild(h("div", { class: "h1", text: "AO VIVO" }));
    box.appendChild(h("div", { class: "lead row" }, [h("span", { class: "pulse" }), h("span", { text: "QUEM ESTÁ COM O APP ABERTO OU RODANDO AGORA · ATUALIZA SOZINHO A CADA 15 S" })]));
    if (!S.live) { box.appendChild(carregando()); return box; }
    var L = S.live, on = L.online || [], st = L.stats || {};
    var fg = on.filter(function (s) { return s.fg; }).length;
    var byp = on.filter(function (s) { return lerSt(s.st).byp; }).length;
    box.appendChild(h("div", { class: "card hero" }, [
      h("div", { class: "lbl", text: "ONLINE AGORA" }),
      h("div", { class: "big" }, [String(on.length), h("small", { text: on.length === 1 ? "PESSOA" : "PESSOAS" })]),
      h("div", { class: "chips" }, [chip("ok", "dot", fg + " COM O APP NA TELA"), chip("warn", "dot", (on.length - fg) + " EM 2º PLANO"), chip("", "shield", byp + " COM BYPASS LIGADO")])
    ]));
    box.appendChild(h("div", { class: "stats" }, [
      stat(st.entradas, "ENTRADAS COM KEY", "login", "ÚLTIMAS 24 H"),
      stat(st.aplicados, "BYPASS APLICADOS", "check", "ÚLTIMAS 24 H"),
      stat(st.pessoas, "APARELHOS QUE USARAM", "users", "ÚLTIMAS 24 H"),
      stat(st.sessoes, "VEZES QUE ABRIRAM O APP", "app", "ÚLTIMAS 24 H")
    ]));
    box.appendChild(sec("ONLINE", on.length));
    if (!on.length) box.appendChild(vazio("live", "NINGUÉM ONLINE AGORA", "QUANDO ALGUÉM ABRIR O APP (VERSÃO 1.0.39 OU MAIS NOVA) APARECE AQUI NA HORA."));
    else box.appendChild(h("div", { class: "grid" }, on.map(cartaoVivo)));
    var vivos = {};
    on.forEach(function (s) { vivos[s.key + "|" + s.dev] = 1; });
    var rec = (L.recentes || []).filter(function (s) { return !vivos[s.key + "|" + s.dev]; });
    if (rec.length) {
      box.appendChild(sec("SAÍRAM NAS ÚLTIMAS 6 H", rec.length));
      box.appendChild(h("div", { class: "grid" }, rec.map(cartaoSaiu)));
    }
    return box;
  }
  function cartaoVivo(s) {
    var owner = S.me.role === "owner";
    return h("div", { class: "card" }, [
      h("div", { class: "pess" }, [
        avatar(s, s.fg ? "on" : "bg"),
        h("div", { class: "grow" }, [
          h("div", { class: "row" }, [h("span", { class: "nome ell", text: nome(s) }), papel(s.role)]),
          h("div", { class: "meta", text: aparelho(s) + (s.android ? " · ANDROID " + s.android : "") + (s.app ? " · APP " + s.app : "") }),
          h("div", { class: "keytxt", text: s.key === "OWNER" ? "KEY DO DONO" : s.key })
        ]),
        chip(s.fg ? "ok" : "warn", "dot", s.fg ? "NA TELA" : "2º PLANO")
      ]),
      h("div", { class: "info" }, [
        inf("ENTROU ÀS", hora(s.start)),
        inf("ON FAZ", dur(agora() - s.start)),
        inf("ÚLTIMO SINAL", ha(s.last)),
        owner ? inf("IP", (s.ip || "?") + (s.country ? " · " + s.country : ""), true) : inf("ID", String(s.dev || "").slice(0, 10), true)
      ]),
      h("div", { class: "chips" }, estado(s.st)),
      h("div", { class: "acts" }, [h("button", { class: "btn", onclick: function () { verHist(s.key, nome(s)); } }, [ico("hist", 14), "VER HISTÓRICO"])])
    ]);
  }
  function cartaoSaiu(s) {
    return h("div", { class: "ev click", onclick: function () { verHist(s.key, nome(s)); } }, [
      avatar(s, "", true),
      h("div", { class: "grow" }, [
        h("div", { class: "tt" }, [h("span", { class: "ell", text: nome(s) }), papel(s.role)]),
        h("div", { class: "sb", text: aparelho(s) + " · FICOU " + dur(s.last - s.start) })
      ]),
      h("div", { class: "hr" }, [hora(s.start) + " → " + hora(s.last), h("small", { text: "SAIU " + ha(s.last) })])
    ]);
  }

  // ---- historico
  var FILTROS = [["tudo", "TUDO"], ["entradas", "ENTRADAS"], ["bypass", "BYPASS"], ["modos", "MODOS"], ["sessoes", "USO DO APP"]];
  function historico() {
    var H = S.hist, box = h("div");
    box.appendChild(h("div", { class: "h1", text: "HISTÓRICO" }));
    box.appendChild(h("div", { class: "lead", text: "QUEM ENTROU, QUEM USOU O BYPASS E OS MODOS E QUANTO TEMPO FICOU NO APP · ÚLTIMOS 30 DIAS" }));
    box.appendChild(h("div", { class: "pills" }, FILTROS.map(function (f) {
      return h("button", { class: "pill" + (H.tipo === f[0] ? " on" : ""), text: f[1], onclick: function () { if (H.tipo === f[0]) return; H.tipo = f[0]; carregaHist(false); } });
    })));
    if (H.key) {
      box.appendChild(h("div", { class: "chips" }, [h("span", { class: "chip info" }, [ico("users", 11), "SÓ DE " + H.nome,
        h("button", { class: "x", title: "Tirar filtro", onclick: function () { H.key = ""; H.nome = ""; carregaHist(false); } }, [ico("x", 12)])])]));
    }
    box.appendChild(campoBusca("BUSCAR NOME, KEY OU APARELHO", H.q, function (v) { H.q = v; desenha(); }));
    var lista = h("div");
    box.appendChild(lista);
    function desenha() {
      lista.textContent = "";
      if (!H.items) { lista.appendChild(carregando()); return; }
      var qq = H.q.trim().toLowerCase();
      var its = H.items.filter(function (it) {
        if (!qq) return true;
        return [nome(it), it.key, it.brand, it.model, it.detail, descreve(it).t].join(" ").toLowerCase().indexOf(qq) >= 0;
      });
      if (!its.length) {
        lista.appendChild(vazio("hist", "NADA POR AQUI", H.items.length ? "NENHUM ITEM BATE COM A BUSCA." :
          "AINDA NÃO TEM REGISTRO DESSE TIPO. O HISTÓRICO DE BYPASS, MODOS E USO DO APP COMEÇA A ENCHER QUANDO O PESSOAL ATUALIZAR PRO APP 1.0.39."));
        return;
      }
      var cont = {};
      its.forEach(function (it) { var k = diaKey(it.t); cont[k] = (cont[k] || 0) + 1; });
      var ult = "";
      its.forEach(function (it) {
        var dk = diaKey(it.t);
        if (dk !== ult) {
          ult = dk;
          var dn = diaNome(it.t);
          lista.appendChild(h("div", { class: "dia" }, [h("b", { text: dn[0] }), dn[1], h("i", { text: cont[dk] + (cont[dk] === 1 ? " ITEM" : " ITENS") })]));
        }
        lista.appendChild(itemHist(it));
      });
      if (H.mais) {
        lista.appendChild(h("button", { class: "btn big", style: "margin-top:14px", disabled: H.carregando, onclick: function () { carregaHist(true); } },
          [H.carregando ? "CARREGANDO..." : "CARREGAR MAIS ANTIGOS"]));
      }
    }
    desenha();
    return box;
  }
  function itemHist(it) {
    var D = descreve(it), owner = S.me.role === "owner";
    var corpo = [h("div", { class: "tt" }, [D.t, papel(it.role)])];
    if (D.d) corpo.push(h("div", { class: "dd", text: D.d }));
    corpo.push(h("div", { class: "sb", text: nome(it) + " · " + aparelho(it) }));
    var dir = [hora(it.t)];
    if (it.tipo === "sessao") {
      var aindaOn = agora() - it.last < ONLINE;
      var ms = it.last - it.start;
      corpo.push(h("div", { class: "sb", text: "DAS " + hora(it.start) + " ATÉ " + (aindaOn ? "AGORA" : hora(it.last)) + (it.app ? " · APP " + it.app : "") }));
      corpo.push(h("div", { class: "bar" + (aindaOn ? " ok" : "") }, [h("i", { style: "width:" + Math.max(4, Math.min(100, ms / 36000)) + "%" })]));
      dir.push(h("small", { text: aindaOn ? "ON AGORA" : dur(ms, true) }));
    }
    if (owner && it.ip) corpo.push(h("div", { class: "sb mono", text: "IP " + it.ip + (it.country ? " · " + it.country : "") }));
    var clicavel = !S.hist.key;
    return h("div", { class: "ev" + (clicavel ? " click" : ""), onclick: clicavel ? function () { verHist(it.key, nome(it)); } : null }, [
      h("span", { class: "ic " + D.cor }, [ico(D.ic, 16)]),
      h("div", { class: "grow" }, corpo),
      h("div", { class: "hr" }, dir)
    ]);
  }

  // ---- keys
  function online(k) {
    var n = agora();
    return (k.devices || []).some(function (d) { return d.last_seen && n - d.last_seen < ONLINE; });
  }
  function situacao(k) {
    if (k.key === "OWNER") return "ativa";
    if (k.revoked) return "revogada";
    if (k.exp && k.exp < agora()) return "vencida";
    return "ativa";
  }
  function keysView() {
    var box = h("div");
    box.appendChild(h("div", { class: "h1", text: "KEYS" }));
    if (!S.list) { box.appendChild(carregando()); return box; }
    var ks = S.list.keys || [];
    var reais = ks.filter(function (k) { return k.key !== "OWNER"; });
    var conta = { todas: ks.length, online: 0, ativa: 0, vencida: 0, revogada: 0, nunca: 0 };
    ks.forEach(function (k) {
      if (online(k)) conta.online++;
      if (k.key !== "OWNER") conta[situacao(k)]++;
      if (!(k.devices || []).length) conta.nunca++;
    });
    box.appendChild(h("div", { class: "lead", text: reais.length + " KEYS · " + conta.ativa + " ATIVAS · " + conta.online + " ONLINE AGORA" }));
    var F = [["todas", "TODAS"], ["online", "ONLINE"], ["ativa", "ATIVAS"], ["vencida", "VENCIDAS"], ["revogada", "REVOGADAS"], ["nunca", "NUNCA USADAS"]];
    box.appendChild(h("div", { class: "pills" }, F.map(function (f) {
      return h("button", { class: "pill" + (S.kf === f[0] ? " on" : ""), onclick: function () { S.kf = f[0]; render(true); } }, [f[1], h("i", { text: String(conta[f[0]]) })]);
    })));
    box.appendChild(campoBusca("BUSCAR KEY, NOME OU APARELHO", S.kq, function (v) { S.kq = v; desenha(); }));
    var lista = h("div", { class: "grid" });
    box.appendChild(lista);
    function desenha() {
      lista.textContent = "";
      var owner = S.me.role === "owner", qq = S.kq.trim().toLowerCase();
      var sel = ks.filter(function (k) {
        var f = S.kf;
        if (f === "online" && !online(k)) return false;
        if ((f === "ativa" || f === "vencida" || f === "revogada") && (k.key === "OWNER" || situacao(k) !== f)) return false;
        if (f === "nunca" && (k.devices || []).length) return false;
        if (!qq) return true;
        var hay = (k.key + " " + nome(k) + " " + (k.created_by || "") + " " + (k.devices || []).map(function (d) {
          return (d.brand || "") + " " + (d.model || "") + (owner ? " " + (d.ip || "") : "");
        }).join(" ")).toLowerCase();
        return hay.indexOf(qq) >= 0;
      });
      if (!sel.length) { lista.appendChild(vazio("key", "NENHUMA KEY", "MUDE O FILTRO OU A BUSCA.")); return; }
      sel.forEach(function (k) { lista.appendChild(keyCard(k)); });
    }
    desenha();
    return box;
  }
  function keyCard(k) {
    var owner = S.me.role === "owner", isO = k.key === "OWNER", devs = k.devices || [];
    var sit = situacao(k), cor = sit === "revogada" ? "bad" : sit === "vencida" ? "warn" : "ok";
    var ult = devs.reduce(function (m, d) { return Math.max(m, d.last_seen || 0); }, 0);
    var on = online(k), aberto = !!S.open[k.key];
    var c = h("div", { class: "card" }, [
      h("div", { class: "pess" }, [
        avatar(k, on ? "on" : ""),
        h("div", { class: "grow" }, [
          h("div", { class: "row" }, [h("span", { class: "nome ell", text: nome(k) }), papel(k.role)]),
          isO ? h("div", { class: "keytxt", text: "SUA KEY DE DONO (FICA ESCONDIDA)" }) :
            h("button", { class: "keycp", title: "Copiar key", onclick: function () { copiar(k.key); } }, [h("span", { class: "keytxt", text: k.key }), ico("copy", 13)])
        ]),
        isO ? null : chip(cor, "dot", sit.toUpperCase())
      ]),
      h("div", { class: "info" }, [
        inf("AGORA", on ? "ONLINE" : "OFFLINE", false, on ? "ok" : ""),
        inf("VISTO", ult ? ha(ult) : "NUNCA USOU"),
        inf("APARELHOS", devs.length + " DE " + k.max_dev),
        inf("VENCE", isO ? "NUNCA" : vence(k))
      ]),
      isO ? null : h("div", { class: "meta", style: "margin-top:9px", text: "CRIADA " + data(k.created_at) + (k.created_by ? " POR " + String(k.created_by).toUpperCase() : "") + (k.exp ? " · VENCE " + data(k.exp) : "") }),
      h("div", { class: "acts linha" }, [
        h("button", { class: "btn f", onclick: function () { verHist(k.key, nome(k)); } }, [ico("hist", 14), "HISTÓRICO"]),
        h("button", { class: "btn f" + (aberto ? " on" : ""), onclick: function () { S.open[k.key] = !aberto; render(true); } }, [ico("phone", 14), "APARELHOS " + devs.length]),
        isO ? null : h("button", { class: "btn ic", title: "Editar", onclick: function () { editar(k); } }, [ico("edit", 15)]),
        h("button", { class: "btn ic", title: "Mais", onclick: function () { mais(k); } }, [ico("more", 15)])
      ])
    ]);
    if (aberto) {
      var dl = h("div", { class: "devs" });
      if (!devs.length) dl.appendChild(h("div", { class: "meta", style: "padding:10px 0", text: "NENHUM APARELHO USOU ESSA KEY AINDA." }));
      devs.forEach(function (d) {
        var don = d.last_seen && agora() - d.last_seen < ONLINE;
        dl.appendChild(h("div", { class: "dev" }, [
          h("div", { class: "row" }, [h("span", { class: "ds" + (don ? " on" : "") }), h("b", { class: "grow ell", text: aparelho(d) }), d.blocked ? chip("bad", "ban", "BLOQUEADO") : null]),
          h("div", { class: "meta", text: "ANDROID " + (d.android || "?") + " · APP " + (d.app || "?") + " · " + (d.n || 0) + " ACESSOS" }),
          h("div", { class: "meta", text: "VISTO " + ha(d.last_seen) + " · PRIMEIRA VEZ " + data(d.first_seen) }),
          h("div", { class: "meta mono", text: (owner ? "IP " + (d.ip || "?") + (d.country ? " (" + d.country + ")" : "") + " · " : "") + "ID " + String(d.dev).slice(0, 10) }),
          h("div", { class: "acts", style: "margin-top:8px" }, [h("button", { class: "btn " + (d.blocked ? "" : "d"), onclick: function () {
            var bloq = !d.blocked;
            var go = function () { api("block", { key: k.key, dev: d.dev, blocked: bloq }).then(function () { toast(bloq ? "APARELHO BLOQUEADO" : "APARELHO LIBERADO"); carregaLista(); }).catch(falha); };
            if (bloq) confirma("BLOQUEAR APARELHO?", aparelho(d) + " NÃO VAI MAIS CONSEGUIR USAR ESSA KEY.", "BLOQUEAR", true, go); else go();
          } }, [ico(d.blocked ? "undo" : "ban", 14), d.blocked ? "DESBLOQUEAR" : "BLOQUEAR APARELHO"])])
        ]));
      });
      c.appendChild(dl);
    }
    return c;
  }
  function mais(k) {
    var isO = k.key === "OWNER", owner = S.me.role === "owner", fecha;
    var itens = [
      isO ? null : opc(k.revoked ? "undo" : "ban", k.revoked ? "REATIVAR KEY" : "REVOGAR KEY",
        k.revoked ? "VOLTA A FUNCIONAR NA HORA" : "PARA DE FUNCIONAR E O APP DESLOGA EM ATÉ 1 MIN", !k.revoked, function () {
          fecha();
          api("revoke", { key: k.key, revoked: !k.revoked }).then(function () { toast(k.revoked ? "KEY REATIVADA" : "KEY REVOGADA"); carregaLista(); }).catch(falha);
        }),
      opc("unlink", "SOLTAR APARELHOS", "LIBERA A KEY PRA SER USADA EM OUTRO CELULAR", false, function () {
        fecha();
        confirma("SOLTAR APARELHOS?", "TODOS OS APARELHOS DESSA KEY SAEM DA LISTA. ELA PODE SER USADA EM OUTRO CELULAR.", "SOLTAR", false, function () {
          api("reset", { key: k.key }).then(function () { toast("APARELHOS SOLTOS"); carregaLista(); }).catch(falha);
        });
      }),
      (owner && !isO) ? opc("trash", "EXCLUIR PARA SEMPRE", "APAGA A KEY, OS APARELHOS E O HISTÓRICO DELA", true, function () {
        fecha();
        confirma("EXCLUIR ESSA KEY?", "NÃO DÁ PRA DESFAZER. A KEY, OS APARELHOS E TODO O HISTÓRICO DELA SÃO APAGADOS.", "EXCLUIR", true, function () {
          api("delete", { key: k.key }).then(function () { toast("KEY EXCLUÍDA"); carregaLista(); }).catch(falha);
        });
      }) : null
    ];
    fecha = modal(nome(k), isO ? "SUA KEY DE DONO" : k.key, h("div", { style: "margin-top:8px" }, itens), null);
  }
  var PRESETS = [["1", "1 DIA"], ["7", "7 DIAS"], ["15", "15 DIAS"], ["30", "30 DIAS"], ["90", "90 DIAS"], ["0", "SEM VENCER"], ["outro", "OUTRO"]];
  function escolheDias(atual, comNaoMudar, onPick) {
    var lista = (comNaoMudar ? [["", "NÃO MUDAR"]] : []).concat(PRESETS);
    return h("div", { class: "pills solto" }, lista.map(function (p) {
      return h("button", { class: "pill" + (atual === p[0] ? " on" : ""), text: p[1], onclick: function () { onPick(p[0]); } });
    }));
  }
  function stepper(val, min, max, onCh) {
    var i = h("input", { type: "number", inputmode: "numeric", value: String(val), min: String(min), max: String(max) });
    function set(v) { v = Math.max(min, Math.min(max, parseInt(v, 10) || min)); i.value = String(v); onCh(v); }
    i.addEventListener("change", function () { set(i.value); });
    return h("div", { class: "step" }, [
      h("button", { class: "btn", text: "−", onclick: function () { set((parseInt(i.value, 10) || min) - 1); } }),
      i,
      h("button", { class: "btn", text: "+", onclick: function () { set((parseInt(i.value, 10) || min) + 1); } })
    ]);
  }
  function editar(k) {
    var owner = S.me.role === "owner";
    var E = { label: k.label || "", dias: "", outro: "", dev: k.max_dev };
    var corpo = h("div");
    function desenha() {
      corpo.textContent = "";
      var lab = h("input", { value: E.label, placeholder: "EX.: JOÃO, GRUPO VIP...", maxlength: "60" });
      lab.addEventListener("input", function () { E.label = lab.value; });
      corpo.appendChild(h("div", { class: "field" }, [h("label", { text: "NOME / ETIQUETA" }), lab]));
      corpo.appendChild(h("div", { class: "field" }, [h("label", { text: "VALIDADE A PARTIR DE HOJE" }), escolheDias(E.dias, true, function (v) { E.dias = v; desenha(); })]));
      if (E.dias === "outro") {
        var o = h("input", { type: "number", inputmode: "numeric", value: E.outro, placeholder: "QUANTOS DIAS" });
        o.addEventListener("input", function () { E.outro = o.value; });
        corpo.appendChild(h("div", { class: "field" }, [o]));
      }
      corpo.appendChild(h("div", { class: "field" }, [h("label", { text: "QUANTOS APARELHOS PODEM USAR" }), stepper(E.dev, 1, owner ? 99 : 5, function (v) { E.dev = v; })]));
      corpo.appendChild(h("div", { class: "resumo", text: "HOJE: " + (k.exp ? "VENCE " + data(k.exp) : "NUNCA VENCE") + " · " + (k.devices || []).length + " DE " + k.max_dev + " APARELHOS EM USO" }));
    }
    desenha();
    modal("EDITAR KEY", k.key, corpo, function (fecha) {
      return [
        h("button", { class: "btn g", text: "CANCELAR", onclick: fecha }),
        h("button", { class: "btn p", text: "SALVAR", onclick: function () {
          var body = { key: k.key, label: E.label, maxDev: E.dev };
          if (E.dias === "outro") { if (String(E.outro).trim() === "") return toast("DIGITE OS DIAS", true); body.days = E.outro; }
          else if (E.dias !== "") body.days = E.dias;
          api("update", body).then(function () { fecha(); toast("SALVO"); carregaLista(); }).catch(falha);
        } })
      ];
    });
  }

  // ---- criar
  function criar() {
    var owner = S.me.role === "owner", box = h("div");
    box.appendChild(h("div", { class: "h1", text: "CRIAR KEYS" }));
    box.appendChild(h("div", { class: "lead", text: "ESCOLHA O TIPO, A VALIDADE E QUANTOS APARELHOS CADA KEY ACEITA" }));
    var c = h("div", { class: "card" });
    if (owner) {
      c.appendChild(h("div", { class: "field", style: "margin-top:0" }, [h("label", { text: "TIPO" }), h("div", { class: "seg" }, [["user", "USUÁRIO"], ["staff", "STAFF"]].map(function (r) {
        return h("button", { class: NOVO.role === r[0] ? "on" : null, text: r[1], onclick: function () { NOVO.role = r[0]; render(true); } });
      }))]));
    }
    var lab = h("input", { value: NOVO.label, placeholder: "EX.: JOÃO, GRUPO VIP...", maxlength: "60" });
    lab.addEventListener("input", function () { NOVO.label = lab.value; atualizaResumo(); });
    c.appendChild(h("div", { class: "field", style: owner ? null : "margin-top:0" }, [h("label", { text: "NOME / ETIQUETA" }), lab]));
    c.appendChild(h("div", { class: "field" }, [h("label", { text: "VALIDADE" }), escolheDias(NOVO.dias, false, function (v) { NOVO.dias = v; render(true); })]));
    if (NOVO.dias === "outro") {
      var o = h("input", { type: "number", inputmode: "numeric", value: NOVO.outro, placeholder: "QUANTOS DIAS" });
      o.addEventListener("input", function () { NOVO.outro = o.value; atualizaResumo(); });
      c.appendChild(h("div", { class: "field" }, [o]));
    }
    c.appendChild(h("div", { class: "row", style: "gap:10px;align-items:flex-start" }, [
      h("div", { class: "field grow" }, [h("label", { text: "APARELHOS POR KEY" }), stepper(NOVO.dev, 1, owner ? 99 : 5, function (v) { NOVO.dev = v; atualizaResumo(); })]),
      h("div", { class: "field grow" }, [h("label", { text: "QUANTAS KEYS" }), stepper(NOVO.qtd, 1, 50, function (v) { NOVO.qtd = v; atualizaResumo(); })])
    ]));
    var resumo = h("div", { class: "resumo" });
    function atualizaResumo() {
      var d = NOVO.dias === "outro" ? (parseInt(NOVO.outro, 10) || 0) : parseInt(NOVO.dias, 10) || 0;
      resumo.textContent = "VAI CRIAR " + NOVO.qtd + (NOVO.qtd === 1 ? " KEY" : " KEYS") + " DE " + (NOVO.role === "staff" ? "STAFF" : "USUÁRIO") +
        (NOVO.label.trim() ? " (" + NOVO.label.trim().toUpperCase() + ")" : "") + " · " + (d > 0 ? "VALE " + d + (d === 1 ? " DIA" : " DIAS") : "NUNCA VENCE") +
        " · " + NOVO.dev + (NOVO.dev === 1 ? " APARELHO" : " APARELHOS") + " CADA";
    }
    atualizaResumo();
    c.appendChild(resumo);
    var bt = h("button", { class: "btn p big", style: "margin-top:14px", onclick: function () {
      var dias = NOVO.dias === "outro" ? NOVO.outro : NOVO.dias;
      if (NOVO.dias === "outro" && String(dias).trim() === "") return toast("DIGITE OS DIAS", true);
      bt.setAttribute("disabled", "");
      api("create", { role: owner ? NOVO.role : "user", label: NOVO.label, days: dias, maxDev: NOVO.dev, count: NOVO.qtd }).then(function (j) {
        S.made = j.keys || []; NOVO.label = ""; render(true); carregaLista(); toast(S.made.length === 1 ? "KEY CRIADA" : S.made.length + " KEYS CRIADAS");
      }).catch(function (e) { bt.removeAttribute("disabled"); falha(e); });
    } }, [ico("plus", 16), "CRIAR"]);
    c.appendChild(bt);
    box.appendChild(c);
    if (S.made && S.made.length) {
      var txt = S.made.join("\n");
      box.appendChild(sec("ACABOU DE CRIAR", S.made.length));
      box.appendChild(h("div", { class: "card" }, [
        S.made.map(function (k) {
          return h("div", { class: "made" }, [h("span", { class: "grow keytxt", style: "margin:0;color:var(--c1)", text: k }), h("button", { class: "btn ic", title: "Copiar", onclick: function () { copiar(k); } }, [ico("copy", 15)])]);
        }),
        h("div", { class: "acts" }, [
          h("button", { class: "btn p", onclick: function () { copiar(txt); } }, [ico("copy", 14), S.made.length === 1 ? "COPIAR" : "COPIAR TODAS"]),
          h("button", { class: "btn g", text: "LIMPAR", onclick: function () { S.made = null; render(true); } })
        ])
      ]));
    }
    return box;
  }

  // ---- registros (so dono)
  var MOT = {
    ok: ["ENTROU", "ok", "login"], renew: ["RENOVOU SOZINHO", "", "undo"], invalid: ["KEY ERRADA", "bad", "ban"],
    revoked: ["KEY REVOGADA", "bad", "ban"], expired: ["KEY VENCIDA", "warn", "clock"], blocked: ["APARELHO BLOQUEADO", "bad", "ban"],
    device_limit: ["LIMITE DE APARELHOS", "warn", "phone"]
  };
  function registros() {
    var box = h("div");
    box.appendChild(h("div", { class: "h1", text: "REGISTROS" }));
    box.appendChild(h("div", { class: "lead", text: "TODAS AS TENTATIVAS DE ENTRAR, CERTAS E ERRADAS, COM IP · SÓ O DONO VÊ" }));
    if (!S.log) { box.appendChild(carregando()); return box; }
    var F = [["tudo", "TUDO"], ["ok", "ENTRADAS"], ["falhas", "FALHAS"], ["renew", "RENOVAÇÕES"]];
    var conta = { tudo: S.log.length, ok: 0, falhas: 0, renew: 0 };
    S.log.forEach(function (r) { if (!r.ok) conta.falhas++; else if (r.reason === "renew") conta.renew++; else conta.ok++; });
    box.appendChild(h("div", { class: "pills" }, F.map(function (f) {
      return h("button", { class: "pill" + (S.logf === f[0] ? " on" : ""), onclick: function () { S.logf = f[0]; render(true); } }, [f[1], h("i", { text: String(conta[f[0]]) })]);
    }).concat([h("button", { class: "pill", onclick: function () { S.log = null; render(true); carregaLog(); } }, [ico("undo", 13), "ATUALIZAR"])])));
    var rows = S.log.filter(function (r) {
      if (S.logf === "ok") return r.ok && r.reason !== "renew";
      if (S.logf === "falhas") return !r.ok;
      if (S.logf === "renew") return r.ok && r.reason === "renew";
      return true;
    });
    if (!rows.length) { box.appendChild(vazio("list", "SEM REGISTROS", "NADA NESSE FILTRO.")); return box; }
    var ult = "";
    rows.forEach(function (r) {
      var dk = diaKey(r.t);
      if (dk !== ult) { ult = dk; var dn = diaNome(r.t); box.appendChild(h("div", { class: "dia" }, [h("b", { text: dn[0] }), dn[1]])); }
      var m = MOT[r.reason] || [String(r.reason || "?").toUpperCase(), r.ok ? "ok" : "bad", "dot"];
      var quem = r.key === "OWNER" ? "AUCEROLA" : r.key === "?" ? "KEY DESCONHECIDA" : (r.label ? String(r.label).toUpperCase() + " · " : "") + r.key;
      box.appendChild(h("div", { class: "ev" }, [
        h("span", { class: "ic " + m[1] }, [ico(m[2], 16)]),
        h("div", { class: "grow" }, [h("div", { class: "tt", text: m[0] }), h("div", { class: "sb mono", text: quem }), h("div", { class: "sb mono", text: "IP " + (r.ip || "?") + " · ID " + (r.dev || "?") })]),
        h("div", { class: "hr" }, [hora(r.t)])
      ]));
    });
    return box;
  }

  // ------------------------------------------------------------ entrar / sair
  function telaLogin() {
    app.textContent = "";
    var inp = h("input", { type: "password", placeholder: "COLE SUA KEY AQUI", autocomplete: "off", spellcheck: "false" });
    var bt = h("button", { class: "btn p big", style: "margin-top:12px" }, [ico("login", 16), "ENTRAR"]);
    function go() {
      var k = inp.value.trim();
      if (!k) return;
      KEY = k;
      bt.setAttribute("disabled", "");
      bt.textContent = "ENTRANDO...";
      api("me").then(function (j) {
        S.me = j; S.err = "";
        try { sessionStorage.setItem("nk", KEY); } catch (e) {}
        inicia();
      }).catch(function (e) { S.err = motivo(e.message); KEY = ""; telaLogin(); });
    }
    inp.addEventListener("keydown", function (ev) { if (ev.key === "Enter") go(); });
    bt.addEventListener("click", go);
    app.appendChild(h("div", { class: "login" }, [
      h("div", { class: "mark", text: "N" }),
      h("div", { class: "brand", text: "NOCRY" }),
      h("div", { class: "brandsub", text: "PAINEL DA STAFF" }),
      h("div", { class: "card", style: "margin-top:26px" }, [
        h("div", { class: "field", style: "margin-top:0" }, [h("label", { text: "KEY DE DONO OU DE STAFF" }), inp]),
        bt,
        S.err ? h("div", { class: "err" }, [ico("ban", 14), S.err]) : null
      ]),
      h("div", { class: "foot", text: "A KEY FICA SALVA SÓ NESTA ABA E SOME QUANDO VOCÊ FECHAR ELA." })
    ]));
    setTimeout(function () { inp.focus(); }, 50);
  }
  function sair(negado) {
    try { sessionStorage.removeItem("nk"); } catch (e) {}
    KEY = ""; S.me = null; S.list = null; S.live = null; S.log = null; S.made = null; S.hist.items = null;
    S.err = negado ? "KEY NÃO AUTORIZADA" : "";
    telaLogin();
  }
  function inicia() {
    if (S.tab === "log" && S.me.role !== "owner") S.tab = "live";
    render();
    carregaVivo();
    if (S.tab === "keys" || S.tab === "new") carregaLista();
    if (S.tab === "hist") carregaHist(false);
    if (S.tab === "log") carregaLog();
  }

  setInterval(function () {
    if (document.hidden || !S.me) return;
    var a = document.activeElement, digitando = a && a.tagName === "INPUT";
    if (document.querySelector(".ov")) return; // nao mexe com modal aberto
    if (S.tab === "live") carregaVivo();
    else if (S.tab === "keys" && !digitando) carregaLista();
  }, 15000);
  document.addEventListener("visibilitychange", function () { if (!document.hidden && S.me && S.tab === "live") carregaVivo(); });

  if (KEY) {
    api("me").then(function (j) { S.me = j; inicia(); }).catch(function () { KEY = ""; telaLogin(); });
  } else telaLogin();
})();
</script>
</body>
</html>`;
