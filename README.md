# XC60 Telemetria

App Android (Kotlin + Jetpack Compose) que transforma um tablet ou celular em computador de bordo e central de mídia para o **Volvo XC60 1ª geração (2009–2017, plataforma P3)**. Ele usa um **ELM327 Bluetooth** na porta OBD2 e manda o som para a multimídia original pelo Bluetooth, tudo ao mesmo tempo.

## Como as duas conexões Bluetooth convivem

```
                 ┌──────────── A2DP (som) ─────────────► Multimídia original XC60
 Tablet Android ─┤
                 └──────────── SPP (serial) ───────────► ELM327 na porta OBD2 ──► HS-CAN 500k
```

- São perfis Bluetooth diferentes e o Android mantém os dois ligados juntos.
- Os dois disputam o mesmo rádio. Por isso o app:
  - pede até 6 sensores numa única requisição, o que reduz o tráfego;
  - lê os sensores rápidos (RPM, velocidade, turbo, acelerador) a cada ciclo e os lentos em rodízio;
  - tem uma **pausa ajustável entre requisições** (Config). Se a música engasgar, aumente.

## O que o app faz

| Tela | Função |
|---|---|
| **Início** | Mostradores de velocidade e rotação, barras de turbo, água, óleo, bateria e tanque, computador de bordo, música tocando e atalhos de navegação. |
| | Em tela dividida com Waze ou Google Maps, muda sozinho para uma versão vertical compacta. |
| **Mídia** | Escolha da fonte (Spotify, YouTube Music, Deezer, YouTube). Mostra música, artista, capa e progresso, com controles. |
| | Para mostrar a música, é preciso liberar "Acesso a notificações" uma vez. O app só lê o que está tocando. |
| **Conforto** | Clima (motorista/passageiro), vidros, teto solar, cortina, travas e porta-malas. **Funciona apenas no modo demonstração** (veja Segurança). |
| **Diagnóstico** | Sensores: todos os PIDs que a central suporta. |
| | Falhas: lê motor e câmbio e apaga as falhas do motor, com confirmação. |
| | Ferramentas Volvo: varredura de DIDs e console somente leitura. |
| **Ajustes** | Adaptador, modo demonstração, combustível, pausa entre requisições, CSV e status. |

## Segurança

O app **não pode mexer em nada que afete o funcionamento ou o desempenho do carro**. Isso é garantido no código, não só na interface.

- **Trava no núcleo (`SafeCommand` + `Elm327.request`):** todo comando para o carro passa por ela, e só saem serviços de leitura.
  - **Bloqueado:** escrita de configuração, rotinas, reset de central, controle de atuadores, acesso de segurança e programação.
- **Apagar falhas (modo 04):** é a única exceção. Só pode ser usado pelo botão próprio, com confirmação, e nunca pelo console.
- **Conforto:** aceita apenas uma lista fechada de ações. Porta-malas e destravar exigem velocidade 0.
  - No carro real fica **desligado**. No XC60 P3 essas funções estão na rede MS-CAN, que o ELM327 genérico não alcança, e os comandos da central de conforto (CEM) são proprietários.
  - Para ativar seriam necessários um adaptador MS-CAN e comandos confirmados no próprio carro, nunca comandos "chutados".

A conexão roda num **serviço em primeiro plano**: continua com a tela apagada ou com outro app aberto (Waze, Spotify) e reconecta sozinha quando o carro é religado.

## Como abrir e instalar

1. Instale o **Android Studio** (versão Ladybug ou mais nova).
2. *File → Open* e escolha a pasta `xc60-telemetria`. O Android Studio baixa o Gradle 8.9 e as dependências.
3. No tablet, ative a **Depuração USB** (Opções do desenvolvedor), ligue no PC e clique em ▶ Run.

Requisitos: Android 8.0 ou mais novo. O app abre sempre na horizontal.

## Configuração no carro

1. Pareie o tablet com a **multimídia do XC60** (como sempre, para o som).
2. Pareie o **ELM327** nas configurações de Bluetooth do Android. O PIN costuma ser `1234` ou `0000`.
3. Abra o app, vá em **Config**, escolha o ELM327 e toque em **Conectar**. Faça isso com a ignição ligada.
4. Em **Config → Combustível**, escolha *Gasolina comum (E27)* para o T5/T6 com gasolina de posto.

## Limites do ELM327 genérico no Volvo P3

O P3 tem várias redes CAN. A porta OBD dá acesso a:

