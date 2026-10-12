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

- Inicio con carrusel de destacados, *Seguir viendo*, **En directo ahora** (deportes, eventos y noticias según la guía), canales recientes, Mi lista y novedades.
- **Perfiles** con avatar de color: cada uno guarda su Seguir viendo, Mi lista, recientes, subtítulos y animaciones.
- **En vivo**: categorías, lista de canales con lo que se emite ahora y ficha con la guía.
- **Películas** y **Series** en filas por categoría, con fondo grande que cambia al moverte.
- **Series por plataforma** (Netflix, HBO Max, Apple TV+, Disney+…), con página propia y subcategorías.
- **Estudios, directores y listas** en Películas: Studio Ghibli, A24, Nolan, Almodóvar, Halloween en octubre, Navidad en diciembre, ganadoras del Óscar. El buscador también encuentra directores y estudios.
- Logos de canales completos: cada logo con su fondo, o en blanco si se prefiere (Ajustes).
- Fichas de película y de serie (temporadas, episodios, progreso de cada episodio).
- Buscador con teclado en pantalla.
- Reproductor con zapping, lista de canales, números de canal, audio y subtítulos (con tamaño ajustable), avance rápido y reanudación.
- Aviso de *Siguiente episodio* en los últimos 30 segundos de cada capítulo.
- **Saltar intro**: marca una vez dónde empieza y acaba la intro de una serie (flecha arriba durante el episodio) y en los siguientes episodios aparece el botón.
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

## Colecciones

Los estudios, directores y listas están en [`colecciones.json`](colecciones.json). Para añadir uno, copia una línea,
cambia el nombre y pon su identificador de Wikidata (la Q con número que aparece en wikidata.org). La app lee el
archivo de aquí, así que los cambios llegan sin reinstalar.

## Fuentes de datos

- Plataformas de series: [TVmaze](https://www.tvmaze.com/api) (CC BY-SA 4.0).
- Estudios, directores y listas: [Wikidata](https://www.wikidata.org) (CC0).

## Para desarrollo

Kotlin, Jetpack Compose for TV, Media3 (ExoPlayer) y Room. Cada cambio en `main` se compila
automáticamente en GitHub Actions y se publica como nueva versión.
