# AGENTS.md — backend-cheapquest

Reglas e instrucciones persistentes para cualquier agente que trabaje en este repositorio. Este archivo es leído automáticamente por opencode en cada sesión y se versiona en git.

---

## 1. Project Overview

Backend Java 21 plano + Maven que enriquece una lista de juegos proveniente de **Firebase (Firestore)** con datos de **CheapShark** (ofertas/tiendas) y **RAWG** (detalles, multimedia, reviews), traduce los campos textuales con **DeepL** y persiste el resultado en Firestore con estructura multilingüe (`es`, `en`, `fr`).

El re-fetch periódico se dispara mediante un endpoint REST invocado por un cron externo (Programador de Tareas de Windows / cron de Linux).

### Casos de uso cubiertos
- Sincronización inicial: enriquecer todos los juegos de `/games/pending`.
- Actualización selectiva: re-fetch de campos vacíos reportados en `ValidationReport`.
- Recuperación: juegos fallidos en `/games/failed` con último error.

---

## 2. Stack & Tooling

| Componente | Tecnología | Justificación |
|---|---|---|
| Lenguaje | Java 17 LTS (mínimo), 21 LTS target | Records, sealed classes, pattern matching. `pom.xml` usa `release=17` porque es el JDK instalado; cambiar a 21 cuando esté disponible. |
| Build | Maven 3.9+ | Todo desde `pom.xml`; cero JARs en `libs/` |
| HTTP server | `com.sun.net.httpserver.HttpServer` (JDK) | Built-in, sin dependencias, suficiente para 2-3 endpoints admin |
| HTTP client | `java.net.http.HttpClient` (JDK) | Built-in |
| JSON | GSON `com.google.code.gson:gson` | API simple, encaja con records, sin módulos extra |
| DeepL | `com.deepl.api:deepl-java` (Maven Central) | Cliente oficial |
| Firebase | `com.google.firebase:firebase-admin` (Maven Central) | SDK oficial de Google |
| Logging | SLF4J + Logback | Estándar de facto |
| Tests | JUnit 5 + Mockito + AssertJ | Estándar |
| Cobertura | JaCoCo ≥ 85% en `domain` y `service` | Reporte en `target/site/jacoco/index.html` |

### Prohibido explícitamente
- JARs en `libs/` o pegados con `<systemPath>` en el `pom.xml`.
- "Dependencias internas" no declaradas en Maven.
- Valores hardcoded de API keys, URLs, tokens, intervalos, paths de credenciales.
- Múltiples clases públicas top-level en un mismo archivo.
- Field injection con `@Autowired` (no aplica al ser Java plano, pero tampoco se usará ningún container de DI oculto).

---

## 3. Domain Model

Todas las entidades de dominio son **inmutables**. Records cuando son DTOs de salida puros; clases `final` con campos `final` cuando hay comportamiento.

```
Game
 ├── id (String, slug de RAWG)
 ├── name (String)
 ├── description (String, EN original)
 ├── released (LocalDate)
 ├── playtime (Integer, horas)
 ├── developer (String)
 ├── publisher (String)
 ├── headerImage (String, URL)
 ├── trailerUrl (String, URL RAWG movie)
 ├── genres: List<Genre>
 ├── tags: List<Tag>
 ├── screenshots: List<String> (URLs RAWG)
 ├── stores: List<Offer>         ← ofertas de CheapShark
 │    ├── storeId
 │    ├── storeName
 │    ├── storeIconUrl
 │    ├── price (BigDecimal)
 │    ├── retailPrice (BigDecimal)
 │    ├── savings (BigDecimal, %)
 │    └── dealUrl (String)
 ├── bestDeal: Offer             ← la oferta con mayor savings
 ├── reviews: List<Review>       ← fusión CheapShark + RAWG
 │    ├── source (enum: CHEAPSHARK | RAWG)
 │    ├── externalId
 │    ├── author
 │    ├── rating (Double 0-5)
 │    ├── text (String, EN)
 │    └── externalUrl
 ├── localized: Map<Language, LocalizedGame>  ← se rellena tras DeepL
 └── validationReport: ValidationReport
      ├── status (enum: COMPLETE | PARTIAL | EMPTY)
      ├── missingFields: Set<GameField>  ← enum: GENRES, TAGS, SCREENSHOTS, TRAILER, HEADER_IMAGE, REVIEWS, STORES, DESCRIPTION, METADATA
      ├── lastFullFetchAt (Instant)
      └── lastPartialFetchAt (Instant)

GameDeals
 ├── gameId (String)
 ├── searchTitle (String)        ← el título que se usó para buscar (tal cual lo pasó el caller; útil para mostrar en el front y para re-buscar en otras APIs como RAWG)
 ├── name (String)               ← nombre canónico que devolvió la API
 ├── internalName (String)       ← slug interno de CheapShark (útil como candidato a RAWG slug)
 ├── thumb (String)
 ├── cheapestEver (BigDecimal, nullable)
 ├── offerCount (int)            ← total real de ofertas (N)
 ├── bestDeal (Offer, nullable)  ← la mejor, no se repite en offers
 └── offers: List<Offer>         ← SOLO las restantes (N-1, vacío si N≤1)

Offer (antes Store)
 ├── storeId (String)
 ├── storeName (String)
 ├── storeIconUrl (String, nullable)
 ├── price (BigDecimal)
 ├── retailPrice (BigDecimal)
 ├── savings (BigDecimal, %)
 └── dealUrl (String, nullable)

LocalizedGame
 ├── name
 ├── description
 ├── tags: List<String>           ← nombres traducidos
 ├── genres: List<String>         ← nombres traducidos
 └── reviews: List<Review>        ← review.text traducido

Language (enum): ES, EN, FR
```

