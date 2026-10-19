# AGENTS.md

Guía para agentes que trabajan en Winali y sus apps. Detalles del estándar de catálogo en [docs/CATALOG.md](docs/CATALOG.md); estado verificado en [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md).

## Estructura

| Carpeta | Repositorio | Paquete |
|---|---|---|
| `.` (tienda, catálogo, herramientas) | `WillianChavez/winali` | `io.github.willianchavez.winali` |
| `apps/apk` | `WillianChavez/apk` | `io.github.willianchavez.apk` |
| `apps/focus` | `WillianChavez/focus` | `io.github.willianchavez.focus` |
| `apps/read` | `WillianChavez/read` | `io.github.willianchavez.read` |

Cada carpeta de `apps/` es un repositorio git independiente (ignorado por `winali`): haz commit y push dentro de ella. Los APK de referencia de la raíz y `.local/` nunca se suben.

## Entorno (Windows)

- `JAVA_HOME=C:/Users/willi/AppData/Local/Programs/REA/java/jdk-21.0.12.1+1`
- `ANDROID_HOME=%LOCALAPPDATA%/Android/Sdk` (plataforma 36, Build Tools 36.0.0)
- Desde PowerShell: `.\gradlew.bat …`; desde Git Bash: `cmd //c "gradlew.bat …"` y `export MSYS_NO_PATHCONV=1` antes de usar rutas de `adb` como `/data/local/tmp`.
- Emuladores: siempre `-gpu host` (usa la RTX 4060), uno por app (`winali36`, `apk36`, `focus36`, `read36`), RAM 2 GB, y ciérralos al terminar con `adb -s <serial> emu kill`. Fija `ANDROID_SERIAL` antes de pruebas de dispositivo. No lances dos Gradle a la vez.
- REA (`rea` en `C:/Users/willi/AppData/Local/Programs/REA`, con apktool) para inspeccionar APK propios y de referencia. Las referencias se analizan; nunca se copia su código, iconos, imágenes ni textos.

## Actualizar una app

Ejemplo con Focus pasando a `0.1.3`; vale igual para `apk`, `read` y la propia `winali` (en ese caso la carpeta es la raíz).

1. **Cambios y pruebas** en `apps/<app>`:
   ```sh
   .\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleRelease
   .\gradlew.bat :app:connectedDebugAndroidTest   # con ANDROID_SERIAL fijado
   ```
   La release usa R8: prueba también el comportamiento afectado con el APK release, no solo el debug. Añade una prueba por cada comportamiento nuevo.
2. **Versión por defecto** en `app/build.gradle.kts` (`versionCode` y `versionName`), solo para compilaciones locales. En CI la versión sale del tag: `vX.Y.Z` → `versionCode = X*1000000 + Y*1000 + Z` (`v0.1.3` → `1003`). El `versionCode` siempre debe subir.
3. **`CHANGELOG.md` solo con las notas de esta versión.** El workflow lo usa entero como notas de la Release y como `changelog` del `release.json` que muestra Winali. Texto para el usuario, sin secciones tipo "Sin publicar".
4. **Commit y push a `main`**, y espera la compilación en verde:
   ```sh
   git push origin main
   gh run list -R WillianChavez/<app> --limit 1
   ```
5. **Tag** para publicar la Release firmada:
   ```sh
   git tag v0.1.3 && git push origin v0.1.3
   gh run watch <id> -R WillianChavez/<app> --exit-status
   gh release view v0.1.3 -R WillianChavez/<app>   # <app>.apk + release.json
   ```
   El workflow *Signed release* firma con la clave del repositorio (secretos `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, alias `release`), genera `release.json` desde el APK real y crea la Release. Nunca cambies de clave: Android rechazaría la actualización.
6. **Catálogo** en la raíz de `winali`:
   ```sh
   node tools/catalog.mjs sync <app>        # lee releases/latest/download/release.json
   node tools/catalog.mjs validate
   node --test tools/catalog.test.mjs
   git diff catalog/index.json               # revisa versión, tamaño, hash, certificado y notas
   git commit -am "Publish <App> 0.1.3 in the catalog" && git push origin main
   ```
   `sync` también actualiza la copia embebida (`app/src/main/assets/catalog.json`) y sube `revision`. El certificado debe coincidir con el anterior; si `sync` lo rechaza, no lo fuerces.
7. **Comprueba el catálogo publicado:**
   ```sh
   curl -s https://raw.githubusercontent.com/WillianChavez/winali/main/catalog/index.json
   ```
   Los teléfonos lo verán en su próxima comprobación (al abrir Winali o cada ~12 h). No hace falta recompilar Winali.

Si cambian la descripción, los permisos o las capturas de la ficha, edítalos en `catalog/index.json` (español y `locales.en`). Las capturas van en `docs/screenshots/<app>-N.png` y se enlazan con `https://raw.githubusercontent.com/WillianChavez/winali/main/docs/screenshots/...`. Actualiza también la tabla del `README.md` si cambia lo que hace la app.

## Añadir una app nueva

1. Crea `apps/<app>` como repositorio propio copiando la estructura de `apps/apk` (Gradle, `.github/workflows`, `tools/release-manifest.mjs`, `LICENSE`, `README.md`, `CHANGELOG.md`), con paquete `io.github.willianchavez.<app>`.
2. Crea el repositorio público con `gh repo create`, genera una clave de firma propia (fuera del repo, con permisos privados) y configura los tres secretos.
3. Publica `v0.1.0` con el tag.
4. Añade su ficha a `catalog/index.json` con `"release": null`, ejecuta `node tools/catalog.mjs sync <app>`, actualiza el número de apps esperado en `tools/catalog.test.mjs` y la tabla del `README.md`.

## Reglas del proyecto

- Sin cuentas, anuncios, telemetría ni SDK de terceros. Sin permiso de Internet salvo en Winali.
- Sin textos de relleno ni eslóganes en la interfaz: solo texto funcional, en español e inglés.
- Release optimizada: R8, reducción de recursos, sin `material-icons-extended`, con Baseline Profile.
- No marques nada como hecho sin probarlo; anota en `docs/IMPLEMENTATION.md` (o `docs/FEATURES.md` de la app) lo que no se pudo verificar.
