# PromoTracker — Rastreador Inteligente de Preços

Versão Android nativa (Java 21) do PromoTracker, com design fiel à versão web e banco Supabase compartilhado.

## ⚙️ Configuração obrigatória

Abra `app/build.gradle` e substitua:
```groovy
buildConfigField "String", "SUPABASE_URL",      "\"https://SEU-PROJETO.supabase.co\""
buildConfigField "String", "SUPABASE_ANON_KEY", "\"sua-anon-key\""
```

## 🚀 Build

```bash
# Baixar o gradle-wrapper.jar primeiro (necessário uma vez):
mkdir -p gradle/wrapper
curl -L https://github.com/gradle/gradle/raw/v8.5.0/gradle/wrapper/gradle-wrapper.jar \
     -o gradle/wrapper/gradle-wrapper.jar

# Build debug
./gradlew assembleDebug          # Linux/Mac/WSL
gradlew assembleDebug            # Windows CMD
.\gradlew assembleDebug          # Windows PowerShell

# Instalar direto no dispositivo
./gradlew installDebug
```

APK gerado em: `app/build/outputs/apk/debug/app-debug.apk`

## 🔑 Serper API Key

No app: **cabeçalho → Configurações API** → cole a chave → Salvar Chaves  
Obtenha gratuitamente em [serper.dev](https://serper.dev) (2.500 pesquisas/mês grátis)

## 🎨 Design

- **Modo claro:** fundo azul-acinzentado claro, cards brancos arredondados (16dp), botões pretos
- **Modo escuro:** fundo navy profundo (#0D1117), cards escuros, botões e acentos roxos (#7B6CF6)
- Segue fielmente o design do app web

## 📋 Requisitos

- Android Studio Hedgehog 2023.1.1+
- JDK 21 (bundled com Android Studio)
- Android SDK 34 (targetSdk), mínimo Android 8.0 (API 26)
- Gradle 8.5 + AGP 8.3.2
