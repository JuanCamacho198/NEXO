# Contributing to NEXO

Thank you for your interest in contributing to NEXO! This guide will help you get started.

## Code of Conduct

By participating in this project, you are expected to uphold our [Code of Conduct](CODE_OF_CONDUCT.md).

## How Can I Contribute?

### Reporting Bugs

Before creating a bug report:
1. **Check existing issues** to avoid duplicates
2. Use the bug report template
3. Include detailed steps to reproduce
4. Include your Android version and device info

### Suggesting Features

1. Search existing feature requests
2. Open a new issue with the feature template
3. Explain the use case and expected behavior
4. Consider mockups or examples

### Pull Requests

#### Pull Request Process

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes following our coding standards
4. Add/update tests if applicable
5. Update documentation if needed
6. Commit with clear messages (see commit conventions below)
7. Push to your fork and open a PR

#### Commit Message Conventions

We use [Conventional Commits](https://conventionalcommits.org):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Types:**
- `feat`: A new feature
- `fix`: A bug fix
- `docs`: Documentation only changes
- `style`: Code style changes (formatting, no logic)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Build process, dependencies

**Examples:**
```
feat(reader): add PDF go-to-page dialog
fix(lib): filter soft-deleted books from library
docs(readme): update installation instructions
test(viewmodel): add deletion event tests
```

## Development Setup

### Prerequisites

- Android Studio Flamingo+
- JDK 17+
- Android SDK API 26+

### Local Development

```bash
# Clone and open in Android Studio
git clone https://github.com/JuanCamacho198/NEXO.git
# Open android/ folder in Android Studio

# Build from command line
cd android
./gradlew.bat assembleDebug

# Run tests
./gradlew.bat testDebugUnitTest
```

### Coding Standards

- **Language**: Kotlin 1.9.x
- **UI**: Jetpack Compose (Material 3)
- **Architecture**: Clean Architecture
- **State**: StateFlow + Compose collectAsState
- **DI**: Manual dependency injection (no Hilt/Koin)

**Key Practices:**
- Use sealed classes for UI states
- Follow MVVM with ViewModels
- Keep Compose functions small and focused
- Use meaningful names for variables
- Add KotlinDoc for public APIs

### Pull Request Checklist

- [ ] Tests pass (`./gradlew.bat testDebugUnitTest`)
- [ ] Build succeeds (`./gradlew.bat assembleDebug`)
- [ ] Code follows style guidelines
- [ ] Documentation updated (if applicable)
- [ ] Commit messages are clear and conventional

## Recognition

Contributors will be added to the project's contributors list.

---

## Questions?

- Open an issue for bugs or feature requests
- Start a discussion for general questions
- Don't hesitate to ask for help!

We appreciate every contribution, no matter how small. Thank you for supporting NEXO!