### Sections (subcolección `sections/{YYYY-MM-DD}/items/{slug}` + `sections/latest/items/{slug}`)

```
SectionName (enum): POPULARES, NUEVAS_OFERTAS, VINTAGE, MEJORES_PROMOS, BAJOS_HISTORICOS
 ├── slug()        # wire-format: kebab-case ("mejores-promos", "nuevas-ofertas", ...)
 └── fromSlug()    # reverse lookup, Optional<SectionName>

SectionSnapshot
 ├── name (SectionName)
 ├── date (LocalDate, UTC)
 ├── computedAt (Instant)
 ├── totalCandidates (int)         # post-filter, pre-limit
 └── items: List<SectionItem>

SectionItem
 ├── slug (String)
 ├── title (String)
 ├── bestDeal (Offer, dominio)      # NO OfferDto: el mapper convierte en el límite
 ├── score (BigDecimal)            # sección-específico; mayor = mejor
 └── extra: Map<String, String>    # hints legibles: "savingsPct=66.70", "year=2014", ...

GameView                          # input de los SectionBuilder
 ├── slug (String)
 ├── title (String)
 ├── cheapshark (CheapsharkView, nullable)
 └── rawg (RawgView, nullable)

CheapsharkView
 ├── synced (boolean)
 ├── bestDeal (Offer, nullable)
 ├── cheapestEver (BigDecimal, nullable)
 └── offers: List<Offer>

RawgView (v1, crecerá con 'populares')
 ├── released (String, ISO)         # nullable
 ├── metacritic (Integer, 0-100)    # nullable
 └── rating (Double, 0-5)          # nullable
```

**Reglas:**
- `SectionItem.bestDeal` es SIEMPRE la mejor oferta del juego (la que `GameDeals.bestDeal` ya calculó). Un juego aparece como máximo una vez por sección aunque tenga 10 tiendas.
- `SectionSnapshot.totalCandidates` es el tamaño del set post-filtro y pre-límite: permite distinguir "sección corta porque el catálogo es pequeño" de "sección corta porque casi todo se filtró".
- `extra` es por-sección; las claves son estables por sección pero el contenido no (un builder puede añadir las suyas). Los valores son strings formateados.
- `score` es por-sección: el MejoresPromosBuilder lo usa para guardar el `savings` (0-100), un builder de "populares" lo usaría para una composite 0-1. El consumidor NO puede asumir rango fijo.
- Los builders son **puros**: leen `SectionContext.catalog` y devuelven `BuildResult(totalCandidates, items)`. Sin I/O.

### Reglas del modelo
- `Game.name`, `Game.description`, `Game.developer`, `Game.publisher`, todos los `Review.text`, todos los `Tag.name` y todos los `Genre.name` se guardan en **inglés** (source of truth de RAWG).
- `Game.localized` se rellena con DeepL y solo contiene los idiomas efectivamente traducidos.
- `bestDeal` se calcula siempre desde `stores`, no se persiste independientemente.

### RAWG 3-way merge (search + slug + id)

`RawgAggregationService.aggregate` ya no se queda con un único payload: dispara dos llamadas `/games/...` y un merge tolerante para que la página del juego tenga toda la información posible. La razón por la que hay dos detalles es la siguiente:

- **`getDetails(slug)`** es **mandatory**: sostiene la fase temprana de la hidratación, donde lo único conocido del juego es el slug. Si devuelve 404, `GameNotFoundException` y el juego va a `/games/failed` (contrato intacto).
- **`getDetails(id)`** (con el `id` que vino en la respuesta de la search) es **best-effort**: si falla con 5xx / 404 / 429 / `RuntimeException`, `RawgAggregationService` loggea `WARN rawg_details_by_id_failed id=… status=…` y continúa con la unión de search + slug. La pipeline no se rompe por un id-based call que falla.

Las dos llamadas devuelven prácticamente el mismo payload en RAWG (id y slug resuelven al mismo recurso), pero el merge existe por defensa: si RAWG cambiase el shape entre endpoints, o si un futuro campo solo apareciese en uno, el merge lo absorbe sin tocar la pipeline.

```
RawgAggregationService.aggregate(name)
  ├─ searchByName(name)                  → picked  (search DTO)
  ├─ getDetails(picked.slug())           → detail  (slug DTO, mandatory)
  ├─ safeGetDetailsById(picked.id())     → idOpt   (id DTO, best-effort)
  ├─ merge = idOpt.isPresent()
  │       ? mergeSearchAndDetails(picked, detail, idOpt.get())
  │       : detail                       ← short-circuit si la id call falló
  └─ toDetails(merged, …)                ← el resto de la pipeline
```

`mergeSearchAndDetails(search, detailsBySlug, detailsById)` en `RawgMapper` aplica estas reglas (todas en un único punto, testeadas en `RawgMapperMergeTest`):

- **Precedencia de escalares**: `detailsById > detailsBySlug > search`. Si la fuente preferente es `null`/blank/0, cae a la siguiente. Si dos fuentes no-blank discrepan, gana la preferente y se loggea `WARN rawg_field_mismatch field=X winningSource=id|slug|search idValue=… slugValue=…`.
- **Listas con `id`**: unión deduplicada por id, en orden `id-only → slug-only → search-only`. Las plataformas y los shortScreenshots tienen claves en records anidados, así que tienen helpers dedicados (`unionPlatforms`, `unionShortScreenshots`); los stores usan clave `long`, así que `unionByStoreId` con `Map<Long, …>`.
- **Listas de strings** (`alternativeNames`): unión por `equals`, mismo orden.
- **Counts** (`additionsCount`, `ratingsCount`, `parentsCount`, …, `suggestionsCount`): `max(a, b, c)`, sin WARN. RAWG puede quedarse corto unos minutos entre una sub-llamada y el `getDetails` padre; los counters son ruido permisivo.
- **Mapas** (`addedByStatus`, `reactions`): `mergeIntMap` con `max` por clave, claves unidas. Los contadores de popularidad solo crecen.
- **Booleans** (`tba`): OR entre las tres fuentes. Si cualquiera dice TBA, el juego es TBA.
- **Identidad** (`id`, `slug`): se asume consistencia; si difieren se loggea `WARN rawg_id_mismatch idById=… idBySlug=… - keeping id-based` y gana la preferente.

