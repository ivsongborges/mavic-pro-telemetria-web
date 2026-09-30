# Firebase — telemetria na nuvem (opcional)

Recebe a telemetria do app Android via HTTPS (sem precisar do PC ligado na mesma rede) e mostra
um dashboard ao vivo, hospedado no Firebase Hosting. Usa só o plano gratuito **Spark** (sem cartão
de crédito): Realtime Database + Authentication (anônima) + Hosting.

```
Mavic Pro ──rádio──> Controle remoto ──USB──> Celular (app) ──HTTPS/internet──> Firebase Realtime Database
                                                                                          │
                                                                          Firebase Hosting (dashboard ao vivo)
```

O app continua enviando 10 registros/s por UDP local (se você usar) e grava tudo no celular;
para a nuvem, ele manda por padrão **~1 registro/s** (ajustável em `CloudUploader.java`,
`setUploadRateHz`), para não estourar as cotas gratuitas do Firebase.

## 1. Criar o projeto no Firebase
Isso precisa ser feito por você — exige login na sua conta Google, que eu não posso fazer por aqui.

1. Acesse <https://console.firebase.google.com> → **Adicionar projeto**. Pode desativar o Google Analytics (não é necessário).
2. No menu lateral, **Build → Realtime Database → Criar banco de dados**. Escolha uma região e comece
   em modo **bloqueado** (as regras deste repositório substituem o padrão no passo 4).
3. **Build → Authentication → Sign-in method → Anônimo → Ativar.** É o que o app e o dashboard usam
   para poder gravar/ler sem senha.
4. **Build → Hosting → Começar.** Não precisa seguir o assistente de CLI ainda; isso é feito no passo 3 abaixo.

## 2. Registrar o app Android no Firebase
1. Na página inicial do projeto (⚙ **Configurações do projeto → Seus apps → Adicionar app → Android**).
2. **Nome do pacote Android:** `com.dji.mavictelemetry` (tem que ser idêntico ao `applicationId` do
   `android/app/build.gradle`).
3. Baixe o **`google-services.json`** gerado e salve em `android/app/google-services.json`
   (o `.gitignore` já ignora esse arquivo — ele identifica o seu projeto Firebase).
4. Recompile o app no Android Studio. Se o arquivo estiver no lugar certo, o Gradle ativa o plugin
   do Firebase sozinho (veja o comentário em `android/app/build.gradle`).

## 3. Publicar o dashboard (Firebase Hosting)
No seu computador (não precisa ser o mesmo do receptor local):

```bash
npm install -g firebase-tools
firebase login
```

`firebase login` abre o navegador para você entrar com a sua conta Google — é uma etapa que só você
pode fazer.

Depois, dentro da pasta `firebase/`:

```bash
cp .firebaserc.example .firebaserc
# edite .firebaserc e troque SEU-PROJECT-ID-AQUI pelo ID do projeto (⚙ Configurações do projeto)

cp public/firebase-config.example.js public/firebase-config.js
# edite public/firebase-config.js com os valores de:
# ⚙ Configurações do projeto → Seus apps → app Web (se ainda não existir, clique em "</>" para criar um) → SDK setup

firebase deploy --only hosting,database
```

O comando publica o dashboard e envia as regras de segurança (`database.rules.json`). A URL final
aparece no terminal, algo como `https://SEU-PROJETO.web.app`.

## 4. Usar
1. No app Android, ative o interruptor **"Enviar para a nuvem (Firebase)"**. O status abaixo dele mostra
   se conectou.
2. Abra a URL do Hosting: o dropdown **Voo** lista as sessões enviadas (mais recente primeiro),
   com posição, bateria e estado de voo ao vivo no mapa e nos painéis.
3. **Exportar CSV** baixa o histórico do voo selecionado.

## Modelo de dados (Realtime Database)
```
/flights/{flightId}/meta/{startedAt, device}
/flights/{flightId}/live/{...último snapshot...}
/flights/{flightId}/log/{pushId}/{...snapshot no momento...}
```
`flightId` é gerado pelo app (`yyyyMMdd_HHmmss`) toda vez que você liga o interruptor de nuvem.

## Regras de segurança
`database.rules.json` exige `auth != null` para ler ou escrever em `/flights`. Qualquer pessoa com o
app instalado (ou o link do dashboard) consegue logar anonimamente e ler/escrever, então isso é
adequado para uso pessoal, mas **não é uma trava forte** — o `apiKey` do Firebase não é secreto por
design (fica embutido no app/site), a segurança real vem das regras. Para restringir mais:
- troque a autenticação anônima por e-mail/senha e ajuste as regras para checar `auth.uid` específico; ou
- ative o [Firebase App Check](https://firebase.google.com/docs/app-check) para exigir que só o seu
  app/site (não qualquer cliente com a mesma config) consiga gravar.

## Problemas comuns
- **"Firebase não configurado" no app**: falta `android/app/google-services.json` (passo 2), ou o
  `applicationId` do `build.gradle` não bate com o package cadastrado no Firebase.
- **"Falha no login anônimo"**: autenticação anônima não foi ativada no console (passo 1.3).
- **Dashboard fica em "conectando à nuvem..."**: confira `public/firebase-config.js` — `databaseURL`
  e `projectId` precisam ser exatamente os do seu projeto; sem esse arquivo a página mostra
  "faltando firebase-config.js".
- **Voo não aparece no dropdown**: o app só cria o registro do voo (`meta`) depois de logar no
  Firebase; espere alguns segundos após ligar o interruptor de nuvem. As regras exigem
  `auth != null` — confira se a autenticação anônima está ativa.
- **`firebase deploy` pede billing/Blaze**: só acontece se você tentar usar Cloud Functions (não
  usadas aqui). Hosting + Realtime Database + Authentication anônima funcionam no plano Spark.
- **Gradle reclama de "uses-sdk:minSdkVersion ... cannot be smaller than version ... declared in
  library"** (o merge do `AndroidManifest.xml` falha silenciosamente, sem gerar APK e sem erro
  óbvio na tela): o SDK do Firebase para Android exige `minSdkVersion 23`. O projeto já usa 23
  por causa disso (`android/app/build.gradle`) — isso deixa de fora só Android 5.0/5.1 (Lollipop).
  Se você reverter para 21 achando que é mais compatível, o build volta a travar nesse ponto.

## Custos
Plano Spark (gratuito), sem cartão cadastrado: Realtime Database 1 GB armazenado / 10 GB baixados por
mês, Hosting 10 GB de transferência/mês, Authentication sem limite prático para este uso. A ~1
escrita/s, um voo de 20 min grava ~1200 pontos pequenos (poucos KB) — folgado dentro da cota gratuita.
Se quiser manter os 10 Hz também na nuvem, ajuste `CloudUploader.setUploadRateHz(10)` no código, mas
aí o consumo cresce proporcionalmente.
