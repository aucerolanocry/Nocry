# Nocry

App Android do **NOCRY SYSTEM**. Faz as mesmas funções do menu do Termux, mas sem abrir o Termux: o ADB já vem dentro do app.

**O que tem:** Modo Competitivo, toque rápido por volume (VOL+ / VOL−), toque pelo carregador (tirou/colocou), Modo Carregador (densidade + abrir o FF), Otimização completa (ver, aplicar, desfazer, proteger apps), Ajustes do toque, Logs e Testar agora.

**Novo na 1.3.1:** ícone novo do app (logo laranja com barras), já no formato adaptativo do Android e com versão monocromática para os ícones temáticos do Android 13+.

**Novo na 1.3 (intro de abertura):** ao abrir o app toca uma **intro HACKER** em tela cheia ("app desenvolvido por aucerola") com música original, em ~8 segundos. **Toque na tela pula.** No **Estúdio → intro de abertura** você escolhe quando toca (sempre, só na 1ª vez depois de instalar/atualizar, ou desligada), liga/desliga o som, **troca a música por uma do seu celular** (escolhe o arquivo, define em que segundo começa) e pode **assistir de novo** quando quiser.

**Novo na 1.2 (visual profissional):** 8 **temas prontos** (Brasa, Cyber, Aurora, Synthwave, Ouro, Matrix, Sakura, Gelo), tudo em **MAIÚSCULO** com fontes novas, 9 fundos animados (Brasas, Grade, Aurora, Sol, Chuva, Neve, Pétalas, Ouro, Noite), degradê com duas cores e ângulo, cartões em 4 estilos (Vidro, Contorno, Sólido, HUD), barra de baixo flutuante, início com modos que você organiza e a aba **Estúdio** para mudar tudo (cores, brilho, borda, fonte, efeitos, vibração).

**Na 1.1:** acesso por **Key** (dono, staff e usuário) e fundo com imagem ou **vídeo da sua própria galeria**, com escurecer e desfocar.

**O que NÃO tem:** nenhum bypass, nenhuma função de anti-cheat e nenhum token/chave do sistema. O app não carrega segredo nenhum.

---

## Para quem só quer usar (baixar)

1. Abra a página **Releases** deste repositório e baixe o `Nocry.apk`.
   Link fixo: `https://github.com/aucerolanocry/Nocry/releases/latest/download/Nocry.apk`
2. Instale (o Android pede para permitir "instalar apps desconhecidos" do navegador).
3. Abra o **Nocry**, digite a sua **Key** e toque em **Entrar** (a tela mostra o que é enviado ao servidor). Depois toque em **Configurar e conectar** e siga os 6 passos (o app guia cada um):
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

Mude o que quiser, suba, e rode o workflow com a versão seguinte (`1.1.1`, `1.3.0`...). No app, **Sobre** avisa quando existe versão mais nova.

---

## Key e servidor (versão 1.1)

O app confere a Key num servidor seu (Cloudflare Worker + D1, pasta `Nocry-servidor`, com passo a passo no `LEIA-ME.md`). O servidor responde com uma assinatura que o app confere com a chave **pública** embutida (`License.kt`); a chave privada fica só no Cloudflare.

- **Dono** (a key `OWNER_KEY`): cria, desativa e apaga qualquer key, vê todos os registros.
- **Staff**: cria e gerencia só keys de usuário.
- **Usuário**: usa o app. Cada key vale para 1 celular (o dono pode liberar mais) e pode ter data de vencimento.
- Registro por entrada: IP (visto pelo servidor), marca e modelo do celular, versão do Android e do app, e um código anônimo do aparelho (hash do ANDROID_ID). Não coleta telefone, localização, contatos nem lista de apps.
- Sem internet o app continua valendo por 3 dias depois da última confirmação.
- O endereço do servidor fica em `app/build.gradle.kts` (variável `apiUrl`). Para trocar sem mexer no código, defina a variável de ambiente `NOCRY_API` no workflow.

**Importante:** como o código do app é público, quem quiser pode recompilar sem a Key. A Key protege quem usa o APK oficial. Para proteger mais, deixe o código-fonte num repositório privado e publique só os APKs num repositório público.

## Limitações

- Precisa de Android 8 (API 26) ou mais novo; o pareamento por código usa a depuração sem fio do Android 11+.
- O desfoque do fundo precisa do Android 12 ou mais novo.
- O app roda comandos de sistema via ADB do próprio aparelho; o efeito de cada ajuste varia por marca/versão do Android.

## Créditos

ADB embutido: [libadb-android](https://github.com/MuntashirAkon/libadb-android) (Apache-2.0 / GPL-3.0).

Fontes: Space Grotesk e JetBrains Mono (licença SIL OFL 1.1, textos em `app/src/main/assets/fonts/`).


---

## Fontes e licenças (1.2)

As fontes ficam em `app/src/main/assets/fonts/`, junto com o texto da licença de cada uma (`OFL-*.txt` e `FONTES-LICENCAS.txt`). Todas usam a licença **SIL Open Font License 1.1**, que permite usar e distribuir dentro de um app: Big Shoulders, Tektur, Outfit, Bricolage Grotesque, Gloock, Work Sans, Instrument Sans, Pixelify Sans, Red Hat Mono, Space Grotesk, JetBrains Mono e Poppins.

---

## Intro (1.3)

O vídeo da intro fica em `app/src/main/res/raw/intro_hacker4.mp4` (animação e trilha originais, feitas para o Nocry; a música é sintetizada, não usa nenhuma faixa de terceiros). Ele toca por cima do app (`Intro.kt`). A música que o usuário escolhe é copiada para a pasta privada do app e nunca sai do celular. Para trocar a intro, substitua esse arquivo por outro `.mp4` com o mesmo nome (de preferência 720x1280, até uns 10 s).
