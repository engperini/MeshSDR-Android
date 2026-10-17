# MeshSDR-Android

> **Estado em 17/10/2026: protótipo experimental, não pronto para uso como decodificador Meshtastic.** A versão inicial recebeu IQ real de um RTL2832U no Android. Depois da introdução do decoder nativo C++/NDK, as execuções **Android APK #38** e **Quality gates #34** aparecem **vermelhas** no GitHub Actions. **Não existe confirmação de compilação, instalação ou decodificação LoRa bem-sucedida para esta versão.**

## Objetivo do produto

Aplicativo Android que usa um dongle USB **RTL2832U (RTL-SDR)** por OTG e o driver RTL-SDR Android existente para **receber continuamente, em tempo real**, sinais LoRa e interpretar pacotes **Meshtastic** (primeira prioridade) e **MeshCore** (segunda prioridade). Mostrar mensagens compreensíveis quando o formato e as chaves permitirem, com logs técnicos separados. O usuário **não possui transmissor/nó LoRa para testes**; por isso a implementação deve ser validada automaticamente com vetores de referência e testes de DSP reproduzíveis, sem exigir instalação a cada tentativa. A gravação IQ é **opcional para diagnóstico**, **não** é a arquitetura do decoder e **não** deve bloquear a implementação.

### Critérios de aceite (não atendidos ainda)

1. CI Android APK **e** Quality gates verdes no mesmo commit.
2. APK instalável e **atualizável por cima** da instalação anterior, com versionCode maior **e mesma assinatura**.
3. Demodulação **em tempo real** de IQ unsigned 8-bit interleaved vindo do `rtl_tcp`, com detecção de preâmbulo, sincronismo, deinterleaving/FEC, CRC e payload.
4. Teste automatizado que gera/reproduz sinais LoRa conhecidos e comprova **bytes exatos** de pelo menos um quadro; testes com vários tamanhos de leitura TCP, SF/BW e ruído.
5. Reconhecimento de cabeçalho Meshtastic e decodificação de protobuf/descriptografia **somente** com chave conhecida/autorizada; distinguir claramente metadados de texto legível.
6. Interface com margens para status/navigation bars, mensagens separadas de logs e sem congelar sob fluxo IQ contínuo.
7. Perfis/frequências corretos e configuráveis para a região/protocolo; MeshCore em etapa posterior.

## O que já funcionou de verdade

