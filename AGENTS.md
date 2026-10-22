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

Cada carpeta de `apps/` es un repositorio git independiente y **privado** (ignorado por `winali`): haz commit y push dentro de ella. `winali` contiene la tienda y el catálogo; `WillianChavez/winali-downloads` es el repositorio público separado de distribución binaria. El código de las apps no se publica ni se copia a ningún repositorio público, y nunca hagas público un repositorio de una app. Los APK firmados y, para Deck, el servidor Windows firmado, se publican como archivos estáticos de `winali-downloads` (paso 5). Los APK de referencia de la raíz y `.local/` nunca se suben.

## Privacidad de Releases

- Las Releases públicas nunca deben incluir código fuente, repositorios exportados, zips de fuentes, APKs de depuración, archivos `mapping`, logs, claves, contraseñas, credenciales ni ningún otro archivo privado. Para las apps solo se publican el APK firmado y su `release.json`.
- El código fuente de cada app permanece exclusivamente en su repositorio privado; no se copia ni se publica en `winali` ni en ningún otro repositorio público.
- GitHub añade automáticamente a toda Release pública los enlaces `Source code (zip)` y `Source code (tar.gz)` del repositorio y su tag. Esos enlaces no son assets subidos por el agente y GitHub no permite quitarlos individualmente de una Release. Si se exige que no aparezcan, no se debe publicar la Release en un repositorio público hasta definir un destino de distribución que cumpla ese requisito; hacer una Release pública solo con APK y `release.json` no elimina esos enlaces automáticos.

## Ramas y protección

Aplica a los seis repositorios (`winali`, `apk`, `focus`, `read`, `deck`, `books`):

- `main` es la rama estable y la que lee el catálogo; `dev` es la rama de integración. Trabaja en `dev` o en ramas `feature/…`, `fix/…` que salen de `dev`, y abre un PR hacia `dev`; cuando esté probado, PR de `dev` a `main`.
- Rulesets activos: `main` exige PR con 1 aprobación y revisión del code owner (`.github/CODEOWNERS` → `@WillianChavez`), conversaciones resueltas y bloquea force-push y borrado; `dev` bloquea force-push y borrado; los tags `v*` y `<app>-v*` no se pueden mover ni borrar.
- Los rulesets solo se aplican en `winali` (público). En los repos de las apps, que son privados, el plan gratuito de GitHub no los aplica (se configuraron pero quedan inactivos): ahí la protección es que nadie más tiene acceso. Si se añade un colaborador a un repo de app, hay que pasar a GitHub Pro o proteger `main` de otra forma antes.
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

Ejemplo con Focus pasando a `0.1.3`; vale igual para `apk`, `read`, `deck` y `books`. Resumen: el código y el tag `vX.Y.Z` se quedan en el repo privado de la app; el APK firmado y su `release.json` se publican como archivos estáticos de `winali-downloads`; después se sincroniza el catálogo de `winali`. Para la propia tienda (la carpeta es la raíz), el APK y `release.json` también van a `winali-downloads`.

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
5. **Compila y firma localmente; después publica el binario estático.** GitHub Actions está deshabilitado en los seis repositorios y no hay workflows. Desde el repositorio de la app:
   ```sh
   .\tools\build-release.ps1 -Version 0.1.3 -ExpectedCertificateSha256 <huella-del-catalogo>
   git tag v0.1.3 && git push origin v0.1.3      # tag de código en el repo privado de la app
   cd ..\..
   .\tools\publish-download.ps1 -App focus -Version 0.1.3 -Push
   ```
   Sustituye `focus` por la app correspondiente. `publish-download.ps1` copia solo el APK firmado y `release.json` a `WillianChavez/winali-downloads/apps/<app>/<versión>` y `latest`, actualiza el catálogo y puede hacer push. El `release.json` apunta a la URL estática de GitHub Pages. Para Deck Windows, publica también `deck-windows-x64.exe` y `windows-release.json` en las mismas carpetas. El script ejecuta release, lint y pruebas unitarias, firma con `Documents/Winali-private/signing/<app>.jks` y lee `<app>.json` (`storePassword`, `keyPassword`, `alias`). Genera `release.json` desde el APK real y comprueba versión y certificado. Los archivos salen a `apps/<app>/.local/releases/<app>/<versión>` (en la propia tienda, a `.local/releases/winali/<versión>`). Las claves y credenciales se guardan fuera de Git, en `Documents/Winali-private/signing`; nunca las guardes dentro de Codex ni en el repositorio. Nunca cambies de clave: Android rechazaría la actualización. No vuelvas a añadir workflows sin que el usuario lo pida.
