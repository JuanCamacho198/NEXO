# NEXO 📚

A modern Android ebook reader built with Kotlin and Jetpack Compose.

<p align="center">
  <a href="https://developer.android.com/studio">
    <img alt="Built with Android Studio" src="https://img.shields.io/badge/Android%20Studio-2024.2.1+-4781B8?style=flat&logo=android-studio&logoColor=white" />
  </a>
  <a href="https://kotlinlang.org">
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?style=flat&logo=kotlin&logoColor=white" />
  </a>
  <a href="https://opensource.org/licenses/Apache-2.0">
    <img alt="License" src="https://img.shields.io/badge/License-Apache%202.0-FE5212?style=flat&logo=open-source-initiative&logoColor=white" />
  </a>
</p>

---

## Overview

NEXO is a local-first ebook reader for Android that supports EPUB and PDF formats with a clean, modern interface built with Jetpack Compose.

### Features

- 📖 **Multiple Format Support**: Read EPUB and PDF books
- 🌙 **Reading Experience**: Clean, distraction-free reader
- 🔖 **Bookmarks & Highlights**: Mark your favorite passages
- 📊 **Reading Progress**: Track your reading time and progress
- 🗂️ **Library Management**: Organize your book collection
- 🔍 **Easy Import**: Import books from device storage

---

## Tech Stack

- **Language**: Kotlin 1.9.x
- **UI Framework**: Jetpack Compose with Material 3
- **Architecture**: Clean Architecture (MVVM)
- **Database**: Room
- **Async**: Kotlin Coroutines + Flow
- **Backend** (optional): Supabase for cloud sync

---

## Architecture

```
app/
├── src/main/java/com/nexo/
│   ├── domain/           # Domain layer (business logic)
│   │   ├── model/       # Domain models
│   │   ├── repository/ # Repository interfaces
│   │   └── usecase/   # Use cases
│   ├── data/           # Data layer
│   │   ├── local/      # Room database, DAOs, entities
│   │   ├── repository/ # Repository implementations
│   │   ├── storage/   # File storage
│   │   ├── epub/      # EPUB parsing
│   │   └── pdf/       # PDF rendering
│   ├── presentation/    # UI layer
│   │   ├── screen/    # Compose screens
│   │   ├── viewmodel/ # ViewModels
│   │   ├── navigation/
│   │   └── theme/
│   └── di/            # Dependency injection
```

---

## Getting Started

### Prerequisites

- Android Studio Flamingo or newer
- JDK 17+
- Android SDK (API 26+)

### Build

```bash
# Clone the repository
git clone https://github.com/JuanCamacho198/NEXO.git
cd NEXO/android

# Build debug APK
./gradlew.bat assembleDebug

# Or run on connected device/emulator
./gradlew.bat installDebug
```

### Configuration (Optional)

Create `local.properties` in the `android/` directory:

```properties
supabase.url=YOUR_SUPABASE_URL
supabase.anonkey=YOUR_SUPABASE_ANON_KEY
```

---

## Screenshots

| Library | Reader (EPUB) | Reader (PDF) |
|---------|---------------|--------------|
| ![Library] | ![EPUB Reader] | ![PDF Reader] |

---

## Contributing

Contributions are welcome! Please read our [Contributing Guide](CONTRIBUTING.md) for details.

---

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

---

## Links

- [GitHub Repository](https://github.com/JuanCamacho198/NEXO)
- [Issue Tracker](https://github.com/JuanCamacho198/NEXO/issues)
- [Changelog](CHANGELOG.md)
