# BUTI 2.0 v0.1

A clean native Android payday-to-payday money manager.

## Included in v0.1
- Payday-to-payday budget cycle
- Dashboard with "Safe to Spend Today"
- Income: add, edit, delete
- Regular monthly expenses: add, edit, delete, optional due day
- Everyday spending: add, edit, delete
- Savings: add, edit, delete
- Local Room database
- Native Kotlin + Jetpack Compose

## Build on GitHub (no Android Studio needed)
This project includes `.github/workflows/build-apk.yml`.

1. Put the project files in a GitHub repository.
2. Open the repository's **Actions** tab.
3. Select **Build BUTI APK**.
4. Tap **Run workflow** and confirm.
5. When the run finishes, open it and download the artifact named **BUTI-v0.1-debug-apk**.
6. Unzip that artifact to get `app-debug.apk`, then install it on your Android device.

The workflow also builds automatically when project files are pushed to the main/master branch.
