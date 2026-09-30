# Mavic Pro — Telemetria em tempo real (DJI Mobile SDK V4)

O projeto tem duas partes independentes, que **não precisam ser usadas juntas**:

```
                                          ┌──Wi-Fi/UDP (mesma rede)──> PC local (receiver/, veja abaixo)
Mavic Pro ──rádio──> RC ──USB──> Celular (android/) ─┤
                                          └──HTTPS/internet──────────> Firebase (firebase/, veja abaixo)
```

O Mavic Pro (original) **só é suportado pelo Mobile SDK V4**; o MSDK V5 não o suporta.
O SDK V4 roda no Android/iOS, então o celular conectado ao controle remoto por USB é a ponte:
o app lê os estados do drone a 10 Hz e pode enviá-los por dois caminhos, ao mesmo tempo ou
só um deles:

1. **`android/` + `receiver/`** — envio local por UDP para um script Python no PC, na mesma
   rede Wi-Fi. Sem depender de internet além do 1º registro do SDK. Ver seção
   [Envio local (UDP)](#envio-local-udp) abaixo.
2. **`android/` + `firebase/`** — envio por HTTPS para o Firebase (Realtime Database),
   com um dashboard hospedado na nuvem, acessível de qualquer lugar. Ver
   [`firebase/README.md`](firebase/README.md).

## Estrutura
- `android/` — app Android (Java, SDK 4.16.4): registra o SDK, lê a telemetria e envia por UDP
  local e/ou para o Firebase (opcional).
- `receiver/receiver.py` — receptor UDP no PC; mostra no terminal, grava `.jsonl` + `.csv` em `logs/` e serve o dashboard.
- `receiver/webserver.py` + `receiver/web/index.html` — servidor HTTP/SSE e a página do dashboard (mapa, instrumentos).
- `tools/simulate.py` — simulador de voo para testar o dashboard sem o drone.
- `firebase/` — configuração do Firebase Hosting + Realtime Database: dashboard web na nuvem,
  alternativa ao `receiver/` que não depende do PC estar ligado nem na mesma rede. Opcional —
  veja [`firebase/README.md`](firebase/README.md) para o passo a passo completo.

## Envio local (UDP)
### Requisitos
- Android Studio (Hedgehog ou mais novo) e JDK 11+ (o Android Studio já traz).
- Celular Android 6.0+ (API 23 — exigido pelo SDK do Firebase, mesmo se você só usar o envio local),
  com cabo USB do controle remoto (Lightning/micro-USB/USB-C, conforme o RC).
- Conta em <https://developer.dji.com> → *Apps* → *Create App*:
  - Tipo: **Android SDK**, package name: `com.dji.mavictelemetry` (ou o que você usar em `applicationId`).
  - Copie o **App Key**.
- Python 3.8+ no PC (sem dependências).

### Passo a passo
1. Crie `android/local.properties` (além do `sdk.dir` que o Android Studio gera):
   ```
   DJI_APP_KEY=seu_app_key_aqui
   ```
2. Abra a pasta `android/` no Android Studio, aguarde o Gradle sync (o wrapper `.jar` é gerado automaticamente).
3. Ligue o celular ao PC com depuração USB e rode o app (▶). O **primeiro registro exige internet** no celular.
4. No PC, descubra o IP (`ipconfig`) e rode, com o PC e o celular na **mesma rede Wi-Fi**:
   ```bash
   python receiver/receiver.py --port 14550
   ```
   Libere a porta UDP 14550 no Firewall do Windows se necessário.
5. Ligue o drone e o controle remoto, e conecte o RC ao celular por USB (o app abre sozinho; aceite a permissão de acessório).
6. No app: informe o IP do PC e a porta, toque em **Iniciar envio**.

> O cabo USB ocupa a porta do celular; use Wi-Fi (ou hotspot do PC) para o envio. Não é necessário o app oficial DJI GO 4 — feche-o para não disputar a conexão.
>
> O app também tem um interruptor **"Enviar para a nuvem (Firebase)"**, independente deste envio
> local — os dois podem ficar ligados ao mesmo tempo. Ver [`firebase/README.md`](firebase/README.md).

### Dashboard web e mapa
Ao rodar o `receiver.py`, ele também serve um dashboard em **<http://localhost:8080>** (só biblioteca padrão do Python).
- **Mapa** (OpenStreetMap, com alternância para satélite Esri): posição do drone em tempo real, ícone girando com o rumo,
  trilha do voo, ponto de decolagem (H), "Seguir drone", "Centralizar" e "Limpar trilha".
- **Painéis:** altitude, velocidades, distância até o ponto de decolagem, bateria (células, corrente, potência),
  satélites/GPS, horizonte artificial, bússola, link de rádio, sticks do RC, obstáculos, alertas,
  gráficos dos últimos 5 min e tabela com todos os campos.
- Status "Ao vivo / Sem dados há N s", pacotes recebidos e perdidos.
- O navegador precisa de internet (Leaflet via CDN e tiles do mapa); a telemetria em si é local.

Opções: `--web-port 8080` (`0` desativa), `--web-host 127.0.0.1`. Para abrir em outro dispositivo da rede
(ex.: tablet), use `--web-host 0.0.0.0` e acesse `http://IP-DO-PC:8080` (libere a porta no firewall; não há autenticação).

### Replay de voos salvos
Clique em **Replay** no topo do dashboard para revisar um voo gravado (`.csv` ou `.jsonl`):
- escolha um log da lista (pasta `logs/` do receptor), clique em **Abrir arquivo…** ou **arraste o arquivo** para a página;
- a **barra de progresso** leva a qualquer instante do voo; há também play/pause, ±5 s e velocidade de 0,5× a 30×;
- o mapa mostra o voo inteiro tracejado e a trilha já percorrida; todos os painéis refletem o instante escolhido;
- os gráficos passam a mostrar o **voo completo**; clique neles para saltar para aquele ponto;
- atalhos: `Espaço` reproduz/pausa, `←`/`→` voltam/avançam 5 s (com `Shift`, 30 s).

Enquanto está em Replay a telemetria ao vivo é ignorada (mas continua sendo gravada); volte com **Ao vivo**.
O receptor só cria os arquivos de log quando chega o primeiro pacote, então abrir só para revisar voos não gera arquivos vazios.

**Testar sem o drone:** em outro terminal, `python tools/simulate.py` simula um voo circular e alimenta o dashboard.

### Onde ficam os logs
Por padrão em `<projeto>/logs/` (ex.: `C:\Code\mavic-telemetry\logs\telemetry_AAAAMMDD_HHMMSS.csv` e `.jsonl`),
não importa de onde o comando é executado. Para outra pasta: `--out C:\Dados\voos`.
O `.jsonl` guarda tudo o que chegou; o `.csv` tem as colunas fixas de `FIELDS` em `receiver.py`.

## Campos enviados
Mesmos campos nos dois caminhos (JSON). No UDP local, 1 objeto por datagrama a 10 Hz; na nuvem
(Firebase), por padrão ~1 objeto/s (ver [`firebase/README.md`](firebase/README.md#custos)).
| Grupo | Campos |
|---|---|
| Tempo | `ts` (ms epoch) |
| GPS / posição | `lat`, `lon`, `alt` (m, rel. decolagem), `sats`, `gps_level`, `home_set`, `home_lat`, `home_lon`, `takeoff_alt` |
| Velocidade | `vx`, `vy`, `vz` (m/s, NED), `speed_h` (horizontal), `speed_3d` |
| Atitude / bússola | `pitch`, `roll`, `yaw`, `head_dir`, `compass_heading`, `compass_error` |
| Estado de voo | `flight_mode`, `is_flying`, `motors_on`, `flight_time_s`, `flight_count`, `ultrasonic_m`, `ultrasonic_used`, `vision_pos_used`, `imu_preheating` |
| Bateria | `bat_pct`, `bat_v_mv`, `bat_a_ma`, `bat_temp_c`, `bat_remaining_mah`, `bat_full_mah`, `bat_design_mah`, `bat_discharges`, `bat_life_pct`, `cell1_mv`..`cell4_mv`, `cell_delta_mv` |
| Retorno à base / avisos | `wind_warning`, `going_home`, `gohome_state`, `gohome_height`, `remaining_flight_s`, `time_to_home_s`, `bat_needed_home_pct`, `max_radius_home_m`, `bat_low_warn`, `bat_serious_warn`, `max_height_reached`, `max_radius_reached` |
| Obstáculos (m) | `obs_nose`, `obs_tail`, `obs_right`, `obs_left` (distância mínima por sensor de visão) |
| Link de rádio | `uplink_pct`, `downlink_pct` |
| Controle remoto | `rc_lx`, `rc_ly`, `rc_rx`, `rc_ry` (-660..660), `rc_left_dial`, `rc_flight_switch`, `rc_gohome_btn`, `rc_pause_btn` |
| Gimbal | `gimbal_pitch`, `gimbal_roll`, `gimbal_yaw` |

Campos que o drone/RC não reportar ficam vazios no CSV. **Rotação dos motores (RPM) não é exposta pelo SDK V4.**
Para adicionar campos, edite `TelemetryStreamer.java` (callbacks) e `FIELDS` em `receiver.py`.

## Problemas comuns
- **App fecha sozinho ao abrir**: se `targetSdkVersion` em `android/app/build.gradle` estiver 33+,
  o SDK V4 da DJI (2022) crasha ao registrar (`SecurityException: RECEIVER_EXPORTED ...`) — ele
  registra um `BroadcastReceiver` do jeito antigo, e o Android exige a flag explícita a partir do
  target 33. Mantenha `targetSdkVersion 30` (não precisa ser igual ao `compileSdkVersion`, que pode
  ficar mais novo). Para ver a causa de um crash: `adb logcat -d | grep -A 40 "FATAL EXCEPTION"`
  com o celular conectado por USB.
- **Registro falha**: package name diferente do cadastrado, App Key errado em `local.properties`, ou sem internet.
- **Fica preso em "Conecte o controle remoto por USB" mesmo com o cabo plugado**: quase sempre é o
  **DJI GO 4 instalado no celular** roubando a conexão — só um app pode segurar o acessório USB do
  RC por vez, e os dois usam o mesmo filtro (`manufacturer="DJI" model="com.dji.sdk"`), então o
  Android entrega para qualquer um dos dois que "ganhar". Fechar o DJI GO 4 pela tela de recentes
  **não é suficiente** (o Android ainda consegue acordá-lo pela conexão USB); force-pare-o em
  *Configurações → Apps → DJI GO 4 → Forçar parada* (ou desinstale) **antes** de conectar o cabo, e
  abra o Mavic Telemetry primeiro. Para confirmar qual app pegou a conexão, com o celular ligado ao
  PC por `adb`: `adb logcat -d | grep -i USB_ACCESSORY_ATTACHED` mostra o `cmp=` (componente) que
  foi aberto.
- **Produto não conecta** (outras causas): cabo USB só de carga, ou RC desligado/despareado do drone.
- **Sem dados no PC**: IP errado, redes diferentes, firewall bloqueando UDP, ou o app em segundo
  plano sendo limitado pelo Android — veja a exceção de otimização de bateria pedida pelo app.
- **Nuvem**: veja a seção "Problemas comuns" em [`firebase/README.md`](firebase/README.md).
- Se o Gradle reclamar de versão do AGP/JDK, ajuste `android/build.gradle`.
