# Сборка APK через GitHub Actions

1. Создайте GitHub repository.
2. Загрузите в корень репозитория содержимое этой папки (файл `settings.gradle.kts` должен лежать в корне).
3. Откройте вкладку **Actions**.
4. Выберите **Build Android APK**.
5. Нажмите **Run workflow** или просто сделайте push в `main`.
6. После успешной сборки откройте запуск workflow и внизу скачайте artifact **MeshMessenger-debug-apk**.

APK будет внутри скачанного artifact.

Этот workflow собирает debug APK, поэтому подпись Google Play не требуется. Для release/AAB понадобится отдельная настройка keystore через GitHub Secrets.
