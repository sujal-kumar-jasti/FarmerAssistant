#  Farmer Assistant

**Farmer Assistant** is an advanced agricultural management and disease detection ecosystem built with **Kotlin**. It leverages **On-Device Machine Learning (TFLite)**, **Regional Hotspot Tracking**, and **Automated Analytical Workers** to empower farmers with actionable data to improve crop health and maximize yield.

## Download & Installation

You can download the latest APK directly from the link below:

[Download Farmer Assistant v1.0 APK](https://github.com/sujal-kumar-jasti/Farmer-Assistant/releases/download/v1.0.0/FarmerAssistant-v1.0.apk)

### How to Install

Since this application is not yet published on the Google Play Store, your Android device may flag it as an unknown source. Follow these steps to install:

1. **Download:** Click the link above to save `FarmerAssistant-v1.0.apk` to your device.
2. **Open:** Tap the download notification.
3. **Allow Unknown Sources:**
   - If prompted, go to **Settings** and enable **"Allow from this source"**.
   - Return to the installation screen and tap **Install**.
4. **Play Protect:**
   - If you see an "Unsafe app blocked" warning, tap **More details** and select **Install anyway**.

*Note: This warning appears because the app is a private developer build and not yet verified by the Play Store.*

The project is organized under the package `com.farmerassistant.app` and follows modern Android architecture patterns. The backend logic and data synchronization are powered by **Firebase**, while specialized background workers handle complex agricultural simulations.

![Platform: Android](https://img.shields.io/badge/Platform-Android-green)
![Tech: Kotlin](https://img.shields.io/badge/Built%20With-Kotlin-orange)
![ML: TensorFlow Lite](https://img.shields.io/badge/ML-TensorFlow%20Lite-blue)
![Backend: Firebase](https://img.shields.io/badge/Backend-Firebase-yellow)

## Key Features

### AI-Powered Disease Management
- **Instant ML Diagnosis:** Uses a custom **TensorFlow Lite** model to identify 38 different plant disease classes directly on the device—no internet required.
- **Outbreak Hotspots:** A community-driven engine that tracks regional disease reports in real-time, providing early warnings to farmers in the same district.
- **Mitigation Chatbot:** AI-guided advice offering organic and chemical solutions for detected plant diseases.

### Advanced Farm Analytics
- **Yield Prediction Engine:** Automated analysis that projects potential yield reduction based on soil nutrition (NPK) and climate stress factors.
- **Soil Health Monitoring:** Real-time simulation of Nitrogen, Phosphorus, and Potassium levels combined with dynamic irrigation advice.
- **Automated Reporting:** Generates professional **Farm Performance Reports** in PDF format, summarizing historical health trends and AI recommendations.

### Precision Mapping & Sync
- **Multi-Plot Management:** Map multiple fields with custom boundaries using high-resolution satellite imagery via **Google Maps API**.
- **Context-Aware Insights:** Visual overlays for soil types and plot-specific alerts (Weather/Soil/Yield).
- **Offline Data Queue:** Queue disease reports while in the field; the system automatically syncs data to the cloud once a connection is detected.

## Technical Architecture

The application is configured with **Gradle Kotlin DSL** and focuses on non-blocking performance using **Kotlin Coroutines**.

### Tech Stack
- **Language:** Kotlin
- **UI Framework:** XML (Material Design 3)
- **Backend:** Firebase (Auth, Firestore, Storage)
- **ML/AI:** TensorFlow Lite, Custom Background Simulation Models
- **Background Tasks:** Android WorkManager (Regional alerts & periodic sync)

### Project Structure
The source code is located in `app/src/main/java/com/farmerassistant/app/`:

- **ui/home/fragments/**: Core screens including Disease Detection, Analytics, and Plot Management.
- **workers/**: Background processing logic (WorkManager) for soil analysis and regional alert aggregation.
- **data/**: Repositories and models handling farm data and report logic.
- **api/**: Network configurations for Weather APIs and Firebase services.
- **util/**: Helper functions for PDF generation, Location Geocoding, and Language localization.
- **assets/**: ML model files (`.tflite`) and class label definitions.

## Author

**J Sujal Kumar**
- **GitHub:** [@sujal-kumar-jasti](https://github.com/sujal-kumar-jasti)
- **LinkedIn:** [sujalkumarjasti](https://www.linkedin.com/in/sujalkumarjasti)
- **Email:** [sujalkumarjasti751@gmail.com](mailto:sujalkumarjasti751@gmail.com)

For installation and setup instructions, please refer to [SETUP.md](SETUP.md).