- **HS-CAN 500 kbit/s (pinos 6/14):** motor, câmbio, ABS. ✅ Funciona com ELM327 genérico.
- **MS-CAN 125 kbit/s (pinos 3/11):** ar-condicionado, painel, portas, conforto. ❌ O clone **não alcança**. Precisa de um adaptador com chaveamento de rede, como OBDLink MX+ ou vLinker MC+.

Outros pontos:

- **Consumo:** é calculado pelo fluxo de ar (MAF), considerando mistura estequiométrica. Pode errar alguns % e, em freio-motor (corte de injeção), mostra consumo onde ele é zero. Confira o valor uma vez com o tanque cheio e ajuste se precisar.
- **Diesel (D5):** o consumo só aparece se a central informar o PID 5E.
- **Temperatura do óleo (PID 5C):** nem toda central P3 informa. O app esconde os sensores que a central não suporta.

## Encontrando os sensores próprios da Volvo

O app **não traz uma tabela pronta de DIDs da Volvo**. Eles variam conforme motor, câmbio e versão de software, e um valor errado mostra dado falso no painel. Para descobrir os do seu carro:

1. **Volvo → Motor 7E0 → DID 0000 a 00FF → Varrer.** Depois tente outras faixas, como `F100–F1FF` (identificação) e `1000–1FFF`.
2. Marque **monitorar** nos DIDs que responderam.
3. Mude uma condição no carro (acelerar, esquentar o motor, engatar marcha) e veja quais bytes mudam (ficam azuis).
4. Compare com um valor conhecido e chegue à fórmula. Exemplo: um byte que acompanha a temperatura da água com +40 de diferença usa a fórmula `A - 40`.
5. Cadastre o sensor em `obd/Pids.kt`. O próximo passo natural é um `VolvoPids.kt` com consulta via serviço 22.

> Faça a varredura com o **carro parado**. Ela só lê dados, mas pausa o painel enquanto roda.

## Estrutura do código

```
app/src/main/java/br/xc60/telemetria/
├── MainActivity.kt              navegação lateral + permissões
├── obd/
│   ├── ObdTransport.kt          Bluetooth SPP (com fallbacks para clones)
│   ├── Elm327.kt                comandos AT, cabeçalhos, remontagem ISO-TP
│   ├── Pids.kt                  sensores padrão + leitura de várias PIDs
│   └── Diagnostics.kt           DTCs + trava de segurança do console
├── service/
│   ├── TelemetryService.kt      serviço em primeiro plano + reconexão
│   ├── TelemetryEngine.kt       laço de leitura, computador de bordo, CSV, ferramentas
│   └── Settings.kt              preferências e tipos de combustível
├── media/MediaControls.kt       controle do player e volume (A2DP)
└── ui/                          telas Compose
```

## Próximos passos sugeridos

- **Capa e nome da música:** precisa da permissão de *acesso a notificações* (`MediaSessionManager`).
- **Adaptador BLE** (vLinker BM, OBDLink CX): criar uma segunda implementação de `ObdTransport`.
- **Sensores Volvo confirmados** (`VolvoPids.kt`), depois da descoberta pela varredura.
- **Modo "abrir ao ligar o carro":** iniciar o app quando o Bluetooth do carro conectar.

## Atualizações (GitHub Releases)

O app procura versão nova ao abrir e a cada 6 horas em `github.com/dhiogosb/XC60Telemetria/releases`. Quando acha, mostra uma faixa no topo:

1. **Baixar.** O APK é baixado e conferido pelo `SHA256SUMS.txt`.
2. **Instalar.** Só aparece com o carro parado. Na primeira vez, o Android pede para permitir que o app instale atualizações.

Também dá para procurar na hora em **Ajustes → Geral → Procurar atualização**.

### Publicar uma versão nova

1. Em `app/build.gradle.kts`, aumente `versionCode` e `versionName` (ex.: 6 e `0.6.0`).
2. Compile a release assinada:
   ```powershell
   .\gradlew.bat assembleRelease
   ```
3. Monte os arquivos e publique:
   ```bash
   mkdir -p dist && cp app/build/outputs/apk/release/app-release.apk dist/XC60Telemetria-0.6.0.apk
   (cd dist && sha256sum XC60Telemetria-0.6.0.apk > SHA256SUMS.txt)
   gh release create v0.6.0 dist/XC60Telemetria-0.6.0.apk dist/SHA256SUMS.txt --title "v0.6.0" --notes "O que mudou"
   ```

> **Guarde a pasta `signing/` com backup** (fora do Git). Ela tem a chave que assina o app. Se for perdida, as versões novas não instalam por cima: o Android recusa APK assinado por outra chave, e todo mundo teria que desinstalar e instalar de novo.
