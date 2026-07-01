package com.cheapquest.backend.client;

import com.cheapquest.backend.config.AppProperties;
import com.cheapquest.backend.dto.firebase.GameDocumentDto;
import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper over the Firestore SDK that exposes only the operations
 * the backend needs against the games collection. Wraps every SDK
 * exception in {@link FirebaseUnavailableException} so callers do not
 * have to catch the Firestore exception hierarchy.
 *
 * <p>The collection path comes from {@code firestore.collection.games-path}
 * (default {@code games}); the document ID is the RAWG slug.
 */
public final class FirebaseClient {

    private static final Logger log = LoggerFactory.getLogger(FirebaseClient.class);

    private final Firestore firestore;
    private final String collectionPath;

    public FirebaseClient(Firestore firestore, AppProperties props) {
        this.firestore = firestore;
        this.collectionPath = props.firestoreCollectionGamesPath();
        log.debug("firebase_client_initialized collectionPath={}", collectionPath);
    }

    public List<GameDocumentDto> readAll() {
        QuerySnapshot snapshot = await("reading", collectionPath, () -> gamesCollection().get());
        List<GameDocumentDto> out = new java.util.ArrayList<>(snapshot.size());
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            out.add(doc.toObject(GameDocumentDto.class));
        }
        return out;
    }

    public Optional<GameDocumentDto> readOne(String slug) {
        DocumentSnapshot snap = await("reading", slug,
                () -> gamesCollection().document(slug).get());
        if (!snap.exists()) {
            return Optional.empty();
        }
        return Optional.of(snap.toObject(GameDocumentDto.class));
    }

    /**
     * Atomic create. Returns {@code true} if the document was created,
     * {@code false} if a document with that slug already exists.
     */
    public boolean createIfNotExists(String slug, GameDocumentDto dto) {
        DocumentReference ref = gamesCollection().document(slug);
        try {
            await("creating", slug, () -> ref.create(dto));
            return true;
        } catch (FirebaseUnavailableException e) {
            if (isAlreadyExists(e.getCause())) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Surgical update: only the fields present in {@code fields} are
     * rewritten. Fails if the document does not exist.
     */
    public void update(String slug, Map<String, Object> fields) {
        DocumentReference ref = gamesCollection().document(slug);
        await("updating", slug, () -> ref.update(fields));
    }

    private CollectionReference gamesCollection() {
        return firestore.collection(collectionPath);
    }

    /**
     * Resolves an {@link ApiFuture}, translating every SDK failure
     * (interruption, execution failure, runtime error) into a single
     * {@link FirebaseUnavailableException} tagged with the operation
     * and the subject. The cause of any wrapped exception is the
     * original throwable, so callers can still pattern-match on it.
     *
     * <p>The future is supplied lazily so that synchronous SDK failures
     * (e.g. a {@code get()} that throws before returning a future) are
     * also wrapped.
     */
    private static <T> T await(String op, String subject, Supplier<ApiFuture<T>> futureSupplier) {
        try {
            return futureSupplier.get().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirebaseUnavailableException("interrupted " + op + " " + subject, e);
        } catch (ExecutionException e) {
            throw new FirebaseUnavailableException("failed " + op + " " + subject, e.getCause());
        } catch (RuntimeException e) {
            throw new FirebaseUnavailableException("failed " + op + " " + subject, e);
        }
    }

    private static boolean isAlreadyExists(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof com.google.cloud.firestore.FirestoreException fe) {
            return fe.getStatus() != null
                    && fe.getStatus().getCode() == io.grpc.Status.Code.ALREADY_EXISTS;
        }
        if (t instanceof com.google.api.gax.rpc.ApiException api
                && api.getStatusCode() != null
                && api.getStatusCode().getCode() != null) {
            return api.getStatusCode().getCode()
                    == com.google.api.gax.rpc.StatusCode.Code.ALREADY_EXISTS;
        }
        return false;
    }
}