Los `addedByStatus` y `reactions` que aparecen en el `RawgDetails`/`RawgDocumentDto` final vienen del `mergeIntMap` (no de un pick escalar). Las poblaciones de listas se manejan en `RawgMapper.toDetails` con los helpers `toRatings`, `toClip`, `toEsrbRating`, `toStoreRef`, `toStoreEntries`, `toScreenshots`; los nuevos records de dominio `RawgRating`, `RawgClip`, `RawgEsrbRating`, `RawgStoreRef`, `RawgStoreEntry`, `RawgScreenshot` mirrorean los DTOs de RAWG (descartando los campos que no se persisten, igual que `RawgGenre`/`RawgTag`/etc.).

El resultado del merge se pasa luego a `mapper.toDetails(merged, …)` y desde ahí a `FirebaseMapper.toDocumentDto`, así que toda la información de RAWG que no estaba en el record original de 26 campos llega a Firestore con tipos explícitos. `RawgView` (proyección para los sections builders) gana a su vez `ratingsCount`, `additionsCount`, `addedByStatus`, `reactions`, `suggestionsCount` para que el "populares" builder los pueda ordenar.

---

## 4. API Mapping

| API | Método | Path | Propósito | Cliente |
|---|---|---|---|---|
| CheapShark | GET | `/games?title={name}` | Búsqueda por nombre | `CheapSharkClient.findByTitle` |
| CheapShark | GET | `/games?id={id}` | Detalle + deals | `CheapSharkClient.getDetails` |
| CheapShark | GET | `/stores` | Catálogo de tiendas | `CheapSharkClient.getStores` |
| RAWG | GET | `/games?search={name}` | Búsqueda por nombre (devuelve id + slug + summary) | `RawgClient.searchByName` |
| RAWG | GET | `/games/{slug}` | Detalle completo (mandatory, fase temprana) | `RawgClient.getDetails` |
| RAWG | GET | `/games/{id}` | Detalle completo (best-effort, segunda call tras el merge) | `RawgClient.getDetails` |
| RAWG | GET | `/games/{slug}/screenshots` | Capturas | `RawgClient.getScreenshots` |
| RAWG | GET | `/games/{slug}/movies` | Trailer | `RawgClient.getMovies` |
| RAWG | GET | `/games/{slug}/reviews` | Reviews | `RawgClient.getReviews` |
| RAWG | GET | `/games/{slug}/additions` | DLCs / sibling games | `RawgClient.getAdditions` |
| RAWG | GET | `/games/{slug}/development-team` | Creadores | `RawgClient.getDevelopmentTeam` |
| DeepL | POST | `/v2/translate` | Traducción de textos | `DeepLClient.translate` |
| Firestore | GET | `/games/pending` | Lista de juegos a procesar | `FirebaseClient.readPending` |
| Firestore | SET | `/games/{lang}/{id}` | Upsert resultado | `FirebaseClient.upsert` |
| Firestore | SET | `/games/failed/{id}` | DLQ de fallos | `FirebaseClient.moveToFailed` |
| Firestore | SET | `/admin/lock` | Lock single-flight del refresh | `FirebaseClient.acquireLock` |
| Firestore | GET | `/sections/{YYYY-MM-DD}/items/{slug}` | Lee snapshot histórico | `FirestoreSectionStore.read` |
| Firestore | GET | `/sections/latest/items/{slug}` | Lee mirror live | `FirestoreSectionStore.readLatest` |
| Firestore | GET | `/sections/latest/items/*` (collection) | Lee las 5 secciones live en un solo get | `FirestoreSectionStore.readAllLatest` |
| Firestore | SET | `sections/{YYYY-MM-DD}/items/{slug}` + `sections/latest/items/{slug}` (batch atómico) | Upsert de snapshot | `FirestoreSectionStore.write` |
| Endpoint | POST | `/admin/sections` | Recompute de las 5 secciones (bearer) | `AdminSectionsEndpoint` |
| Endpoint | POST | `/admin/sections/{name}` | Recompute de una sección (bearer) | `AdminOneSectionEndpoint` |
| Endpoint | GET | `/sections` | Lista de las 5 secciones con su último snapshot | `PublicSectionsListEndpoint` |
| Endpoint | GET | `/sections/{name}?date=YYYY-MM-DD\|live` | Lee un snapshot (público, sin auth) | `PublicSectionReadEndpoint` |

### Detalles importantes
- RAWG requiere `?key={RAWG_API_KEY}` en cada request.
- CheapShark **no** requiere API key.
- DeepL free plan → base URL `https://api-free.deepl.com`; Pro → `https://api.deepl.com`. Configurable en `application.properties`.
- `RawgClient.getDetails` se invoca **dos veces** por hidratación: una con el slug (mandatory, contrato intacto) y otra con el id (best-effort, complementa la primera vía `RawgMapper.mergeSearchAndDetails`). El id se toma de la respuesta del `searchByName` previo. Ver §3 "RAWG 3-way merge".
- Cada `RawgClient` debe recibir `slug` o `id` y devolver un `Game` parcial; el ensamblado final lo hace `GameAggregationService`.
- Rate limiting: CheapShark permite ~5 req/s sin auth; RAWG ~5 req/s con auth; DeepL free ~500k chars/mes. `RawgClient` y `CheapSharkClient` deben implementar backoff exponencial en `429`/`503`. La doble call RAWG duplica el presupuesto por juego pero, al ser best-effort la segunda, no introduce un cuello de botella nuevo bajo carga.