- App Android em Kotlin compilou nas CIs anteriores à introdução do NDK; houve execução anterior informada como verde (CI #14).
- **Teste no celular com RTL2832U real**: driver externo abriu via `iqsrc://`, conexão TCP em `127.0.0.1:14423`, cabeçalho `RTL0` recebido e IQ contínuo de aproximadamente **2,05 MB/s** com taxa 1,024 MS/s. A frequência exibida era **906,875 MHz**.
- Interface básica: frequência editável/presets, Conectar/Parar, abas Mensagens/Logs, copiar/limpar. O usuário reportou sobreposição das barras do sistema; código de correção por `WindowInsets` foi publicado, mas **não foi validado em hardware**.
- Testes unitários Java/Kotlin para transporte `rtl_tcp` simulado e interpretação de envelope Meshtastic foram adicionados. **Esses testes não validam o demodulador LoRa**.

## O que foi implementado no código, mas NÃO validado

| Componente | Arquivo | Estado |
| --- | --- | --- |
| Android UI e stream USB/RTL | `app/src/main/java/com/engperini/meshsdr/MainActivity.kt` | Fluxo IQ anterior validado; integração LoRa atual sem build verde |
| Transporte rtl_tcp testável | `app/src/main/java/com/engperini/meshsdr/RtlTcpClient.kt` | Testes com socket simulado; não necessariamente usado na UI |
| Protocolo rtl_tcp | `app/src/main/java/com/engperini/meshsdr/RtlTcpProtocol.kt` | Auxiliar de cabeçalho/comando |
| Parser envelope Meshtastic | `app/src/main/java/com/engperini/meshsdr/MeshtasticAirPacket.kt` | Cabeçalho de rádio; **não** protobuf nem descriptografia |
| Ponte Kotlin/JNI | `app/src/main/java/com/engperini/meshsdr/LoraReceiver.kt` | Publicada, sem CI verde |
| Ponte C++/JNI | `app/src/main/cpp/lora_jni.cpp` | Alimenta decoder em tempo real; não validada |
| DSP LoRa de terceiros | `app/src/main/cpp/upstream/lora_lite.h`, `lora_kiss.h` | Copiado de `mmmaly/LoraReceiverStandalone`; precisa validar licença, compatibilidade e compilação |
| Build C++ | `app/src/main/cpp/CMakeLists.txt` | Android NDK 27.2.12479018; falhando na CI |
| Gravação IQ | `MainActivity.kt` | Botão opcional de 10 s, não é requisito para decodificar |

### Caminho de recepção pretendido

```text
RTL2832U USB OTG
   -> RTL-SDR Driver Android (externo, iqsrc://)
   -> rtl_tcp localhost:14423 (cabeçalho RTL0 + IQ U8 interleaved)
   -> MainActivity (leitura em thread)
   -> LoraReceiver.kt -> JNI lora_jni.cpp
   -> decimação IQ 1 MS/s -> 500 kS/s
   -> lora_lite::Demod (LoRa PHY, perfil inicial SF11/BW250k)
   -> bytes de quadro CRC-ok
   -> MeshtasticAirPacket (envelope, SEM decriptografia)
   -> aba Mensagens
```

**Limitações técnicas importantes:** o código atual configura o DSP **fixo** em SF11/BW250k/CR 4/5/sync 0x2B, embora a frequência seja editável. O sample rate foi alterado de 1.024 MS/s para **1 MS/s** para permitir decimação 2:1; não há validação da precisão da taxa ou do alinhamento de canal. Mudar a frequência no campo **não reconfigura** o perfil LoRa. Não há varredura automática, suporte comprovado a MeshCore, nem decodificação de texto Meshtastic. Os metadados Meshtastic **não são mensagens de texto**. O perfil US LongFast não cobre automaticamente todos os canais ou regiões.

## Situação atual da CI (evidência enviada pelo usuário)

- `Android APK #38` — **falhou**, commit `d36aacd`.
- `Quality gates #34` — **falhou**, mesmo commit.
- Captura mostra **somente status vermelho**; **não mostra a linha do erro de compilação**. Não é correto afirmar que a causa já foi diagnosticada.
- O workflow `android.yml` tenta publicar `MeshSDR-build-log` **mesmo em falha**. Consultar esse artefato e o step **Build native LoRa decoder and Android APK** para a primeira mensagem `error:` / `CMake Error` / `FAILURE`. No `quality.yml`, conferir qual step falhou e os artefatos `MeshSDR-test-reports`.
- Histórico: falhas anteriores de `setup-android` e Lint foram superadas antes da introdução do decoder; não confundir com o problema atual.

[GitHub Actions — todas as execuções](https://github.com/engperini/MeshSDR-Android/actions) · [Android APK](https://github.com/engperini/MeshSDR-Android/actions/workflows/android.yml) · [Quality gates](https://github.com/engperini/MeshSDR-Android/actions/workflows/quality.yml)

## Como reproduzir o build

Ambiente: JDK 17, Android SDK API 35 / build-tools 35.0.0, Android NDK 27.2.12479018, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.0.21. O workflow usa `gradle` instalado por GitHub Actions (não depende de wrapper local).

```bash
gradle :app:testDebugUnitTest --stacktrace
gradle :app:lintDebug --stacktrace
gradle :app:assembleDebug --stacktrace --console=plain
```

APK, **somente se compilar**: `app/build/outputs/apk/debug/app-debug.apk`, artifact `MeshSDR-Android-debug` da workflow Android APK. Não confundir um build de APK verde com a prova de decodificação LoRa.

### Atualização sem desinstalar: pendência crítica

`app/build.gradle.kts` usa `MESHSDR_VERSION_CODE`, e as CIs tentam calculá-lo por `1000 + git rev-list --count HEAD`. **O checkout padrão do Actions é raso (`fetch-depth: 1`)**, portanto a contagem pode permanecer em 1 e **não garantir incremento de versão**. Corrigir com `fetch-depth: 0` e versionamento persistente ou `github.run_number` com política explícita. Além disso, `actions/cache` para `~/.android/debug.keystore` **não garante** que a mesma chave exista entre todos os builds ou em caso de cache miss. A instalação por cima só funciona com **applicationId igual, versionCode superior e assinatura idêntica**. Preferir chave de assinatura fixa armazenada em GitHub Secrets (sem versionar a chave no Git) e verificar fingerprint antes de distribuir. O APK atual **não tem garantia de upgrade in-place**.

### Licenciamento

O núcleo `lora_lite.h`/`lora_kiss.h` foi importado de [mmmaly/LoraReceiverStandalone](https://github.com/mmmaly/LoraReceiverStandalone), derivado de outros projetos LoRa SDR. Verificar licença e atribuições exatas do upstream e cumprir as obrigações aplicáveis antes de distribuir binários. Há nota em `app/src/main/cpp/upstream/README-LICENSE.md`; essa nota **não substitui** a licença completa quando exigida.

## Próximas tarefas — ordem de execução

Veja [TODO.md](TODO.md). A prioridade imediata é **recuperar CI verde sem desativar o decoder** e depois provar demodulação **em tempo real** com teste automatizado de PHY. Não pedir ao usuário que instale repetidamente APKs para descobrir erros de build. Não exigir um dispositivo LoRa de transmissão para implementar/testar o decoder.

## Histórico resumido

1. Criado MVP de UI/driver/rtl_tcp; corrigidas falhas iniciais de configuração de Actions/Lint; CI anterior verde.
2. Usuário testou hardware: `RTL0` e ~2,05 MB/s de IQ real, sem decodificação.
3. Adicionados parser de envelope Meshtastic, testes unitários, margens Android e gravação opcional IQ.
4. Importado núcleo LoRa C++ e integrado via JNI; versão/assinatura de APK tentadas, ainda incompletas.
5. **Regressão:** Android APK #38 e Quality gates #34 falham. Não há demonstração de pacote LoRa decodificado.

**Handoff para outro desenvolvedor/IA:** comece lendo o log da execução #38, resolva a **primeira falha concreta**, mantenha o DSP em tempo real e crie um teste determinístico que prove payload LoRa. Não anuncie sucesso só por um commit ou por um build verde.
