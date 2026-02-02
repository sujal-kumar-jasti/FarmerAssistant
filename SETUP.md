# Installation & Configuration Guide

Follow these steps to configure the environment, set up the cloud services, and build the **Farmer Assistant** application.

## 1. Configure Firebase

The repository may include a configuration file, but you must link it to your own Firebase project for the backend to function.

**Step 1: Replace configuration file**
1. Go to the [Firebase Console](https://console.firebase.google.com/).
2. Create a new project and add an Android App with the package name `com.farmerassistant.app`.
3. Download the `google-services.json` file.
4. Replace the existing file at `app/google-services.json`.

**Step 2: Security Warning**
It is **highly recommended** to add `app/google-services.json` to your `.gitignore` file to prevent leaking your private project credentials to public repositories.

## 2. API Key Configuration

The app requires an external API key to fetch real-time weather data for soil and yield calculations.

1. **Get an API Key:** Sign up at [OpenWeatherMap](https://openweathermap.org/api) and generate a free API key.
2. **Update Constants:**
   - Navigate to the `api/` or `util/` package.
   - Locate the `WeatherConstants` or similar helper file.
   - Insert your API key into the `API_KEY` constant.

## 3. Machine Learning Model

The disease detection engine relies on a `.tflite` model file.

1. Ensure the file `plant_disease_model_38_classes.tflite` is present in the `app/src/main/assets/` directory.
2. If the file is missing, you must re-download or re-train the model and place it there before building.

## 4. Build and Run

### Prerequisites
- **Android Studio:** Hedgehog or later recommended.
- **Java JDK:** 11 or 17.
- **Android SDK:** API Level 34 (Android 14) support.

### Build Steps
1. Open the project in **Android Studio**.
2. **Sync Gradle:** Click "Sync Project with Gradle Files" and wait for dependencies to download.
3. **Build the APK:** Run the following command in the terminal:

```bash
./gradlew assembleDebug
```
4. **Install and Launch:** Connect your physical device or start an emulator and run:
```bash
./gradlew installDebug
```
5. **Enable Background Services**:
  For Regional Alerts and Soil Monitoring to work, ensure that:

     WorkManager is allowed to run in the background on your device.

     Location permissions are granted to the app upon first launch.
   