---

## 5. Pipeline Workflow

Para cada `gameId` en `/games/pending`:

1. **Lectura**: `FirebaseClient.readPending()` devuelve `List<String>` con los slugs/IDs.
2. **Búsqueda CheapShark**: `CheapSharkClient.findByTitle(game.name)` → mejor match por título (heurística: igualdad case-insensitive; fallback Levenshtein si RAWG devuelve 404).
3. **Detalle CheapShark**: `CheapSharkClient.getDetails(match.gameId)` → `List<Store>` (ofertas).
4. **Búsqueda RAWG**: `RawgClient.searchByName(name)` → `picked` (con `id` + `slug` + summary).
5. **Detalle RAWG (mandatory, fase temprana)**: `RawgClient.getDetails(picked.slug)` → `detailBySlug`. Si 404 → `GameNotFoundException` y a `/games/failed` (contrato intacto).
6. **Detalle RAWG (best-effort, segunda call)**: `safeGetDetailsById(picked.id)` → `detailById`. Si 5xx / 404 / 429 / `RuntimeException` → WARN `rawg_details_by_id_failed` y se continúa con la unión de search + slug.
7. **Merge tolerante**: `mapper.mergeSearchAndDetails(picked, detailBySlug, detailById)` produce un único `RawgGameDto` con la unión deduplicada (precedencia `id > slug > search`, listas por id, counts `max`, maps `mergeIntMap` con `max` por clave, booleans OR). Ver §3 "RAWG 3-way merge".
8. **Multimedia RAWG**: `RawgClient.getScreenshots(merged.slug)` y `getMovies(merged.slug)` → `List<String>` y `trailerUrl`. Sub-fetches usan `merged.slug()` por si el merge cambió algo.
9. **Reviews RAWG**: `RawgClient.getReviews(merged.slug)` → `List<Review>`.
10. **Reviews CheapShark**: añadir desde `CheapSharkClient.getDeals(gameId).reviews` si vienen.
11. **ReviewMerger**: fusiona ambas listas. Si mismo `externalId` o `author`, RAWG sobrescribe CheapShark. Resto se concatenan.
12. **BestDealCalculator**: `stores.stream().max(Comparator.comparing(Store::savings))`.
13. **Ensamblado**: `Game` final con `stores`, `bestDeal`, `reviews`, `screenshots`, `trailerUrl`, `validationReport=null` de momento. La info de RAWG sale de `mapper.toDetails(merged, …)` (23 campos extra del merge).
14. **Validación**: `ValidationService.evaluate(game)` → `ValidationReport` con `missingFields` exacto.
15. **Traducción**: `TranslationService.translate(game, Language.ES)` y `translate(game, Language.FR)`. `Language.EN` queda como está.
16. **Persistencia**: `FirebaseWriterService.upsert(game, lang)` por cada idioma **solo si** `validationReport.status != EMPTY`. Si `status == EMPTY`, `moveToFailed(game, error)`.
17. **Limpieza**: si todo OK, eliminar el `gameId` de `/games/pending` (best-effort, log si falla).

---

## 6. Multilingual Strategy

- Estructura Firestore: **`games/{lang}/{gameId}`** (colección por idioma). `lang ∈ {es, en, fr}`.
- **EN** = source of truth de RAWG, se guarda sin traducir.
- **ES** y **FR** = resultado de DeepL aplicado a:
  - `description`
  - `reviews[].text`
  - `tags[].name`
  - `genres[].name`
- **NO se traduce**: nombres de tiendas, URLs, IDs, métricas numéricas, fechas, ratings.
- Caché de traducción: campo `translatedAt` en cada `LocalizedGame`. Si `Instant.now() - translatedAt < cache-ttl-days` (configurable, default 30), se reusa la traducción previa.
- DeepL tiene un máximo de 50 strings por request → `TranslationService` debe agrupar y reusar batches.

---

## 7. Validation & Refresh System

### Estados posibles de un `Game`
- `COMPLETE`: ningún `missingField` crítico. No se re-fetchea.
- `PARTIAL`: uno o varios `missingField` rellenables. Se re-fetchea selectivamente.
- `EMPTY`: el ensamblado falló o el juego no existe en RAWG/CheapShark. Va a `/games/failed` y no se reintenta automáticamente.

### Re-fetch selectivo según `missingFields`

| `missingFields` | Endpoint a llamar |
|---|---|
| `{GENRES}` | `GET /games/{slug}` (el detalle trae genres) |
| `{SCREENSHOTS}`, `{HEADER_IMAGE}` | `GET /games/{slug}/screenshots` |
| `{TRAILER}` | `GET /games/{slug}/movies` |
| `{REVIEWS}` | `GET /games/{slug}/reviews` |
| `{STORES}` | re-fetch CheapShark + recalcular `bestDeal` |
| `size >= 4` o combinación heterogénea | refetch completo (todos los endpoints) |
| `{METADATA}` (developer, publisher, playtime, released) | `GET /games/{slug}` |

Cada re-fetch actualiza `validationReport.lastPartialFetchAt` o `lastFullFetchAt` según el caso.

### Manejo de fallos
- 3 intentos fallidos consecutivos sobre el mismo `gameId` → mover a `/games/failed` con `lastError`, `lastAttemptAt`, `attempts`.
- El cron puede re-probar juegos en `failed` con `lastAttemptAt < now - 7 días` (opcional, configurable).

---