6. **Catálogo** en la raíz de `winali`:
   ```sh
   node tools/catalog.mjs sync <app>        # lee apps/<app>/latest/release.json de winali-downloads
   node tools/catalog.mjs validate
   node --test tools/catalog.test.mjs
   git diff catalog/index.json               # revisa versión, tamaño, hash, certificado y notas
   git commit -am "Publish <App> 0.1.3 in the catalog" && git push origin main
   ```
   Sincroniza solo la app que acabas de publicar: `sync` sin ids o con otros ids adelantaría también versiones ya publicadas que aún no quieras lanzar. `sync` consulta los manifiestos estáticos de GitHub Pages y no necesita token ni acceso al repo privado de la app. `sync` también actualiza la copia embebida (`app/src/main/assets/catalog.json`) y sube `revision`. El certificado debe coincidir con el anterior; si `sync` lo rechaza, no lo fuerces.
7. **Comprueba el catálogo publicado:**
   ```sh
   curl -s https://raw.githubusercontent.com/WillianChavez/winali/main/catalog/index.json
   ```
   Los teléfonos lo verán en su próxima comprobación (al abrir Winali o cada ~12 h). No hace falta recompilar Winali.

Si cambian la descripción, los permisos o las capturas de la ficha, edítalos en `catalog/index.json` (español y `locales.en`). Las capturas van en `docs/screenshots/<app>-N.png` y se enlazan con `https://raw.githubusercontent.com/WillianChavez/winali/main/docs/screenshots/...`. El README de Winali muestra solo las capturas de la tienda y no lleva una tabla de apps disponibles; documenta las funciones en el repositorio de cada app.

## Añadir una app nueva

1. Crea `apps/<app>` como repositorio propio copiando la estructura de `apps/apk` (Gradle, `tools/build-release.ps1`, `tools/release-manifest.mjs`, `LICENSE`, `README.md`, `CHANGELOG.md`), con paquete `io.github.willianchavez.<app>`. No añadas workflows.
2. Crea el repositorio **privado** con `gh repo create --private`, genera una clave de firma propia y guarda su JKS y JSON de credenciales en el directorio privado de firma, fuera del repositorio.
3. Compila y firma localmente `0.1.0` con el script, crea el tag y publica el APK y `release.json` en `winali-downloads` con `publish-download.ps1` (paso 5). La primera versión no tiene huella anterior para `-ExpectedCertificateSha256`; guárdala para las siguientes.
4. Añade su ficha a `catalog/index.json` con `"release": null`, ejecuta `node tools/catalog.mjs sync <app>` y actualiza el número de apps esperado en `tools/catalog.test.mjs`.

## Reglas del proyecto

- Licencias: `winali` (tienda) es MIT. Las apps son software propietario, todos los derechos reservados (`LICENSE` en cada repo): no añadas licencias de código abierto a una app ni copies un `LICENSE` MIT de `winali` al crear una app nueva (copia el de `apps/apk`).
- El código de las apps es privado: no lo copies, ni siquiera parcialmente, a `winali` ni a ningún repositorio público, y no publiques APK de depuración ni fuentes en `winali-downloads`. En `winali` solo van la tienda, el catálogo y las capturas e iconos de las fichas; en `winali-downloads` solo van APKs firmados, el servidor Windows firmado de Deck y sus manifiestos.
- Sin cuentas, anuncios, telemetría ni SDK de terceros. Sin permiso de Internet salvo en Winali y Deck (conexión LAN autorizada por el usuario).
- Sin textos de relleno ni eslóganes en la interfaz: solo texto funcional, en español e inglés.
- Release optimizada: R8, reducción de recursos, sin `material-icons-extended`, con Baseline Profile.
