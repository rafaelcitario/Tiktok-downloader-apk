# Baixador HQ

App Android (Kotlin) que baixa vídeos do TikTok em alta qualidade, sem marca d'água.
Feito para o Galaxy A55 (Android 14/15), mas roda em qualquer Android 12 ou mais novo.

## Como funciona

1. Você toca em **Copiar link** no TikTok.
2. Aparece um pop-up: **"Baixar este vídeo em alta qualidade?"**
3. **Tocar** no pop-up baixa o vídeo. **Arrastar para o lado** descarta.
4. O vídeo cai na galeria, em `Filmes/Baixador HQ`, e uma notificação deixa abrir ou compartilhar.

Um serviço em primeiro plano mantém o app vivo 24h e volta sozinho depois de reiniciar o celular.

## Outras formas de baixar (funcionam sempre)

- No TikTok: **Compartilhar > Baixador HQ** (baixa direto).
- Botão **Colar e baixar** na notificação fixa do app.
- Atalho **Baixar link** no painel rápido (arraste o painel, toque no lápis e adicione).
- Botão **Colar link e baixar** dentro do app.

## Extras

- **Só o áudio** (MP3/M4A) pelo botão do pop-up.
- **Baixar sem perguntar**: ao copiar o link, baixa direto.
- **Somente em Wi-Fi**.
- **Histórico** com abrir e excluir; avisa quando você já baixou aquele vídeo.
- Duas fontes de download: se a primeira falhar, tenta a página do próprio TikTok.

## Gerar o APK

### Pelo GitHub (não precisa instalar nada)

1. Crie um repositório no GitHub e envie **todo o conteúdo desta pasta** (inclusive a pasta oculta `.github`).
2. Abra a aba **Actions**, escolha **Build APK** e toque em **Run workflow** (ou espere o build do próprio envio).
3. Em uns 5 a 8 minutos, o APK aparece em **Releases > Baixador HQ (APK)** (`BaixadorHQ.apk`).
4. Baixe no celular e instale (permita "instalar apps desta fonte" quando o Android pedir).

### Pelo Android Studio

Abra esta pasta, aguarde a sincronização e use **Build > Build APK(s)**.
O arquivo fica em `app/build/outputs/apk/debug/app-debug.apk`.

## Configurar depois de instalar (uma vez)

Abra o app e resolva os avisos amarelos:

1. **Notificações**: permitir (sem isso o pop-up não aparece).
2. **Bateria sem restrições**: permitir. Na Samsung, adicione também em
   Configurações > Bateria > Limites de uso em segundo plano > Apps que nunca entram em suspensão.
3. **Serviço de acessibilidade**: ativar "Baixador HQ". Se o Android disser "configuração restrita":
   Configurações > Apps > Baixador HQ > menu ⋮ > **Permitir configurações restritas**, e tente de novo.

## Por que o serviço de acessibilidade

Desde o Android 10, só o app que está na tela pode ler a área de transferência. Por isso um app comum
**não consegue** perceber um link copiado em segundo plano. O serviço de acessibilidade contorna isso
do jeito mais discreto possível:

- só recebe eventos do app do TikTok;
- olha apenas o nome do botão tocado (por exemplo "Copiar link"), em memória, sem guardar nada;
- nesse momento abre uma janela invisível de 1 pixel por uns 100 ms, lê a cópia e fecha;
- não lê a tela e não toca em nada.

O Android 12+ pode mostrar "Baixador HQ colou da área de transferência". É esperado.

## Limites conhecidos

- A detecção automática só vale para links copiados **dentro do TikTok**. Para links copiados em outros apps
  (WhatsApp, navegador), use o botão da notificação, o atalho do painel rápido ou o botão dentro do app.
- Não existe API oficial do TikTok para baixar vídeos. O app usa o serviço público tikwm.com e, se ele falhar,
  a página do próprio TikTok. Se o TikTok mudar algo, pode ser preciso atualizar `TikTokResolver.kt`.
- Posts de fotos (carrossel) não têm vídeo: dá para baixar só o áudio.
- O APK gerado é de depuração (assinado com a chave de depuração do Android). Serve para uso pessoal.

Baixe apenas vídeos seus ou que o criador permite baixar.