## 8. Refresh Trigger (REST + cron externo)

### Endpoint
```
POST /admin/refresh
Authorization: Bearer ${ADMIN_REFRESH_TOKEN}
Content-Type: application/json

Body (opcional):
{
  "language": "es",       // si se omite, refresca los 3
  "force": false          // si true, ignora caché de traducción
}
```

### Respuestas
- `200 OK` con `{ "status": "completed", "processed": 42, "failed": 1, "durationMs": 12345 }`
- `409 Conflict` si ya hay un refresh en curso
- `401 Unauthorized` si el token no coincide
- `500 Internal Server Error` con `ErrorResponse` si algo explotó

### Lock single-flight
Antes de ejecutar, el endpoint intenta `set` en `/admin/lock` con TTL de 10 min. Si ya existe, devuelve 409.

### Disparador externo
**Windows (Task Scheduler):**
```powershell
$headers = @{ Authorization = "Bearer TU_TOKEN" }
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/admin/refresh" -Headers $headers
```
Configurado para correr cada 6h (configurable en `application.properties` con `admin.refresh.interval-hours`).

**Linux/macOS (cron):**
```bash
0 */6 * * * curl -X POST -H "Authorization: Bearer TU_TOKEN" http://localhost:8080/admin/refresh
```

### Ingesta de juegos
```
POST /admin/games
Authorization: Bearer ${ADMIN_REFRESH_TOKEN}
Content-Type: application/json

Body:
{
  "names": ["Stardew Valley", "Hades"],
  "language": "en"   // opcional, default "en", único valor aceptado
}
```

Inserta títulos en el pipeline: por cada nombre crea (idempotente) el documento en `games/{slug}` y lo encola en `pending/{slug}`. La hidratación posterior se dispara con `POST /admin/refresh` o con el cron. Tamaño máximo del batch: 100 (ver `GameIngestService.MAX_BATCH_SIZE`). La lista se deduplica preservando orden antes de procesar, así que reenviar el mismo batch es seguro y barato.

- `200 OK` con `{ "status": "completed", "accepted": [...], "failed": [...] }` — cada item de `accepted` lleva `{name, slug, action: "CREATED" | "ALREADY_EXISTED"}`, cada item de `failed` lleva `{name, error}`. **200 incluso si todos los nombres fallan**: un batch bien formado siempre devuelve 200; el caller inspecciona `failed`.
- `400 Bad Request` — body vacío, JSON malformado, `Content-Type` no JSON, `names` ausente/vacía, `language != "en"`, o tamaño del batch > `MAX_BATCH_SIZE`.
- `401 Unauthorized` — falta/mal token.
- `500 Internal Server Error` con `ErrorResponse` ante cualquier otro fallo (Firestore caído, etc.).

> Los títulos **deben** ir en inglés: RAWG y CheapShark se consultan por nombre y la pipeline usa `doc.title()` como clave de búsqueda. Un título en otro idioma acabaría en `/games/failed` en la primera hidratación sin error evidente.

### Inspección de colas
```
GET /admin/games?status=pending|failed
Authorization: Bearer ${ADMIN_REFRESH_TOKEN}
```

Devuelve el contenido de la cola de hidratación para inspección operativa (alternativa a la consola de Firebase). `status` es obligatorio y case-insensitive; `pending` lee de `pending/`, `failed` lee de `failed/`.

Respuesta `200 OK`:
```json
{
  "status": "pending",
  "count": 2,
  "entries": [
    { "slug": "portal", "attempts": 1,
      "firstAttemptAt": null,
      "lastAttemptAt": "2026-07-06T10:00:00Z",
      "lastError": null },
    { "slug": "hl2",    "attempts": 2,
      "firstAttemptAt": null,
      "lastAttemptAt": "2026-07-06T10:05:00Z",
      "lastError": "rawg 503" }
  ]
}
```

`firstAttemptAt` solo se rellena en entradas de la cola `failed` (los docs `pending` no lo llevan); en `pending` es `null` explícito. Timestamps en ISO-8601 (convención del resto de DTOs del proyecto).

- `200 OK` con `{ "status": "<echoed>", "count": N, "entries": [...] }` aunque la cola esté vacía.
- `400 Bad Request` — `status` ausente, vacío, o distinto de `pending`/`failed`; método distinto de `GET`.
- `401 Unauthorized` — falta/mal token.
- `500 Internal Server Error` ante cualquier otro fallo.

> El endpoint es de solo lectura: no encola, no reencola, no modifica nada. Para mover una entrada de `failed` de vuelta a `pending` se usa `replacePending` desde una Firebase Function o un script externo (ver §3 y `FirebaseClient`).

### Endpoint extra
```
GET /health
→ 200 OK { "status": "ok", "uptimeSeconds": N }
```
Sin auth, para liveness checks.

### Recompute diario de secciones (cron externo, 00:00 UTC)

El pipeline de `SectionsService` computa 5 snapshots diarios sobre la colección `games/` ya hidratada (sin llamadas a CheapShark/RAWG: la pipeline es offline). Cada snapshot se escribe atómicamente a `sections/{YYYY-MM-DD}/items/{slug}` (history) y a `sections/latest/items/{slug}` (mirror live) en un único `WriteBatch`. La pipeline se dispara desde el sistema operativo a las 00:00 UTC.

```
POST /admin/sections
Authorization: Bearer ${ADMIN_REFRESH_TOKEN}

Body: (ignorado, siempre recomputa las 5)
```

- `200 OK` con `SectionsResponseDto` y un resumen por sección (`COMPLETED` / `SKIPPED_NO_BUILDER` / `FAILED`) más totales `processed` y `failed`. 200 siempre; las secciones que fallan van como `FAILED` en el resumen, no como 5xx.
- `401 Unauthorized` si el token no coincide.
- `409 Conflict` si ya hay un recompute en curso (lock single-flight independiente del de hidratación).
- `500 Internal Server Error` con `ErrorResponse` si Firestore está caído u otro fallo no recuperable.

