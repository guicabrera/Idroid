# Idroid

Compartilha arquivos, texto e área de transferência entre Android, Mac (e futuramente iPad) através de um relay próprio.

- `relay/` — servidor Go (podman), roda no Mac mini via Tailscale: `http://guilhermes-mac-mini-1.tail56ad8e.ts.net:8443`
- `macos/` — app SwiftUI (macOS 14+), Swift Package
- `android/` — app Kotlin/Compose
- `obsidian-bridge/` — plugin do Obsidian (Idroid Bridge): Copilot e VS Code no celular (ADR-0006/0007)
- `docs/` — documentação e ADRs

## Build

### Android (APK)

Pela linha de comando, sem Android Studio. Requer JDK 21; o SDK vem de `android/local.properties`
(`sdk.dir=/opt/homebrew/share/android-commandlinetools`, arquivo fora do git).

```bash
cd android
export JAVA_HOME=$(/usr/libexec/java_home -v 21 || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)
./gradlew :app:assembleDebug :app:lintDebug -q
```

- Saída: `android/app/build/outputs/apk/debug/app-debug.apk`
- Assinado com a chave de debug (`~/.android/debug.keystore`): instala via "fontes desconhecidas", não serve para a Play Store; trocar de chave exige desinstalar antes.
- Com celular via USB (depuração ativada): `./gradlew :app:installDebug`
- Lint deve terminar com 0 erros.

### macOS

```bash
cd macos
SDKROOT=/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk swift build   # só compilar
./scripts/bundle.sh             # gera .build/Idroid.app (com ícone)
./scripts/bundle.sh --install   # gera, copia para /Applications e reabre
```

Não há Xcode completo instalado, só Command Line Tools.

### Relay

```bash
cd relay
go test ./...
podman compose up -d --build --force-recreate   # sem --force-recreate o container antigo continua rodando
```

### Release do Android

`scripts/release-android.sh <versão>` — build + lint, tag `android-vX`, GitHub Release com o APK. Detalhes em
`docs/05-deployment/release-guide.md`.

### Idroid Bridge (plugin do Obsidian)

```bash
cp obsidian-bridge/{manifest.json,main.js} ../../.obsidian/plugins/idroid-bridge/   # depois desativar/ativar no Obsidian
~/.idroid/copilot GET /state                                                       # teste
```

Usa API interna do Copilot (testado no 4.0.18): retestar após atualizar o Copilot.
