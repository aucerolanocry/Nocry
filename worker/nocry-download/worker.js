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

async function chaveValida(env, keyHeader) {
  const key = normKey(keyHeader);
  if (!/^[A-Z0-9-]{4,64}$/.test(key)) return false;

  const ok = ownerKey(env);
  if (ok !== "" && ctEq(key, ok)) return true;

  if (!env.DB) return false; // sem banco configurado, nega por seguranca
  const rec = await env.DB.prepare("SELECT revoked, exp FROM keys WHERE key = ?").bind(key).first();
  if (!rec) return false;
  if (rec.revoked) return false;
  if (rec.exp && rec.exp < Date.now()) return false;
  return true;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const arquivo = url.pathname.replace("/", "");

    if (!arquivo) {
      return new Response("Arquivo nao especificado", { status: 400 });
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

    const resReleases = await fetch("https://api.github.com/repos/aucerolanocry/ff-installer/releases", { headers });
    if (!resReleases.ok) {
      return new Response("Erro ao buscar releases no GitHub", { status: 500 });
    }

    const releases = await resReleases.json();
    let assetId = null;
    for (const release of releases) {
      if (release.assets) {
        const asset = release.assets.find((a) => a.name === arquivo);
        if (asset) {
          assetId = asset.id;
          break;
        }
      }
    }
    if (!assetId) {
      return new Response("Arquivo nao encontrado no GitHub", { status: 404 });
    }

    const githubUrl = `https://api.github.com/repos/aucerolanocry/ff-installer/releases/assets/${assetId}`;
    const response = await fetch(githubUrl, {
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/octet-stream",
        "User-Agent": "Cloudflare-Worker",
      },
      redirect: "follow",
    });

    return new Response(response.body, {
      headers: {
        "Content-Type": "application/octet-stream",
        "Content-Disposition": `attachment; filename="${arquivo}"`,
      },
    });
  },
};