**Disparador externo:**

Windows (Task Scheduler):
```powershell
$headers = @{ Authorization = "Bearer TU_TOKEN" }
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/admin/sections" -Headers $headers
```
Configurado para correr a las 00:00 UTC diarias.

Linux/macOS (cron):
```bash
0 0 * * * curl -X POST -H "Authorization: Bearer TU_TOKEN" http://localhost:8080/admin/sections
```

**Recompute de una sola sección** (mismo contrato, una sola `SectionName`):
```
POST /admin/sections/{name}
Authorization: Bearer ${ADMIN_REFRESH_TOKEN}
```
- `name` es el slug kebab-case (`mejores-promos`, `nuevas-ofertas`, `vintage`, `populares`, `bajos-historicos`).
- `200 OK` con `SectionsResponseDto` de un solo elemento.
- `400 Bad Request` si el slug no corresponde a ninguna `SectionName` conocida.
- `401` / `409` / `500` igual que arriba.

### Lectura pública de secciones (sin auth)

El front consume los snapshots precomputados; los endpoints son públicos y no requieren token.

```
GET /sections
→ 200 OK {
    "status": "ok",
    "count": N,
    "sections": [
      { "name": "vintage",          "date": "2026-07-06", "totalCandidates": 8 },
      { "name": "mejores-promos",   "date": "2026-07-06", "totalCandidates": 5 }
    ]
  }
```
Lista las 5 secciones en orden canónico `SectionName`. Las secciones nunca computadas se omiten del array (no devuelven 404 — el caller las trata como "no disponible"). El campo `count` es el tamaño del array.

```
GET /sections/{name}?date=YYYY-MM-DD|live
→ 200 OK PublicSectionDto
→ 400 Bad Request si el slug o la fecha son inválidos
→ 404 Not Found si el doc no existe (la sección nunca se computó para esa fecha)
```

- `name` es el slug kebab-case.
- `date` por defecto es `live` (lee el mirror). `?date=YYYY-MM-DD` lee el snapshot histórico de ese día.
- El cuerpo es `PublicSectionDto` con `name, date, computedAt, totalCandidates, items[]`. Cada item lleva `slug, title, bestDeal, score, extra`.

---

## 9. Coding Standards

Aplicar el skill `java-coding-standards` (en `.agents/skills/java-coding-standards/SKILL.md`) con las siguientes adaptaciones a "Java plano sin framework":

### Inmutabilidad
- **Records** para todos los DTOs de entrada/salida de las APIs externas.
- Clases de dominio `final` con campos `final`, sin setters, builders solo cuando sean necesarios.
- `List`, `Map`, `Set` siempre wrapped en `List.copyOf(...)` o `Collections.unmodifiableList(...)`.

### Errores
- Excepciones unchecked específicas de dominio: `GameNotFoundException`, `ApiUnavailableException`, `TranslationFailedException`, `FirebaseUnavailableException`.
- `GlobalExceptionHandler` (centralizado, invocado desde los endpoints HTTP) mapea cada excepción a un `ErrorResponse { code, message, timestamp }` con el código HTTP apropiado.
- **Prohibido** `catch (Exception ex) { /* silently */ }`. Log con SLF4J y rethrow, o manejo explícito.
- **Best-effort sub-fetches**: los helpers `safeFetch(boolean, Supplier, label)` (poblado por `additions`/`creators`/`movies`/`screenshots`) y `safeGetDetailsById(int)` (poblado por la 2ª call RAWG) devuelven `List.of()` / `Optional.empty()` ante 404/5xx/rate-limit y ante `RuntimeException` no-`ApiUnavailableException`, loggeando `WARN rawg_subfetch_failed` o `WARN rawg_details_by_id_failed`. La pipeline no se rompe por un sub-fetch individual.

### Optional y nulls
- Métodos `find*` devuelven `Optional<T>`.
- `orElseThrow(() -> new GameNotFoundException(id))` en lugar de `.get()`.
- En `Game`, `bestDeal` es siempre no-null si `stores` no está vacío (lo garantiza `BestDealCalculator`).

### Logging
- Logger con `private static final Logger log = LoggerFactory.getLogger(MyClass.class);`
- Formato `key=value` para parseo fácil: `log.info("game_aggregated gameId={} status={}", id, status);`
- Niveles: `INFO` para eventos de negocio, `DEBUG` para detalles, `WARN` para fallos recuperables, `ERROR` para fallos persistentes.

### Configuración
- `AppProperties` carga `application.properties` desde `src/main/resources/`.
- Cualquier valor que dependa del entorno (API keys, paths de credenciales, tokens) → variable de entorno con `${VAR_NAME}` resuelto al cargar.
- `AppProperties` se inyecta manualmente a cada cliente en el `main` (factoría simple en `App.java`).

### Tests
- JUnit 5 + AssertJ para aserciones fluidas.
- Mockito para clientes externos; **nunca** mockear las clases bajo test.
- Tests de unidad: por cada `service` y cada `mapper`.
- Tests de integración: levantar `HttpClient` real contra endpoints mockeados con `WireMock` (añadir como dep de test).
- JaCoCo ≥ 85% en paquetes `domain` y `service`.

#### Stack de tests obligatorio
Todos los tests deben escribirse con **JUnit 5 (Jupiter)**, **AssertJ** para aserciones fluidas y **Mockito** para mocks de clientes externos. Las dependencias se declaran en `pom.xml` (§12). No se añaden frameworks de test adicionales.

---

