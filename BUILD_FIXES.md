# Build

Uses plain Android Views instead of Compose to avoid Compose overload/signature errors. GitHub Actions builds with JDK 17 and Android SDK 35.


Back fix v12: uses Activity.onBackPressed consistently and disables OnBackInvoked bridging to prevent Android 13+ automatic Activity finishing/navigation.
