# PEAKing

An Android app for tracking the mountain peaks you've hiked. Explore a map, log your visits, and keep a record of every summit — grouped and sorted with elevation data.

## Features

- **Explore** — browse an interactive map of peaks powered by MapLibre.
- **Hike** — record a hike by selecting the peak(s) you visited, with a date picker and optional notes; visits are saved locally.
- **My Peaks** — view your visited peaks grouped and sorted by elevation, with the ability to edit past visits.

## Tech stack

- **Kotlin** + **Jetpack Compose** for UI
- **MapLibre Android SDK** for map rendering
- **Room** for local persistence
- **Navigation Compose** for in-app navigation

## Getting started

### Prerequisites

- Android Studio (recent stable version)
- JDK 21
- A [MapTiler](https://www.maptiler.com/) API key (used for map tiles)

### Setup

1. Clone the repository.
2. In the project root, create/edit `local.properties` and add your MapTiler key:
   ```properties
   MAPTILER_API_KEY=your_api_key_here
   ```
3. Open the project in Android Studio and let Gradle sync.
4. Run the `app` module on an emulator or device (min SDK 24, target/compile SDK 37).

### Release builds

To produce a signed release build, add the following to `local.properties`:

```properties
RELEASE_KEYSTORE_PATH=path/to/keystore.jks
RELEASE_KEYSTORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

## Project structure

```
app/src/main/java/com/example/peaking/
├── data/peak/        # Room entities, DAO, and repository for visited peaks
├── ui/
│   ├── explore/      # Explore tab
│   ├── hike/         # Hike recording flow
│   ├── mypeaks/      # My Peaks tab
│   ├── map/          # Map composables
│   ├── navigation/   # Bottom nav destinations
│   ├── theme/        # Compose theme
│   └── common/       # Shared UI components
└── MainActivity.kt
```