## 10. Project Structure

```
backend-cheapquest/
 ├── pom.xml
 ├── AGENTS.md
 ├── src/
 │   ├── main/
 │   │   ├── java/com/cheapquest/backend/
 │   │   │   ├── App.java                                # main: bootstraps HttpServer + Firebase
 │   │   │   ├── config/
 │   │   │   │   ├── AppProperties.java                  # carga application.properties
 │   │   │   │   ├── FirebaseConfig.java                 # FirebaseApp.initializeApp
 │   │   │   │   └── HttpClientFactory.java
 │   │   │   ├── client/
 │   │   │   │   ├── CheapSharkClient.java
 │   │   │   │   ├── RawgClient.java
 │   │   │   │   ├── DeepLClient.java
 │   │   │   │   └── FirebaseClient.java
 │   │   │   ├── domain/
 │   │   │   │   ├── Game.java
 │   │   │   │   ├── Store.java
 │   │   │   │   ├── Review.java
 │   │   │   │   ├── Genre.java
 │   │   │   │   ├── Tag.java
 │   │   │   │   ├── ValidationReport.java
 │   │   │   │   ├── LocalizedGame.java
  │   │   │   │   ├── GameField.java                      # enum
  │   │   │   │   ├── ValidationStatus.java               # enum
  │   │   │   │   ├── ReviewSource.java                   # enum
  │   │   │   │   ├── Language.java                       # enum
  │   │   │   │   └── sections/                            # tipos del pipeline de secciones
  │   │   │   │       ├── SectionName.java                 # enum
  │   │   │   │       ├── SectionItem.java
  │   │   │   │       ├── SectionSnapshot.java
  │   │   │   │       ├── GameView.java
  │   │   │   │       ├── CheapsharkView.java
  │   │   │   │       └── RawgView.java
  │   │   │   ├── dto/
 │   │   │   │   ├── cheapshark/
 │   │   │   │   │   ├── CheapSharkGameDto.java
 │   │   │   │   │   ├── CheapSharkDealDto.java
 │   │   │   │   │   └── CheapSharkStoreDto.java
 │   │   │   │   ├── rawg/
 │   │   │   │   │   ├── RawgGameDto.java
 │   │   │   │   │   ├── RawgScreenshotDto.java
 │   │   │   │   │   ├── RawgMovieDto.java
 │   │   │   │   │   └── RawgReviewDto.java
 │   │   │   │   ├── deepl/
 │   │   │   │   │   └── DeepLTranslationDto.java
  │   │   │   │   └── admin/
  │   │   │   │       ├── RefreshRequestDto.java
  │   │   │   │       ├── RefreshResponseDto.java
  │   │   │   │       └── SectionsResponseDto.java
  │   │   │   │   ├── firebase/
  │   │   │   │   │   └── sections/                        # DTOs Firestore del pipeline de secciones
  │   │   │   │   │       ├── SectionSnapshotDto.java
  │   │   │   │   │       └── SectionItemDto.java
  │   │   │   │   └── public_/                              # DTOs públicos (sin auth)
  │   │   │   │       ├── PublicSectionDto.java
  │   │   │   │       └── PublicSectionListDto.java
  │   │   │   ├── mapper/
  │   │   │   │   ├── CheapSharkMapper.java
  │   │   │   │   ├── RawgMapper.java
  │   │   │   │   ├── ReviewMerger.java
  │   │   │   │   ├── FirebaseMapper.java                  # GameDocumentDto + OfferDto
  │   │   │   │   ├── OfferConverter.java                  # Offer <-> OfferDto (un solo lugar)
  │   │   │   │   ├── SectionSnapshotMapper.java            # SectionSnapshot <-> SectionSnapshotDto
  │   │   │   │   ├── GameViewMapper.java                  # GameDocumentDto -> GameView
  │   │   │   │   └── PublicSectionMapper.java             # domain / Report -> public / admin DTO
  │   │   │   ├── service/
  │   │   │   │   ├── GameAggregationService.java
  │   │   │   │   ├── ValidationService.java
  │   │   │   │   ├── TranslationService.java
  │   │   │   │   ├── BestDealCalculator.java
  │   │   │   │   ├── FirebaseWriterService.java
  │   │   │   │   └── sections/                            # pipeline de secciones (orquestador + persistencia + builders)
  │   │   │   │       ├── SectionsLock.java                 # interfaz (in-memory por ahora)
  │   │   │   │       ├── InMemorySectionsLock.java
  │   │   │   │       ├── SectionStore.java                 # interfaz (Firestore-backed)
  │   │   │   │       ├── FirestoreSectionStore.java
  │   │   │   │       ├── SectionContext.java
  │   │   │   │       ├── BuildResult.java
  │   │   │   │       ├── SectionBuilder.java               # interfaz
  │   │   │   │       ├── SectionsService.java              # orquestador (lock + catalog + dispatch)
  │   │   │   │       └── builders/
  │   │   │   │           └── MejoresPromosBuilder.java
  │   │   │   ├── endpoint/
  │   │   │   │   ├── AdminRefreshEndpoint.java
  │   │   │   │   ├── HealthEndpoint.java
  │   │   │   │   ├── ErrorResponse.java
  │   │   │   │   └── sections/                            # endpoints HTTP de secciones
  │   │   │   │       ├── SectionsPathUtils.java
  │   │   │   │       ├── AdminSectionsEndpoint.java        # POST /admin/sections
  │   │   │   │       ├── AdminOneSectionEndpoint.java      # POST /admin/sections/{name}
  │   │   │   │       ├── PublicSectionReadEndpoint.java    # GET  /sections/{name}
  │   │   │   │       └── PublicSectionsListEndpoint.java   # GET  /sections
  │   │   │   └── exception/
 │   │   │       ├── GameNotFoundException.java
 │   │   │       ├── ApiUnavailableException.java
 │   │   │       ├── TranslationFailedException.java
 │   │   │       ├── FirebaseUnavailableException.java
 │   │   │       └── GlobalExceptionHandler.java
 │   │   └── resources/
 │   │       ├── application.properties
 │   │       └── logback.xml
 │   └── test/
 │       ├── java/com/cheapquest/backend/   # espejo de main
 │       └── resources/
 │           ├── application-test.properties
 │           └── wiremock/                  # mappings para tests de integración
```

