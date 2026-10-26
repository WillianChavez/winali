# Iconos 3D

Deck es la referencia visual y no se modifica. Winali, APK, Focus, Read y Books comparten azul eléctrico, reflejos cian/menta, biseles redondeados, volumen y fondo azul noche. Read usa documentos; Books conserva el libro abierto.

Generados con la herramienta integrada de imágenes, usando `docs/icons/deck.png` como referencia de estilo, no como imagen a modificar. El prompt común pide una sola figura 3D centrada, formas gruesas redondeadas, iluminación superior izquierda, reflejos cian inferiores derechos y fondo #060D29, sin texto, marcas de agua ni marco exterior. Sujetos: W con diamante (Winali), caja abierta y flecha de extracción (APK), cronómetro y check (Focus), dos documentos superpuestos con esquina doblada (Read), libro abierto (Books).

[Prompts exactos](ICON-PROMPTS.md) · [Vista de familia y máscaras](../docs/icons/family-3d-preview.png).

Archivos finales de ficha: [Winali](../docs/icons/winali-3d.png), [APK](../docs/icons/apk-3d.png), [Focus](../docs/icons/focus-3d.png), [Read](../docs/icons/read-3d.png) y [Books](../docs/icons/books-3d.png). Los originales se guardan en `branding/icon-3d.png` de Winali y en `apps/{apk,focus,read,books}/branding/icon-3d.png` dentro de sus repositorios privados.

Cada repositorio conserva su original en `branding/icon-3d.png`; es la fuente de los nuevos recursos, no el antiguo `icon.svg`. `tools/prepare-brand-icons.py` deriva PNG de inicio/ficha de 512 px, copias offline de 256 px y la vista de familia. Solo copia imágenes de marca, nunca código privado. Requiere Python con Pillow.

El primer plano adaptativo tiene un margen de 8 % antes de aplicar las máscaras de Android. Se mantienen versiones monocromas para iconos temáticos. Las fichas utilizan URLs nuevas `*-3d.png` para no reutilizar la caché de los iconos anteriores. No cambian APK publicados, certificados ni versiones de release al preparar los recursos.

Validación: `node --test tools/brand-icons.test.mjs tools/catalog.test.mjs`; compilar recursos y lint de cada app, revisar `docs/icons/family-3d-preview.png` a tamaño pequeño. Este cambio visual no requiere repetir flujos de lectura, Pomodoro o extracción que no se han modificado. La publicación de APKs sigue el proceso de AGENTS.md.
