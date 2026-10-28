# Nocry

App Android do **NOCRY SYSTEM**. Faz as mesmas funções do menu do Termux, mas sem abrir o Termux: o ADB já vem dentro do app.

**O que tem:** Modo Competitivo, toque rápido por volume (VOL+ / VOL−), toque pelo carregador (tirou/colocou), Modo Carregador (densidade + abrir o FF), Otimização completa (ver, aplicar, desfazer, proteger apps), Ajustes do toque, Logs e Testar agora.

**O que NÃO tem:** nenhum bypass, nenhuma função de anti-cheat e nenhum token/chave do sistema. O app não carrega segredo nenhum.

---

## Para quem só quer usar (baixar)

1. Abra a página **Releases** deste repositório e baixe o `Nocry.apk`.
   Link fixo: `https://github.com/aucerolanocry/Nocry/releases/latest/download/Nocry.apk`
2. Instale (o Android pede para permitir "instalar apps desconhecidos" do navegador).
3. Abra o **Nocry**, toque em **CONFIGURAR / CONECTAR** e siga os 6 passos (o app guia cada um):
   - ligar **Depuração sem fio** nas Opções do desenvolvedor (precisa estar no Wi-Fi);
   - **Parear** com código (o app mostra uma notificação para digitar o código, sem sair da tela);
   - **Conectar**;
   - **Permissões** (o app se dá sozinho a permissão de ajustes seguros via ADB);
   - ligar o **Acessibilidade → Nocry** (é ele que percebe o aperto do volume);
   - notificações e bateria sem restrição.
4. Depois disso é só usar os menus.

**Atenção:** a depuração sem fio do Android desliga quando troca de Wi-Fi ou reinicia o celular. Quando isso acontecer, é só ligar de novo (o pareamento fica salvo, não precisa refazer).

No Android 13 ou mais novo, se a opção de Acessibilidade aparecer cinza: Configurações → Apps → Nocry → ⋮ → **Permitir configurações restritas**.

---

## Para quem publica (você, dono do repositório)

### 1. Criar o repositório público

Crie um repositório **público** chamado `Nocry` e suba **o conteúdo desta pasta** (tudo, incluindo `.github`, `gradlew` e a pasta `gradle`).
Se o GitHub não subir a pasta `.github` (pasta com ponto na frente às vezes é ignorada no envio pelo site), crie o arquivo na mão: **Add file → Create new file**, nome `.github/workflows/build-apk.yml`, e cole o conteúdo.
Se o nome do usuário/repositório for diferente de `aucerolanocry/Nocry`, troque a linha `REPO` em `app/src/main/java/com/nocry/app/Core.kt` (é o que o app usa para checar atualização e montar o link de compartilhar).

### 2. Chave de assinatura (uma vez só)

A chave fixa faz com que cada versão nova **instale por cima** da anterior sem desinstalar. Ela fica nos *Secrets* do GitHub, **nunca** no repositório (que é público).

No repositório: **Settings → Secrets and variables → Actions → New repository secret**, e crie 4:

| Nome | Valor |
|---|---|
| `KEYSTORE_B64` | o conteúdo do arquivo `nocry.jks.b64` |
| `KEYSTORE_PASS` | `KEYSTORE_PASS` do arquivo `segredos.txt` |
| `KEY_ALIAS` | `nocry` |
| `KEY_PASS` | `KEY_PASS` do arquivo `segredos.txt` |

Guarde o `nocry.jks` num lugar seguro. Se perder a chave, quem já instalou precisa desinstalar para instalar uma versão assinada com outra chave. Sem os secrets o build ainda funciona, mas assina com chave de teste (aparece um aviso amarelo no Actions).

### 3. Gerar e publicar o APK

- **Pelo site:** aba **Actions → Gerar APK do Nocry → Run workflow**, coloque a versão (ex.: `1.0.0`) e deixe "Publicar" marcado.
- **Ou por tag:** crie a tag `v1.0.0` (Releases → Draft a new release → escolher/criar tag `v1.0.0` → Publish). O workflow compila e anexa o `Nocry.apk`.

Leva uns 5 a 10 minutos. Quando terminar, o APK aparece em **Releases** e o link `releases/latest/download/Nocry.apk` já funciona para qualquer pessoa.

### 4. Atualizar

Mude o que quiser, suba, e rode o workflow com a versão seguinte (`1.0.1`, `1.1.0`...). No app, **Sobre e atualização** avisa quando existe versão mais nova.

---

## Limitações da versão 1

- Não tem login nem chave de acesso: quem baixar usa tudo.
- Precisa de Android 8 (API 26) ou mais novo; o pareamento por código usa a depuração sem fio do Android 11+.
- O app roda comandos de sistema via ADB do próprio aparelho; o efeito de cada ajuste varia por marca/versão do Android.

## Créditos

ADB embutido: [libadb-android](https://github.com/MuntashirAkon/libadb-android) (Apache-2.0 / GPL-3.0).
