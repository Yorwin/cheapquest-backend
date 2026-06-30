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

    public CollectionReference gamesCollection() {
        return firestore.collection(collectionPath);
    }

    public List<GameDocumentDto> readAll() {
        try {
            ApiFuture<QuerySnapshot> future = gamesCollection().get();
            QuerySnapshot snapshot = future.get();
            List<GameDocumentDto> out = new java.util.ArrayList<>(snapshot.size());
            for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
                out.add(doc.toObject(GameDocumentDto.class));
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirebaseUnavailableException("interrupted reading collection " + collectionPath, e);
        } catch (ExecutionException e) {
            throw new FirebaseUnavailableException("failed reading collection " + collectionPath, e.getCause());
        } catch (RuntimeException e) {
            throw new FirebaseUnavailableException("failed reading collection " + collectionPath, e);
        }
    }

    public Optional<GameDocumentDto> readOne(String slug) {
        DocumentReference ref = gamesCollection().document(slug);
        try {
            DocumentSnapshot snap = ref.get().get();
            if (!snap.exists()) {
                return Optional.empty();
            }
            return Optional.of(snap.toObject(GameDocumentDto.class));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirebaseUnavailableException("interrupted reading " + slug, e);
        } catch (ExecutionException e) {
            throw new FirebaseUnavailableException("failed reading " + slug, e.getCause());
        } catch (RuntimeException e) {
            throw new FirebaseUnavailableException("failed reading " + slug, e);
        }
    }

    /**
     * Atomic create. Returns {@code true} if the document was created,
     * {@code false} if a document with that slug already exists.
     */
    public boolean createIfNotExists(String slug, GameDocumentDto dto) {
        DocumentReference ref = gamesCollection().document(slug);
        try {
            ref.create(dto).get();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirebaseUnavailableException("interrupted creating " + slug, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof com.google.cloud.firestore.FirestoreException fe
                    && fe.getStatus() != null
                    && fe.getStatus().getCode() == io.grpc.Status.Code.ALREADY_EXISTS) {
                return false;
            }
            throw new FirebaseUnavailableException("failed creating " + slug, cause);
        } catch (RuntimeException e) {
            throw new FirebaseUnavailableException("failed creating " + slug, e);
        }
    }

    /**
     * Surgical update: only the fields present in {@code fields} are
     * rewritten. Fails if the document does not exist.
     */
    public void update(String slug, Map<String, Object> fields) {
        DocumentReference ref = gamesCollection().document(slug);
        try {
            ref.update(fields).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirebaseUnavailableException("interrupted updating " + slug, e);
        } catch (ExecutionException e) {
            throw new FirebaseUnavailableException("failed updating " + slug, e.getCause());
        } catch (RuntimeException e) {
            throw new FirebaseUnavailableException("failed updating " + slug, e);
        }
    }
}
