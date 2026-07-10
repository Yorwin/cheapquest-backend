package com.cheapquest.backend.dao;

import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.dto.firebase.HydrationPatch;
import com.cheapquest.backend.exception.DocumentNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the game document aggregate (the
 * {@code games/{slug}} collection and its {@code locales} sub-map).
 * Firestore-backed surface for these operations
 * operations; implementations may use Firestore, SQL, or any other
 * store as long as the CRUD contract is preserved.
 *
 * <p>The interface is intentionally synchronous: each method
 * resolves its underlying future/blocking call before returning,
 * matching the DAO contract.
 */
public interface GameDao {

    /**
     * Create a new game document. Returns {@code true} if the
     * document was created, {@code false} if a document with the
     * given slug already exists.
     */
    boolean createIfNotExists(String slug, GameDocumentDto dto);

    /**
     * Read a single game document. Returns {@code Optional.empty()}
     * if the document does not exist.
     */
    Optional<GameDocumentDto> read(String slug);

    /**
     * Lazy iterable over every game document. The iterator is
     * single-use (each call to {@code iterator()} starts from the
     * first page). Materialise into a {@code List} if the caller
     * needs to walk the catalog more than once.
     */
    Iterable<GameDocumentDto> readAll();

    /**
     * Surgical partial update: only the fields present in the
     * {@link HydrationPatch} are rewritten (title, cheapshark,
     * rawg, validationReport). Throws
     * {@link DocumentNotFoundException} when the document does
     * not exist, so the caller can distinguish a missing doc from
     * a genuine backend failure.
     */
    void update(String slug, HydrationPatch patch);

    /**
     * Write the translated fields into the corresponding
     * {@code LocaleBlock} on the game document. Uses a partial
     * update so the rest of the document (and the rest of the
     * {@code locales} map) is untouched. {@code description} and
     * {@code tagTranslations} may be {@code null} to skip writing
     * those fields (e.g. when only the sync flag is being set).
     */
    void writeLocaleTranslation(String slug, String locale,
            String description, List<String> tagTranslations,
            Instant sourceFetchedAt, Instant translatedAt);

    /**
     * Mark a single locale as synced via a partial update on the
     * {@code locales} map. Only {@code synced} and {@code updatedAt}
     * are written, leaving the rest of the document untouched.
     */
    void markLocaleSynced(String slug, String lang, Instant syncedAt);
}
