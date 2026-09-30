# AGENTS.md

## Commands

### Build & Run
```powershell
# Build debug APK
.\gradlew.bat assembleDebug

# Build release APK
.\gradlew.bat assembleRelease

# Run tests (all)
.\gradlew.bat test

# Run unit tests only
.\gradlew.bat testDebugUnitTest
.\gradlew.bat testReleaseUnitTest

# Run single test class
.\gradlew.bat testDebugUnitTest --tests "com.nexo.presentation.viewmodel.AuthViewModelTest"
.\gradlew.bat testReleaseUnitTest --tests "com.nexo.presentation.viewmodel.AuthViewModelTest"

# Run single test method
.\gradlew.bat testDebugUnitTest --tests "com.nexo.presentation.viewmodel.AuthViewModelTest.signInWithEmailPassword_Success"
```

### Verification Tasks
```powershell
# Verify no hardcoded strings in AuthScreen
.\gradlew.bat verifyAuthScreenNoHardcodedStrings

# Verify release mapping (minify enabled by default)
.\gradlew.bat verifyReleaseMapping

# Run detekt (static analysis)
.\gradlew.bat detekt

# Build with info for debugging
.\gradlew.bat assembleDebug --info --max-workers=2
```

### Android
```powershell
# Install debug APK to device/emulator
.\gradlew.bat installDebug

# Start emulator (requires ANDROID_HOME)
emulator -avd <avd_name>
```

## Project Structure

```
android/
├── app/
│   └── src/
│       ├── main/java/com/nexo/
│       │   ├── data/              # Data layer (repositories, Room, Supabase, PDF, EPUB)
│       │   ├── domain/           # Domain layer (models, repository interfaces, usecases)
│       │   ├── presentation/    # UI layer (screens, viewmodels, theme, navigation)
│       │   ├── ui/              # Reusable UI components (atoms, molecules)
│       │   ├── di/              # Dependency injection (AppContainer)
│       │   └── MainActivity.kt  # Entry point
│       ├── test/                # Unit tests (JVM)
│       │   └── java/com/nexo/
│       │       ├── data/
│       │       ├── domain/
│       │       ├── presentation/
│       │       └── testutil/     # Test utilities (MainDispatcherRule)
│       └── androidTest/         # Instrumented tests (device/emulator)
│           └── java/com/nexo/
├── gradle/                      # Gradle wrapper
├── build.gradle.kts             # Root build config
├── app/build.gradle.kts          # App build config
├── gradle.properties            # Gradle properties
└── local.properties             # Local config (Supabase keys, NOT in git)
```

### Package Organization
| Package | Purpose |
|---------|---------|
| `data/local/` | Room database, DAOs, entities |
| `data/remote/` | Supabase client, sync service |
| `data/session/` | Session management |
| `data/repository/` | Repository implementations |
| `data/pdf/` | PDF rendering |
| `data/epub/` | EPUB parsing |
| `data/storage/` | File storage |
| `domain/model/` | Domain models (Book, ReadingProgress, etc.) |
| `domain/repository/` | Repository interfaces |
| `domain/usecase/` | Business logic |
| `presentation/screen/` | Compose screens |
| `presentation/viewmodel/` | ViewModels |
| `presentation/theme/` | Theme (Color, Type, Dimens, Shape) |
| `presentation/navigation/` | Navigation graph |
| `ui/components/atoms/` | Basic components (Button, ProgressBar, Typography) |
| `ui/components/molecules/` | Compound components (BookCard) |
| `di/` | DI container |

## Code Style

### Kotlin
- **Kotlin 1.9.24** with **Compose 1.5.14**
- Use **coroutines** for async operations
- Use **Flow** for reactive streams
- Follow **Clean Architecture**: presentation -> domain -> data
- **Dependency direction**: UI depends on domain, domain has no dependencies, data implements domain interfaces

### Compose
- Use **Material 3** (`material3` dependency)
- Follow **compoundable pattern**: atoms -> molecules -> screens
- Use **StateFlow** in ViewModels, collect via `collectAsState()` in Compose
- Use **rememberCoroutineScope** for async operations in Composables

### Imports
```kotlin
// Standard
import kotlinx.coroutines.flow.*
// Compose
import androidx.compose.runtime.*
// Room
import androidx.room.*
// Supabase
import io.github.jan-tennert.supabase.*
// ViewModel
import androidx.lifecycle.viewModelScope
```

### Error Handling
- Use sealed classes for error types in domain layer
- Wrap exceptions in domain errors
- Show user-friendly messages in UI

### Naming
- **Files**: PascalCase (e.g., `AuthViewModel.kt`, `BookCard.kt`)
- **Functions**: camelCase
- **Classes/Objects**: PascalCase
- **Constants**: UPPER_SNAKE_CASE
- **Tests**: `<ClassName>Test.kt` or `<Feature>Test.kt`, test methods: `<methodName>_<scenario>_<expected>`

## Conventions

### Room Database
- Schema location: `app/schemas/com.nexo.data.local.AppDatabase/` (auto-generated)
- Migration files: `AppDatabaseMigrations.kt`
- Use **@Dao** for data access
- Use **@Entity** for tables

### Supabase
- Config via `local.properties` (never commit keys)
- BuildConfig fields for runtime config
- Session management in `data/session/`

### Testing
- Tests go in same package structure under `test/`
- Use `MainDispatcherRule` for coroutine dispatchers
- Use **MockK** for mocking dependencies: `mockk()`, `every {}`, `verify {}`
- Mock dependencies via interfaces or constructor injection
- Test one thing per test method

### Strings
- All user-facing strings in `res/values/strings.xml` (English) and `res/values-es/strings.xml` (Spanish)
- No hardcoded strings in Compose code
- Custom Gradle task `verifyAuthScreenNoHardcodedStrings` enforces this

