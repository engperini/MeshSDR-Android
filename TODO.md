# TODO — MeshSDR-Android

Este documento é um plano de execução para continuidade por outro desenvolvedor/IA. **Não marque tarefas como concluídas sem evidência.**

## P0 — recuperar a CI e APK (CI verde no PR #1)

- [ ] Abrir **Android APK #38** e **Quality gates #34**; registrar no commit/PR o primeiro erro real (não apenas `exit code 1`).
- [ ] Corrigir build CMake/NDK/JNI e eventuais falhas Kotlin/Lint **sem retirar o decoder**. Verificar compatibilidade de `lora_lite.h` com SF11/SF12 e tamanhos de buffer.
- [ ] Garantir que `android.yml` e `quality.yml` fiquem verdes **no mesmo SHA**; manter artefatos de diagnóstico.
- [x] Corrigir versionCode: checkout com histórico completo (`fetch-depth: 0`).
- [x] Assinatura estável com chave pública de teste versionada (`app/meshsdr-debug.jks`); imprimir SHA-256 do certificado no log (falta validar upgrade no aparelho).
- [ ] Documentar upgrade do APK com `adb install -r` e verificar mesmo `applicationId`, assinatura e versão.

## P1 — função principal: LoRa PHY ao vivo

- [ ] Revisar e adaptar o decoder upstream de forma correta, mantendo fluxo **RTL TCP → IQ → JNI → LoRa PHY → bytes** contínuo. Gravação raw é **somente opcional**.
- [ ] Revisar `LORA_LITE_MAX_SF=12`, `LORA_LITE_MAX_PAYLOAD=255`, alocação de `Demod`, possíveis truncamentos `uint8_t`, CRC e desempenho em ARM64.
- [x] Criar gerador/teste determinístico com quadros LoRa conhecidos (`app/src/test/cpp`), verificando sync, SF/BW/CR, payload exato e CRC.
- [x] Testar streaming em blocos TCP de tamanho variável, incluindo byte I separado de Q e múltiplos pacotes seguidos.
- [ ] Medir consumo de CPU **no celular** (no host x86: ≈88× tempo real), memória e perda de amostras a 1 MS/s; não bloquear UI nem perder dados silenciosamente.
- [x] Perfis LoRa BR (Meshtastic ANZ LongFast padrão, MeshCore BR) e US; sync word livre. Falta validar no ar.
- [ ] Mostrar métricas: IQ MB/s, quadros detectados, CRC-ok/erro, perda de amostras e erros de decoder.

## P2 — protocolo Meshtastic

- [ ] Validar parser do envelope de 16 bytes com vetores reais e versões atuais do protocolo.
- [ ] Interpretar payload protobuf Meshtastic; distinguir `encrypted` de `decoded`.
- [ ] Implementar decriptação **apenas** quando chave pública/padrão ou chave fornecida/autorizada estiver disponível.
- [ ] Exibir texto, nó, canal, ID, hora, SNR/RSSI quando disponíveis, sem fabricar mensagens.
- [ ] Cobrir formatos/presets Meshtastic relevantes; não assumir que toda atividade em 906.875 MHz usa LongFast.

## P3 — MeshCore e experiência Android

- [ ] Implementar parser MeshCore e perfis de rádio específicos, com testes próprios.
- [ ] Validar margens superior/inferior em Android 15/16 e rotação.
- [~] Serviço em primeiro plano + wake lock mantém a recepção com a tela apagada; falta mover o leitor para o serviço (sobreviver ao fechamento da Activity) e reconexão USB/TCP.
- [ ] Melhorar UI: separação clara mensagens/logs, scroll, exportação e persistência de configurações.
- [ ] Validar hardware real apenas **após** CI e testes DSP verdes; usuário tem RTL2832U, mas **não** possui transmissor LoRa.

## Referências e estado

- Repo: https://github.com/engperini/MeshSDR-Android
- CI: https://github.com/engperini/MeshSDR-Android/actions
- Núcleo LoRa importado: https://github.com/mmmaly/LoraReceiverStandalone
- Android APK #38 e Quality gates #34: vermelhos no print enviado em 17/10/2026.
- README: [visão geral, arquivos e problemas conhecidos](README.md).
