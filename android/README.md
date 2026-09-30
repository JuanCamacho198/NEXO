# NEXO 📖

Aplicación de lectura para Android. Lee **EPUB** y **PDF** con ajustes de lectura (tamaño de fuente, tema claro/oscuro/sepia, temporizador), sincronización con Supabase (progreso, resaltados y marcadores) y modo local offline.

Construida con **Kotlin + Jetpack Compose + Material 3** y Clean Architecture.

## Requisitos

- **Android Studio** Hedgehog o más reciente (Ladybug o Koala recomendado)
- **JDK 17+**
- **Android SDK 36** (Android 16) — Android Studio lo instala al abrir el proyecto
- Gradle 8.13 (el wrapper lo descarga solo, no hace falta instalarlo)

## Instalación paso a paso

1. **Clonar el repositorio**

   ```powershell
   git clone <url-del-repositorio>
   cd NEXO/android
   ```

2. **Abrir el proyecto en Android Studio**

   - File → Open → seleccioná la carpeta `android/`
   - Esperá a que Gradle sincronice (primera vez puede tardar varios minutos)

3. **(Opcional) Configurar Supabase**

   La app funciona sin configuración en **modo local offline**. Para habilitar la sincronización, creá un archivo `local.properties` en la raíz del proyecto (`android/local.properties`) con las claves del proyecto:

   ```properties
   SUPABASE_URL=https://tu-proyecto.supabase.co
   SUPABASE_ANON_KEY=tu-anon-key
   google.oauth.client.id=tu-web-client-id
   google.oauth.android.client.id=tu-android-client-id
   ```

   > ⚠️ `local.properties` contiene claves secretas y **no se commitea**. Android Studio lo crea automáticamente al configurar el SDK.

4. **Ejecutar la app**

   - Conectá un dispositivo Android con depuración USB habilitada o iniciá un emulador
   - Run ▶️ (botón verde) en Android Studio

## Build desde línea de comandos

```powershell
# Compilar APK de debug
.\gradlew.bat assembleDebug

# Compilar APK de release
.\gradlew.bat assembleRelease

# Correr los tests unitarios
.\gradlew.bat testDebugUnitTest
```

El APK queda en `app/build/outputs/apk/`.

## Estructura rápida

```
android/
├── app/src/main/java/com/nexo/
│   ├── data/           # Capa de datos (Room, Supabase, EPUB, PDF)
│   ├── domain/         # Capa de dominio (modelos, repositorios, use cases)
│   ├── presentation/   # UI (pantallas, ViewModels, tema, navegación)
│   └── di/             # Inyección de dependencias
└── app/src/test/       # Tests unitarios
```

## Licencia

Uso interno - NEXO
