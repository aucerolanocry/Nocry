// =====================================================================
//  NOCRY · servidor de download (ff-installer)
//  So entrega arquivo pra quem tiver uma key valida (ativa, nao revogada,
//  nao vencida) no mesmo banco D1 usado pelo Worker "nocry". O segredo
//  (GITHUB_TOKEN) fica so nas configuracoes do Worker.
// =====================================================================

function normKey(k) {
  return String(k == null ? "" : k).trim().toUpperCase().replace(/\s+/g, "");
}

function ctEq(a, b) {
  const x = new TextEncoder().encode(String(a));
  const y = new TextEncoder().encode(String(b));
  let d = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) d |= (x[i] || 0) ^ (y[i] || 0);
  return d === 0;
}

function ownerKey(env) {
  const k = normKey(env.OWNER_KEY || "");
  return k.length >= 8 ? k : "";
}

async function sha256hex(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return Array.from(new Uint8Array(d)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// key de dono trocada pelo app: o worker "nocry" guarda o sha256 dela no mesmo
// banco D1 (tabela meta, chave "owner_key_hash"). Sem checar isso aqui, uma
// troca de key de dono (OWNER_KEY) nao era reconhecida por esse servidor de
// download e a pessoa levava 401 mesmo com a key nova certa.
async function donoTrocado(env) {
  if (!env.DB) return "";
  try {
    const r = await env.DB.prepare("SELECT v FROM meta WHERE k = 'owner_key_hash'").first();
    return r && r.v ? String(r.v) : "";
  } catch (e) {
    return "";
  }
}

async function chaveValida(env, keyHeader) {
  const key = normKey(keyHeader);
  if (!/^[A-Z0-9-]{4,64}$/.test(key)) return false;

  const h = await donoTrocado(env);
  if (h) {
    if (await ctEq(await sha256hex(key), h)) return true;
  } else {
    const ok = ownerKey(env);
    if (ok !== "" && ctEq(key, ok)) return true;
  }

  if (!env.DB) return false; // sem banco configurado, nega por seguranca
  const rec = await env.DB.prepare("SELECT revoked, exp FROM keys WHERE key = ?").bind(key).first();
  if (!rec) return false;
  if (rec.revoked) return false;
  if (rec.exp && rec.exp < Date.now()) return false;
  return true;
}

const espera = (ms) => new Promise((r) => setTimeout(r, ms));

// lista de releases guardada na memória do servidor por 10 min: menos perguntas pro GitHub = menos "HTTP 500"
let memo = { t: 0, releases: null };
let saude = { t: 0, r: null };

function acharId(releases, arquivo) {
  for (const release of releases || []) {
    const asset = (release.assets || []).find((a) => a.name === arquivo);
    if (asset) return asset.id;
  }
  return null;
}

async function acharAsset(headers, arquivo) {
  if (memo.releases && Date.now() - memo.t < 600000) {
    const id = acharId(memo.releases, arquivo);
    if (id) return { id };
  }
  // o GitHub às vezes falha por um instante: tenta 3 vezes antes de desistir
  let res = null;
  for (let t = 0; t < 3; t++) {
    res = await fetch("https://api.github.com/repos/aucerolanocry/ff-installer/releases?per_page=100", { headers });
    if (res.ok || res.status < 500) break;
    await espera(500 * (t + 1));
  }
  if (!res.ok) {
    // GitHub fora, mas a lista guardada (mesmo velha) ainda serve
    const velho = memo.releases ? acharId(memo.releases, arquivo) : null;
    return velho ? { id: velho } : { erro: res.status };
  }
  const releases = await res.json();
  memo = { t: Date.now(), releases };
  const id = acharId(releases, arquivo);
  return id ? { id } : { erro: 404 };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const arquivo = url.pathname.replace("/", "");

    if (!arquivo) {
      return new Response("Arquivo nao especificado", { status: 400 });
    }

    // checagem de saúde (sem segredo nenhum): o servidor consegue falar com o GitHub e baixar um pedacinho?
    if (arquivo === "saude") {
      if (saude.r && Date.now() - saude.t < 60000) return new Response(JSON.stringify(saude.r), { headers: { "Content-Type": "application/json" } });
      const h = { Authorization: `Bearer ${env.GITHUB_TOKEN}`, Accept: "application/vnd.github.v3+json", "User-Agent": "Cloudflare-Worker" };
      const r = { token: !!env.GITHUB_TOKEN };
      try {
        const rel = await fetch("https://api.github.com/repos/aucerolanocry/ff-installer/releases?per_page=100", { headers: h });
        r.releases = rel.status;
        r.limite_restante = rel.headers.get("x-ratelimit-remaining");
        if (rel.ok) {
          const lista = await rel.json();
          memo = { t: Date.now(), releases: lista };
          const id = acharId(lista, "android.zip");
          r.achou_android_zip = !!id;
          if (id) {
            const a = await fetch(`https://api.github.com/repos/aucerolanocry/ff-installer/releases/assets/${id}`,
              { headers: { Authorization: h.Authorization, Accept: "application/octet-stream", "User-Agent": "Cloudflare-Worker", Range: "bytes=0-15" }, redirect: "follow" });
            r.pedacinho = a.status;
            r.content_range = a.headers.get("Content-Range");
            try { await a.arrayBuffer(); } catch (e) {}
          }
        }
      } catch (e) {
        r.erro = String(e && e.message ? e.message : e).slice(0, 120);
      }
      saude = { t: Date.now(), r };
      return new Response(JSON.stringify(r), { headers: { "Content-Type": "application/json" } });
    }

    const keyHeader = request.headers.get("X-Key") || "";
    if (!(await chaveValida(env, keyHeader))) {
      return new Response("Key invalida, revogada ou vencida", { status: 401 });
    }

    const headers = {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github.v3+json",
      "User-Agent": "Cloudflare-Worker",
    };

    const achado = await acharAsset(headers, arquivo);
    if (!achado.id) {
      if (achado.erro === 404) return new Response("Arquivo nao encontrado no GitHub", { status: 404 });
      return new Response("Erro ao buscar releases no GitHub", { status: 503 });
    }

    // repassa o "continuar de onde parou" (Range) pro GitHub: a internet caiu no meio e o app não recomeça do zero
    const h2 = {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: "application/octet-stream",
      "User-Agent": "Cloudflare-Worker",
    };
    const range = request.headers.get("Range");
    if (range) h2.Range = range;

    const githubUrl = `https://api.github.com/repos/aucerolanocry/ff-installer/releases/assets/${achado.id}`;
    let response = null;
    for (let t = 0; t < 3; t++) {
      response = await fetch(githubUrl, { headers: h2, redirect: "follow" });
      if (response.ok || response.status < 500) break;
      await espera(500 * (t + 1));
    }

    if (response.status === 416) {
      const cr = response.headers.get("Content-Range");
      return new Response(null, { status: 416, headers: cr ? { "Content-Range": cr } : {} });
    }
    if (response.status !== 200 && response.status !== 206) {
      // antes ia "200" com o texto do erro dentro, e o app salvava isso como se fosse a pasta
      return new Response("Erro ao baixar o arquivo do GitHub", { status: 502 });
    }

    // tamanho do arquivo vai junto: a barra de progresso do app mostra a porcentagem
    const out = new Headers({
      "Content-Type": "application/octet-stream",
      "Content-Disposition": `attachment; filename="${arquivo}"`,
      "Accept-Ranges": "bytes",
    });
    for (const k of ["Content-Length", "Content-Range", "ETag", "Last-Modified"]) {
      const v = response.headers.get(k);
      if (v) out.set(k, v);
    }
    return new Response(response.body, { status: response.status, headers: out });
  },
};
