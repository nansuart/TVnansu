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

- **Menú lateral** con iconos que se despliega al llegar a él.
- **Inicio** en bloques: arriba el destacado (con **vista previa en directo** del partido o evento, sin sonido) y el panel *En directo ahora*; debajo, filas separadas de películas y series recomendadas y de deportes y noticias en directo, *Seguir viendo*, últimos canales, Mi lista.
- **En vivo**: carrusel grande con el evento importante del momento, temas (Deportes, Noticias, Infantil, Cine y series, Documentales, Música), favoritos y últimos canales, y la guía completa con categorías, canales y programación.
- **Miniaturas de canales**: los últimos canales y los directos muestran una imagen de lo que emiten y hace cuánto se tomó. Se guardan al verlos, desde la vista previa y, si dejas la tele quieta en Inicio o En vivo, abriéndolos uno a uno unos segundos sin sonido (se puede desactivar en Ajustes).
- **Mosca de nansu tv** al 30 % en la esquina inferior izquierda, solo en canales en vivo (se puede quitar en Ajustes).
- **Perfiles** con avatar de color: cada uno guarda su Seguir viendo, Mi lista, recientes, subtítulos y animaciones.
- **Películas** y **Series** con carrusel de destacados (novedades, mejor valoradas y la colección del mes) y filas por categoría.
- **Más como esto** en cada ficha: títulos parecidos por colección, director o estudio, categoría, género, plataforma y año.
- **Series por plataforma** (Netflix, HBO Max, Apple TV+, Disney+…), con página propia y subcategorías.
- **Estudios, directores y listas** en Películas: Studio Ghibli, A24, Nolan, Almodóvar, Halloween en octubre, Navidad en diciembre, ganadoras del Óscar. El buscador también encuentra directores y estudios.
- Logos de canales completos: cada logo con su fondo, o en blanco si se prefiere (Ajustes).
- Fichas de película y de serie (temporadas, episodios, progreso de cada episodio).
- Buscador con teclado en pantalla.
- Reproductor con zapping, lista de canales, números de canal, audio y subtítulos, avance rápido y reanudación.
- **Subtítulos estilo Apple TV**: letra Inter, tamaños XS a XL (XS es el de Apple TV), sobre caja negra translúcida redondeada o con sombra suave. Suben cuando aparecen los controles.
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
- Tipografía de los subtítulos: [Inter](https://rsms.me/inter/) (SIL Open Font License, ver `licenses/Inter-OFL.txt`).

## Para desarrollo

Kotlin, Jetpack Compose for TV, Media3 (ExoPlayer) y Room. Cada cambio en `main` se compila
automáticamente en GitHub Actions y se publica como nueva versión.
