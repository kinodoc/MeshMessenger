# MeshMessenger 0.17 — GitLab сборка APK

## С телефона

1. Создайте новый проект в GitLab.
2. Загрузите **содержимое этой папки** в корень проекта. В корне должны находиться `settings.gradle.kts`, `build.gradle.kts`, `.gitlab-ci.yml` и папка `app`.
3. Сделайте commit в ветку `main`.
4. Откройте **Build → Pipelines**.
5. Дождитесь зелёного статуса pipeline.
6. Откройте job `build_debug_apk`.
7. В разделе **Job artifacts** скачайте APK.

CI сам установит Java 17, Android SDK 36 и Gradle 8.13. Отдельно устанавливать Android Studio или Gradle не нужно.

Собирается debug APK для тестирования. Для Google Play понадобится отдельная release-подпись и AAB.
