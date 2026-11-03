export default {
    async fetch(request, env) {
        const url = new URL(request.url);
            const arquivo = url.pathname.replace('/', '');

                if (!arquivo) {
                      return new Response("Arquivo nao especificado", { status: 400 });
                          }

                              const headers = {
                                    'Authorization': `Bearer ${env.GITHUB_TOKEN}`,
                                          'Accept': 'application/vnd.github.v3+json',
                                                'User-Agent': 'Cloudflare-Worker'
                                                    };

                                                        const resReleases = await fetch('https://api.github.com/repos/aucerolanocry/ff-installer/releases', { headers });

                                                            if (!resReleases.ok) {
                                                                  return new Response("Erro ao buscar releases no GitHub", { status: 500 });
                                                                      }

                                                                          const releases = await resReleases.json();
                                                                              let assetId = null;

                                                                                  for (const release of releases) {
                                                                                        if (release.assets) {
                                                                                                const asset = release.assets.find(a => a.name === arquivo);
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
                                                                                                                                                                                  'Authorization': `Bearer ${env.GITHUB_TOKEN}`,
                                                                                                                                                                                          'Accept': 'application/octet-stream',
                                                                                                                                                                                                  'User-Agent': 'Cloudflare-Worker'
                                                                                                                                                                                                        },
                                                                                                                                                                                                              redirect: 'follow'
                                                                                                                                                                                                                  });

                                                                                                                                                                                                                      return new Response(response.body, {
                                                                                                                                                                                                                            headers: {
                                                                                                                                                                                                                                    'Content-Type': 'application/octet-stream',
                                                                                                                                                                                                                                            'Content-Disposition': `attachment; filename="${arquivo}"`
                                                                                                                                                                                                                                                  }
                                                                                                                                                                                                                                                      });
                                                                                                                                                                                                                                                        }
                                                                                                                                                                                                                                                        }