### Convenciones de nombres
- Clases/Records/Enums: `PascalCase`.
- Métodos/campos: `camelCase`.
- Constantes: `UPPER_SNAKE_CASE`.
- Paquetes: lowercase, sin guiones, singular cuando sea posible.
- Una clase pública top-level por archivo.

---

## 11. Configuration (`application.properties`)

```properties
# CheapShark
cheapshark.base-url=https://www.cheapshark.com/api/1.0
cheapshark.timeout-seconds=10
cheapshark.retry.max-attempts=3
cheapshark.retry.base-delay-millis=1000

# RAWG
rawg.base-url=https://api.rawg.io/api
rawg.api-key=${RAWG_API_KEY}
rawg.timeout-seconds=10

# DeepL (free plan: api-free.deepl.com; pro: api.deepl.com)
deepl.base-url=https://api-free.deepl.com
deepl.api-key=${DEEPL_API_KEY}
deepl.batch-size=50

# Firebase
firebase.project-id=${FIREBASE_PROJECT_ID}
firebase.credentials.path=${FIREBASE_CREDENTIALS_PATH}

# Admin endpoint
admin.refresh.token=${ADMIN_REFRESH_TOKEN}
admin.refresh.port=8080
admin.refresh.interval-hours=6
admin.refresh.lock-ttl-minutes=10

# Multilingual
supported.languages=es,en,fr
translation.cache-ttl-days=30
translation.default-source=en

# Refresh
refresh.max-retries=3
refresh.failed-cooldown-days=7

# Sections: per-section item quotas. The defaults below match
# AGENTS.md (populares=11, nuevas=8, vintage=8, mejores-promos=5,
# bajos-historicos=5). The cron recomputes the five snapshots at
# 00:00 UTC, writes to sections/{date}/items/{slug} for the history
# and sections/latest/items/{slug} for the live mirror.
sections.max-items.populares=11
sections.max-items.nuevas-ofertas=8
sections.max-items.vintage=8
sections.max-items.mejores-promos=5
sections.max-items.bajos-historicos=5
```

---

## 12. Maven `pom.xml` — Dependencias

Todas declaradas en `<dependencies>`, ninguna en `<systemPath>`.

```xml
<!-- Firebase Admin SDK -->
<dependency>
  <groupId>com.google.firebase</groupId>
  <artifactId>firebase-admin</artifactId>
  <version>9.4.1</version>
</dependency>

<!-- DeepL official Java client -->
<dependency>
  <groupId>com.deepl.api</groupId>
  <artifactId>deepl-java</artifactId>
  <version>1.8.0</version>
</dependency>

<!-- GSON (JSON) -->
<dependency>
  <groupId>com.google.code.gson</groupId>
  <artifactId>gson</artifactId>
  <version>2.10.1</version>
</dependency>

<!-- SLF4J + Logback -->
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-api</artifactId>
  <version>2.0.13</version>
</dependency>
<dependency>
  <groupId>ch.qos.logback</groupId>
  <artifactId>logback-classic</artifactId>
  <version>1.5.6</version>
</dependency>

<!-- Tests -->
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <version>5.10.2</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.mockito</groupId>
  <artifactId>mockito-core</artifactId>
  <version>5.12.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.assertj</groupId>
  <artifactId>assertj-core</artifactId>
  <version>3.26.3</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.wiremock</groupId>
  <artifactId>wiremock</artifactId>
  <version>3.13.0</version>
  <scope>test</scope>
</dependency>

<!-- JaCoCo (coverage) -->
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.12</version>
</plugin>
```

---

## 13. Convenciones operativas

- **Commits**: usar el comando `/commit` (definido en `.opencode/commands/commit.md`). Conventional Commits, español o inglés indistintamente, primera línea ≤ 72 caracteres.
- **Commits granulares**: preferiblemente un commit por bloque lógico (cliente, servicio, refactor, test). Hacer `git add` selectivo antes de invocar `/commit` para que solo entre al commit lo relevante al bloque. Pasar el bloque como argumento ayuda al agente a enfocar el mensaje, p. ej. `/commit CheapShark client with backoff`.
- **Branches**: `main` siempre deployable. Features en `feature/<slug>`, fixes en `fix/<slug>`.
- **PR**: descripción breve + checklist de tests + screenshot/log si aplica.
- **Secrets**: nunca en el repo. Solo `application.properties` con `${ENV_VAR}` y `.env.example` documentando las variables necesarias.
- **No committear** `firebase-credentials.json` ni `application-local.properties` con valores reales. Añadir ambos al `.gitignore`.

---

## 14. Recursos y referencias

- **Skills del proyecto** (en `.agents/skills/`):
  - `java-coding-standards` — convenciones de código Java
  - `java-architect` — arquitectura, testing, DDD (referencia conceptual aunque usemos Java plano)
- **APIs externas**:
  - CheapShark: https://apidocs.cheapshark.com/
  - RAWG: https://api.rawg.io/docs/
  - DeepL: https://developers.deepl.com/docs/getting-started/intro
  - Firebase Admin Java: https://firebase.google.com/docs/admin/setup
- **Custom command**: `/commit` para crear commits con Conventional Commits.
