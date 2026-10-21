# AGENTS.md

Guía para agentes que trabajan en Winali y sus apps. Detalles del estándar de catálogo en [docs/CATALOG.md](docs/CATALOG.md); estado verificado en [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md).

## Estructura

| Carpeta | Repositorio | Paquete |
|---|---|---|
| `.` (tienda, catálogo, herramientas) | `WillianChavez/winali` | `io.github.willianchavez.winali` |
| `apps/apk` | `WillianChavez/apk` | `io.github.willianchavez.apk` |
| `apps/focus` | `WillianChavez/focus` | `io.github.willianchavez.focus` |
| `apps/read` | `WillianChavez/read` | `io.github.willianchavez.read` |
| `apps/deck` (Android y servidor Windows) | `WillianChavez/deck` | `io.github.willianchavez.deck` |
| `apps/books` (lector de libros independiente) | `WillianChavez/books` | `io.github.willianchavez.books` |

Cada carpeta de `apps/` es un repositorio git independiente y **privado** (ignorado por `winali`): haz commit y push dentro de ella. Solo `winali` (tienda, catálogo y Releases) es público; el código de las apps no se publica ni se copia a `winali`, y nunca hagas público un repositorio de una app. Los APK firmados se publican como Releases de `winali` (paso 5). Los APK de referencia de la raíz y `.local/` nunca se suben.

## Ramas y protección

Aplica a los seis repositorios (`winali`, `apk`, `focus`, `read`, `deck`, `books`):

- `main` es la rama estable y la que lee el catálogo; `dev` es la rama de integración. Trabaja en `dev` o en ramas `feature/…`, `fix/…` que salen de `dev`, y abre un PR hacia `dev`; cuando esté probado, PR de `dev` a `main`.
- Rulesets activos: `main` exige PR con 1 aprobación y revisión del code owner (`.github/CODEOWNERS` → `@WillianChavez`), conversaciones resueltas y bloquea force-push y borrado; `dev` bloquea force-push y borrado; los tags `v*` y `<app>-v*` no se pueden mover ni borrar.
- Solo el propietario (rol admin) puede saltarse la regla, por eso los pasos de publicación (push de `main` y tag `vX.Y.Z`) siguen funcionando. Para un cambio normal usa PR en lugar de empujar a `main`.
- Fusión: merge commit para `dev` → `main` (conserva el historial), squash para ramas de trabajo → `dev`. Rebase deshabilitado; las ramas fusionadas se borran solas (excepto `dev`).
- Los commits de los pasos 4 y 6 de abajo (`git push origin main`) se pueden hacer vía `dev` + PR; el push directo queda como atajo del propietario.

## Entorno (Windows)

- `JAVA_HOME=C:/Users/willi/AppData/Local/Programs/REA/java/jdk-21.0.12.1+1`
- `ANDROID_HOME=%LOCALAPPDATA%/Android/Sdk` (plataforma 36, Build Tools 36.0.0)
- Desde PowerShell: `.\gradlew.bat …`; desde Git Bash: `cmd //c "gradlew.bat …"` y `export MSYS_NO_PATHCONV=1` antes de usar rutas de `adb` como `/data/local/tmp`.
- Emuladores: siempre `-gpu host` (usa la RTX 4060), uno por app (`winali36`, `apk36`, `focus36`, `read36`), RAM 2 GB, y ciérralos al terminar con `adb -s <serial> emu kill`. Fija `ANDROID_SERIAL` antes de pruebas de dispositivo. No lances dos Gradle a la vez.
- REA (con apktool) para inspeccionar APK propios y de referencia.

## Actualizar una app

Ejemplo con Focus pasando a `0.1.3`; vale igual para `apk`, `read` y la propia `winali` (en ese caso la carpeta es la raíz).

1. **Cambios y pruebas** en `apps/<app>`:
   ```sh
   .\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleRelease
   .\gradlew.bat :app:connectedDebugAndroidTest   # con ANDROID_SERIAL fijado
   ```
   La release usa R8: prueba también el comportamiento afectado con el APK release, no solo el debug. Añade una prueba por cada comportamiento nuevo.
