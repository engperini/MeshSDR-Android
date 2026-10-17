# MeshSDR-Android

> **Estado em 17/10/2026: protótipo experimental.** CI **verde** (Android APK #42 e Quality gates #38, PR #1). O decoder LoRa passa em **teste automatizado de bytes exatos** (modulador de referência → IQ u8 do `rtl_tcp` → decoder), mas **ainda não decodificou um pacote real no ar**, e a interpretação Meshtastic se limita ao cabeçalho de 16 bytes (sem protobuf/descriptografia).

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
| Ponte C++/JNI | `app/src/main/cpp/lora_jni.cpp`, `iq_u8.h` | Conversão u8→complexo e decimação 2:1 (média de 2 amostras) testadas no host; JNI compila na CI |
| DSP LoRa de terceiros | `app/src/main/cpp/upstream/lora_lite.h`, `lora_kiss.h` | Copiado de `mmmaly/LoraReceiverStandalone`; bytes exatos em SF7–12 × CR4/5–4/8 × BW125/250k no teste; licença GPL-3.0 ainda a conferir |
| Build C++ | `app/src/main/cpp/CMakeLists.txt` | Android NDK 27.2.12479018; verde na CI |
| Teste PHY LoRa (host) | `app/src/test/cpp/` | Roda no Quality gates via CMake/ctest |
| Gravação IQ e logs | `MainActivity.kt`, `PublicFiles.kt` | Botões "Gravar IQ (10 s)" e "Salvar logs"; arquivos em **Download/MeshSDR** (Android 10+, via MediaStore, sem permissão). Não é requisito para decodificar |

### Caminho de recepção pretendido

```text
RTL2832U USB OTG
   -> RTL-SDR Driver Android (externo, iqsrc://)
   -> rtl_tcp localhost:14423 (cabeçalho RTL0 + IQ U8 interleaved)
   -> MainActivity (leitura em thread)
   -> LoraReceiver.kt -> JNI lora_jni.cpp
   -> decimação IQ 1 MS/s -> 500 kS/s (iq_u8.h)
   -> lora_lite::Demod (LoRa PHY, perfil inicial SF11/BW250k)
   -> bytes de quadro CRC-ok
   -> MeshtasticAirPacket (envelope, SEM decriptografia)
   -> aba Mensagens
```

**Limitações técnicas importantes:** o código atual configura o DSP **fixo** em SF11/BW250k/CR 4/5/sync 0x2B, embora a frequência seja editável. O sample rate foi alterado de 1.024 MS/s para **1 MS/s** para permitir decimação 2:1; não há validação da precisão da taxa ou do alinhamento de canal. Mudar a frequência no campo **não reconfigura** o perfil LoRa. Não há varredura automática, suporte comprovado a MeshCore, nem decodificação de texto Meshtastic. Os metadados Meshtastic **não são mensagens de texto**. O perfil US LongFast não cobre automaticamente todos os canais ou regiões.

## Perfis LoRa (Brasil)

| Perfil | Frequência | Parâmetros | Fonte |
| --- | --- | --- | --- |
| **Meshtastic BR (padrão)** | 919,875 MHz | SF11 / BW250k / CR4/5 / sync 0x2B | Comunidade usa região **ANZ** (915–928 MHz) + LongFast ([Meshtastic Brasil](https://www.meshbrasil.com/faq.html), [SC Mesh](https://www.sc-mesh.org/primeiros-passos/)); 919,875 = slot djb2("LongFast") % 52 do firmware, verificado em `LoraProfileTest` |
| MeshCore BR | 923,125 MHz | SF8 / BW62,5k / CR4/8 | [Mesh Sorocaba](https://www.meshsorocaba.org/configuracao/) (perfil "Australia: SA, WA, Brazil") |
| Meshtastic US | 906,875 MHz | SF11 / BW250k / CR4/5 / sync 0x2B | Região US |

A documentação oficial do Meshtastic também lista `BR_902` (902–907,5 MHz), mas a comunidade usa ANZ. O decoder **aceita qualquer sync word** e informa o detectado (0x2B Meshtastic, 0x12 rede privada, 0x34 LoRaWAN). A aba Mensagens mostra **detecções** (cabeçalho LoRa válido), quadros com CRC inválido e quadros decodificados.

O sintonizador fica **250 kHz acima** do canal para tirar o pico DC do RTL2832U de cima do sinal; `iq_u8.h` desloca o canal de volta a 0 Hz e decima (2× para BW250k, 8× para BW62,5k). O ganho é enviado ao `rtl_tcp` ao conectar (padrão 40,2 dB) e o log mostra nível RMS e saturação a cada 2 s.

## Teste automatizado do PHY LoRa

`app/src/test/cpp/lora_tx.h` é um **modulador LoRa de referência** escrito de forma independente do decoder (cadeia do gr-lora_sdr: whitening, cabeçalho explícito, CRC, Hamming, interleaver diagonal, Gray inverso, chirps com preâmbulo, sync word e 2,25 downchirps). `lora_phy_test.cpp` gera quadros conhecidos e exige os **bytes exatos** na saída do decoder:

| Caso | O que prova |
| --- | --- |
| tables | Whitening e CRC do TX batem com a tabela/CRC do decoder (check value CRC-16/XMODEM `0x31C3`) |
| clean, max-payload | LongFast (SF11/BW250k/CR4/5/sync 0x2B) pelo caminho real: IQ u8 1 MS/s → `U8Decimator` → decoder; 40 e 255 bytes |
| reads | 3 quadros seguidos, leituras TCP aleatórias incluindo 1–7 bytes (I separado de Q) |
| meshcore-br | SF8/BW62,5k/CR4/8/sync 0x12 por 1 MS/s u8 com decimação 8 |
| detect-only | Quadro com payload destruído gera detecção de cabeçalho e **nunca** um falso "decodificado" |
| ppm+noise, weak, cheap-xtal | Erro de cristal ±3 a ±25 ppm (até ±22,7 kHz, com deriva de clock correspondente) e SNR de +10 a −10 dB na banda |
| matrix | 48 combinações SF7–12 × CR4/5–4/8 × BW125/250k (com LDRO quando aplicável) |
| noise | 5 s de ruído não geram quadro nem detecção falsa; mede velocidade (≈88× tempo real no runner x86) |

Os testes foram verificados por mutação (sync word errado e um bit invertido no TX fazem o teste falhar). Rodar localmente:

```bash
cmake -S app/src/test/cpp -B build-host -DCMAKE_BUILD_TYPE=Release
cmake --build build-host && ctest --test-dir build-host --output-on-failure
```

**Limites:** o canal é sintético (AWGN + ppm); não há multipercurso, interferência nem distorção do R820T. O decoder escuta um perfil por vez (escolhido na tela). Todos os casos usam sintonia deslocada e pico DC simulado. Desempenho em ARM no celular não foi medido.

## Como reproduzir o build

Ambiente: JDK 17, Android SDK API 35 / build-tools 35.0.0, Android NDK 27.2.12479018, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.0.21. O workflow usa `gradle` instalado por GitHub Actions (não depende de wrapper local).

```bash
gradle :app:testDebugUnitTest --stacktrace
gradle :app:lintDebug --stacktrace
gradle :app:assembleDebug --stacktrace --console=plain
```

APK, **somente se compilar**: `app/build/outputs/apk/debug/app-debug.apk`, artifact `MeshSDR-Android-debug` da workflow Android APK. Não confundir um build de APK verde com a prova de decodificação LoRa.

### Atualização sem desinstalar (assinatura fixa)

Todo build (CI ou local) é assinado com a chave pública de teste `app/meshsdr-debug.jks` (alias `meshsdr`, senha `meshsdr-public`), versionada no repositório de propósito: não há segredos neste projeto e qualquer pessoa pode recompilar com a mesma assinatura. O `versionCode` é `1000 + git rev-list --count HEAD`, com checkout completo (`fetch-depth: 0`), portanto cresce a cada commit. O step **Show APK version and signing certificate** imprime `versionCode` e o SHA-256 do certificado, que deve ser sempre `85:3E:23:FB:7D:32:D9:55:8C:2F:25:D2:8F:03:4F:E4:75:4C:8B:29:70:D1:0B:82:8C:E5:E0:EA:DF:3F:C2:B2`. APKs assinados antes desta mudança têm outra chave: desinstale uma única vez; daí em diante a instalação por cima funciona (`adb install -r app-debug.apk`).

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
