# Winali

Una tienda Android para descubrir, instalar y actualizar aplicaciones publicadas en GitHub. Sin cuentas, anuncios ni backend propio.

Android 8 o superior. Kotlin, Jetpack Compose, tema claro/oscuro y español/inglés. Catálogo público con copia local; descargas verificadas y confirmación de instalación mediante Android.

## Desarrollo

Instala JDK 21, Android SDK con plataforma 36 y Build Tools 36.0.0. Define `JAVA_HOME` y `ANDROID_HOME`, o crea `local.properties` con `sdk.dir`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
node --test tools/catalog.test.mjs
node tools/catalog.mjs validate
```

## Publicar aplicaciones

Consulta [el estándar de catálogo](docs/CATALOG.md). Cada app vive en su repositorio y publica APK + release.json. Una actualización del catálogo basta para distribuir una nueva versión; no hay módulo de administración dentro de Winali.

Las Releases se firman con una clave propia persistente mediante GitHub Actions. Configura `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD` y `SIGNING_KEY_PASSWORD` como secretos del repositorio; alias `release`. Conserva una copia privada de la clave y las contraseñas. Nunca se incluyen en Git.

Las aplicaciones de referencia y cualquier material decompilado quedan fuera del repositorio. Winali y sus iconos son originales. Licencia MIT.

El estado de implementación y sus pruebas se registra en [el inventario de entrega](docs/IMPLEMENTATION.md). No se considera completa la paridad de Focus hasta pasar esa revisión.
