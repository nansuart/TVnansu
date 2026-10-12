# nansu tv

Reproductor personal de IPTV para Android TV y Google TV, con interfaz tipo Netflix / Apple TV.
Funciona con cuentas **Xtream Codes** (servidor, usuario y contraseña) y con **listas M3U**.
La app no incluye ningún contenido: solo reproduce las listas que añadas tú.

## Instalar en la tele

1. Instala **Downloader** (de AFTVnews) desde Google Play en la tele.
2. Ábrelo y escribe esta dirección:

   ```
   https://github.com/nansuart/TVnansu/releases/latest/download/TVnansu.apk
   ```

3. Acepta la instalación. La primera vez Android te pedirá permitir que Downloader instale apps.
4. Para actualizar, repite los mismos pasos: la nueva versión se instala encima y conserva tus listas, favoritos y progreso.

El archivo se sigue llamando `TVnansu.apk` para que la dirección de Downloader no cambie.

## Qué incluye

- Inicio con *Seguir viendo*, canales recientes, Mi lista y novedades.
- **En vivo**: categorías, lista de canales con lo que se emite ahora y ficha con la guía.
- **Películas** y **Series** en filas por categoría, con fondo grande que cambia al moverte.
- Fichas de película y de serie (temporadas, episodios, progreso de cada episodio).
- Buscador con teclado en pantalla.
- Reproductor con zapping, lista de canales, números de canal, audio y subtítulos (con tamaño ajustable), avance rápido y reanudación.
- Aviso de *Siguiente episodio* en los últimos 30 segundos de cada capítulo.
- Mantén pulsado OK sobre cualquier canal, película o serie para añadirlo a Mi lista o a favoritos.

## Controles del reproductor

| Botón | Películas y series | TV en vivo |
| --- | --- | --- |
| OK | Pausa / reanudar | Lista de canales |
| ◀ ▶ | Retroceder / avanzar (mantén para ir más rápido) | ◀ lista de canales, ▶ audio y subtítulos |
| ▲ | Audio y subtítulos | Canal anterior |
| ▼ | Mostrar controles | Canal siguiente |
| Números | — | Ir a un canal por número |
| Atrás | Salir (o seguir viendo los créditos si aparece *Siguiente episodio*) | Salir |

## Para desarrollo

Kotlin, Jetpack Compose for TV, Media3 (ExoPlayer) y Room. Cada cambio en `main` se compila
automáticamente en GitHub Actions y se publica como nueva versión.