2. **Versión por defecto** en `app/build.gradle.kts` (`versionCode` y `versionName`). El script local de publicación recibe `X.Y.Z` y calcula `versionCode = X*1000000 + Y*1000 + Z` (`0.1.3` → `1003`). El `versionCode` siempre debe subir.
3. **`CHANGELOG.md` solo con las notas de esta versión.** Se usa entero como notas de la Release y como `changelog` del `release.json` que muestra Winali. Texto para el usuario, sin secciones tipo "Sin publicar".
4. **Commit y push a `main`** después de las comprobaciones locales:
   ```sh
   git push origin main
   ```
5. **Compila y firma localmente; después publica la Release.** GitHub Actions está deshabilitado en los seis repositorios y no hay workflows. Desde el repositorio de la app:
   ```sh
   .\tools\build-release.ps1 -Version 0.1.3 -ExpectedCertificateSha256 <huella-del-catalogo>
   git tag v0.1.3 && git push origin v0.1.3      # tag de código en el repo privado de la app
   gh release create focus-v0.1.3 ".local/releases/focus/0.1.3/focus.apk" ".local/releases/focus/0.1.3/release.json" -R WillianChavez/winali --target main --latest=false --title "Focus 0.1.3" --notes-file CHANGELOG.md
   gh release view focus-v0.1.3 -R WillianChavez/winali   # focus.apk + release.json
   ```
   Sustituye `focus` por la app correspondiente. La Release va en el repositorio público `winali` (los repos de las apps son privados y sus descargas no serían accesibles para los teléfonos) con el tag `<app>-vX.Y.Z`; `--latest=false` evita desplazar la Release `vX.Y.Z` de la tienda. El `release.json` ya apunta a esa URL. El script ejecuta release, lint y pruebas unitarias, firma con `Documents/Winali-private/signing/<app>.jks` y lee `<app>.json` (`storePassword`, `keyPassword`, `alias`). Genera `release.json` desde el APK real y comprueba versión y certificado. Los archivos salen a `apps/<app>/.local/releases/<app>/<versión>` (en la propia tienda, a `.local/releases/winali/<versión>`). Las claves y credenciales se guardan fuera de Git, en `Documents/Winali-private/signing`; nunca las guardes dentro de Codex ni en el repositorio. Nunca cambies de clave: Android rechazaría la actualización. No vuelvas a añadir workflows sin que el usuario lo pida.
6. **Catálogo** en la raíz de `winali`:
   ```sh
   node tools/catalog.mjs sync <app>        # lee la Release <app>-vX.Y.Z más alta de winali
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

Si cambian la descripción, los permisos o las capturas de la ficha, edítalos en `catalog/index.json` (español y `locales.en`). Las capturas van en `docs/screenshots/<app>-N.png` y se enlazan con `https://raw.githubusercontent.com/WillianChavez/winali/main/docs/screenshots/...`. El README de Winali muestra solo las capturas de la tienda y no lleva una tabla de apps disponibles; documenta las funciones en el repositorio de cada app.

## Añadir una app nueva

1. Crea `apps/<app>` como repositorio propio copiando la estructura de `apps/apk` (Gradle, `tools/build-release.ps1`, `tools/release-manifest.mjs`, `LICENSE`, `README.md`, `CHANGELOG.md`), con paquete `io.github.willianchavez.<app>`. No añadas workflows.
2. Crea el repositorio **privado** con `gh repo create --private`, genera una clave de firma propia y guarda su JKS y JSON de credenciales en el directorio privado de firma, fuera del repositorio.
3. Compila y firma localmente `0.1.0` con el script, crea el tag y publica el APK y `release.json` como Release `<app>-v0.1.0` de `winali` con `gh release create` (paso 5). La primera versión no tiene huella anterior para `-ExpectedCertificateSha256`; guárdala para las siguientes.
4. Añade su ficha a `catalog/index.json` con `"release": null`, ejecuta `node tools/catalog.mjs sync <app>` y actualiza el número de apps esperado en `tools/catalog.test.mjs`.

## Reglas del proyecto

- Sin cuentas, anuncios, telemetría ni SDK de terceros. Sin permiso de Internet salvo en Winali y Deck (conexión LAN autorizada por el usuario).
- Sin textos de relleno ni eslóganes en la interfaz: solo texto funcional, en español e inglés.
- Release optimizada: R8, reducción de recursos, sin `material-icons-extended`, con Baseline Profile.