### Lint baseline
- `app/lint-baseline.xml` freezes the legacy lint findings; `lint { baseline = file("lint-baseline.xml"); abortOnError = true }`
  in `app/build.gradle.kts` means only NEW findings fail `:app:lintDebug` (and the `Android checks` CI job).
- Owning-PR convention: when your PR intentionally introduces a finding that is already covered by the baseline
  (or fixes a baselined one), regenerate the baseline in the SAME PR — never in a separate PR:
  `cmd /c gradlew.bat --no-daemon -Dorg.gradle.vfs.watch=false :app:updateLintBaseline`
  then commit the updated `app/lint-baseline.xml` alongside the change. Do NOT hand-edit the baseline.

## Safety

### NEVER DO
- **Never commit `local.properties`** - contains sensitive keys
- **Never hardcode user-facing strings** - use string resources
- **Never use `sudo`** - not needed
- **Never run shell commands with user input** - dangerous
- **Never skip tests** before committing

### ALWAYS DO
- **Run tests** before commit: `.\gradlew.bat test`
- **Verify strings**: `.\gradlew.bat verifyAuthScreenNoHardcodedStrings`
- **Use string resources** for all UI text
- **Handle errors gracefully** in ViewModels and repositories

## Design System

El diseño de la app está en `design/nextPage-movil.pen` (Pencil, accesible via MCP).
Incluye Design Tokens y 5 pantallas trazadas.

Para inspeccionar nodos del diseño desde el MCP de Pencil:
```powershell
# Node IDs de las pantallas:
# W29xCr → Welcome Screen (AuthScreen.kt)
# WDYjT  → Home (HomeScreen.kt)
# HQRl6  → Bookshelf (LibraryScreen.kt)
# iSSWb  → Resaltados (HighlightsScreen.kt)
# EQsNd  → Ajustes (SettingsScreen.kt)
```

## Project Artifacts

| Archivo | Propósito |
|---------|-----------|
| `.gitignore` | Ignora builds, IDE, secrets |
| `docs/` | Documentación del proyecto (architecture, ui, diagrams, onboarding) |
| `.docs/GENTLE_AI_ORCHESTRATOR_GUIDE.md` | Memoria técnica SDD + estado de pantallas vs diseño |


## Gradle Notes

- **Release minify enabled by default** - sets `proguard-rules.pro`
- **KSP** for Room annotation processing
- **Compose compiler** version matched to Kotlin version
- Custom tasks in `app/build.gradle.kts` for verification

## Gradle Daemon & Verify Hang

Root cause: metaspace `512m` + `kotlin.compiler.execution.strategy=in-process` + `org.gradle.vfs.watch=true` (default) + `pwsh` output buffering + MockK `mockkStatic` leaks. The Kotlin compiler running in-process exhausts metaspace when combined with VFS watching and MockK static mocks that are never cleared, causing hangs observed during `testDebugUnitTest` and verify tasks (e.g. `verifyAuthScreenNoHardcodedStrings`).

Properties (in `android/gradle.properties`):

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=768m -XX:+HeapDumpOnOutOfMemoryError
org.gradle.vfs.watch=false
kotlin.compiler.execution.strategy=daemon
```

Test teardown rule: every `mockkStatic` must have a matching `@After fun tearDown() = unmockkAll()` (or `@AfterClass` for `@BeforeClass` mocks). Import `io.mockk.unmockkAll` where needed. `MainDispatcherRule` must expose `val dispatcher` and cancel it in `finished()` via `dispatcher.cancel()` + `Dispatchers.resetMain()`.

Verify invocation (avoid pwsh deadlock — use `cmd` + `--no-daemon` + explicit VFS flag):

```powershell
cmd /c gradlew.bat --no-daemon -Dorg.gradle.vfs.watch=false testDebugUnitTest
```

Add timeout `10m` for verify tasks. Do not run gradle via `pwsh` without `cmd /c` wrapper for long-running verify.

How to recover:

```powershell
.\gradlew.bat --stop
# if still hung: kill java.exe via Task Manager or `taskkill /F /IM java.exe`
```

## E2E (Maestro)

Suite E2E en `.maestro/` con runners en `scripts/e2e/`. Precondiciones del dispositivo:

1. Emparejar por *Depuración inalámbrica* (`adb connect ip:puerto`); el puerto **rota** en HyperOS — usar el serial mDNS como `-AdbTarget` (los scripts lo aceptan) o refrescar `E2E_ADB_TARGET`.
2. Locale del sistema: **español** (strings fijados verbatim en los flows). Sesión iniciada preservada; NUNCA pasar `clearState: true`.
3. Ajustes → **Modo debug** ON (chip DEBUG en lector). Libro de prueba "La Odisea" con highlight sembrado (excerpt "divinal Odiseo", la fila más reciente por `updated_at DESC`).
4. Prompts one-time de MIUI aceptados manualmente; Developer options → **Instalar vía USB** ON (si no, MIUI rechaza el driver de Maestro: `INSTALL_FAILED_USER_RESTRICTED`). Extender `screen_off_timeout` para ventanas largas.
5. Solo ADB-WiFi (sin USB): usar `Start-ChaosSequence.ps1 -StageOnline`; el script arma un temporizador EN EL dispositivo que re-habilita WiFi tras el corte.

```powershell
pwsh scripts/e2e/Invoke-E2E.ps1 -Tags smoke
pwsh scripts/e2e/Invoke-E2E.ps1 -Tags regression,sync
pwsh scripts/e2e/Start-ChaosSequence.ps1 -StageOnline -OfflineSeconds 45
```
