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

### Reglas del modelo
- `Game.name`, `Game.description`, `Game.developer`, `Game.publisher`, todos los `Review.text`, todos los `Tag.name` y todos los `Genre.name` se guardan en **inglés** (source of truth de RAWG).
- `Game.localized` se rellena con DeepL y solo contiene los idiomas efectivamente traducidos.
- `bestDeal` se calcula siempre desde `stores`, no se persiste independientemente.

---

## 4. API Mapping

| API | Método | Path | Propósito | Cliente |
|---|---|---|---|---|
| CheapShark | GET | `/games?title={name}` | Búsqueda por nombre | `CheapSharkClient.findByTitle` |
| CheapShark | GET | `/games?id={id}` | Detalle + deals | `CheapSharkClient.getDetails` |
| CheapShark | GET | `/stores` | Catálogo de tiendas | `CheapSharkClient.getStores` |
| RAWG | GET | `/games/{slug}` | Detalle completo | `RawgClient.getDetails` |
| RAWG | GET | `/games/{slug}/screenshots` | Capturas | `RawgClient.getScreenshots` |
| RAWG | GET | `/games/{slug}/movies` | Trailer | `RawgClient.getMovies` |
| RAWG | GET | `/games/{slug}/reviews` | Reviews | `RawgClient.getReviews` |
| DeepL | POST | `/v2/translate` | Traducción de textos | `DeepLClient.translate` |
| Firestore | GET | `/games/pending` | Lista de juegos a procesar | `FirebaseClient.readPending` |
| Firestore | SET | `/games/{lang}/{id}` | Upsert resultado | `FirebaseClient.upsert` |
| Firestore | SET | `/games/failed/{id}` | DLQ de fallos | `FirebaseClient.moveToFailed` |
| Firestore | SET | `/admin/lock` | Lock single-flight del refresh | `FirebaseClient.acquireLock` |

### Detalles importantes
- RAWG requiere `?key={RAWG_API_KEY}` en cada request.
- CheapShark **no** requiere API key.
- DeepL free plan → base URL `https://api-free.deepl.com`; Pro → `https://api.deepl.com`. Configurable en `application.properties`.
- Cada `RawgClient` debe recibir `slug` y devolver un `Game` parcial; el ensamblado final lo hace `GameAggregationService`.
- Rate limiting: CheapShark permite ~5 req/s sin auth; RAWG ~5 req/s con auth; DeepL free ~500k chars/mes. `RawgClient` y `CheapSharkClient` deben implementar backoff exponencial en `429`/`503`.

---

## 5. Pipeline Workflow

Para cada `gameId` en `/games/pending`:

1. **Lectura**: `FirebaseClient.readPending()` devuelve `List<String>` con los slugs/IDs.
2. **Búsqueda CheapShark**: `CheapSharkClient.findByTitle(game.name)` → mejor match por título (heurística: igualdad case-insensitive; fallback Levenshtein si RAWG devuelve 404).
3. **Detalle CheapShark**: `CheapSharkClient.getDetails(match.gameId)` → `List<Store>` (ofertas).
4. **Detalle RAWG**: `RawgClient.getDetails(slug)` → `Game` parcial con metadata, genres, tags, description, etc.
5. **Multimedia RAWG**: `RawgClient.getScreenshots(slug)` y `getMovies(slug)` → `List<String>` y `trailerUrl`.
6. **Reviews RAWG**: `RawgClient.getReviews(slug)` → `List<Review>`.
7. **Reviews CheapShark**: añadir desde `CheapSharkClient.getDeals(gameId).reviews` si vienen.
8. **ReviewMerger**: fusiona ambas listas. Si mismo `externalId` o `author`, RAWG sobrescribe CheapShark. Resto se concatenan.
9. **BestDealCalculator**: `stores.stream().max(Comparator.comparing(Store::savings))`.
10. **Ensamblado**: `Game` final con `stores`, `bestDeal`, `reviews`, `screenshots`, `trailerUrl`, `validationReport=null` de momento.
11. **Validación**: `ValidationService.evaluate(game)` → `ValidationReport` con `missingFields` exacto.
12. **Traducción**: `TranslationService.translate(game, Language.ES)` y `translate(game, Language.FR)`. `Language.EN` queda como está.
13. **Persistencia**: `FirebaseWriterService.upsert(game, lang)` por cada idioma **solo si** `validationReport.status != EMPTY`. Si `status == EMPTY`, `moveToFailed(game, error)`.
14. **Limpieza**: si todo OK, eliminar el `gameId` de `/games/pending` (best-effort, log si falla).

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

### Endpoint extra
```
GET /health
→ 200 OK { "status": "ok", "uptimeSeconds": N }
```
Sin auth, para liveness checks.

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
 │   │   │   │   └── Language.java                       # enum
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
 │   │   │   │       └── RefreshResponseDto.java
 │   │   │   ├── mapper/
 │   │   │   │   ├── CheapSharkMapper.java
 │   │   │   │   ├── RawgMapper.java
 │   │   │   │   └── ReviewMerger.java
 │   │   │   ├── service/
 │   │   │   │   ├── GameAggregationService.java
 │   │   │   │   ├── ValidationService.java
 │   │   │   │   ├── TranslationService.java
 │   │   │   │   ├── BestDealCalculator.java
 │   │   │   │   └── FirebaseWriterService.java
 │   │   │   ├── endpoint/
 │   │   │   │   ├── AdminRefreshEndpoint.java
 │   │   │   │   ├── HealthEndpoint.java
 │   │   │   │   └── ErrorResponse.java
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
