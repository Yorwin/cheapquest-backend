package com.cheapquest.backend.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cheapquest.backend.exception.FirebaseUnavailableException;
import com.google.api.core.SettableApiFuture;
import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.firestore.FirestoreException;
import io.grpc.Status;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FirestoreRetrierTest {

    private final FirestoreRetrier retrier = new FirestoreRetrier(2, 1L);

    @Test
    void await_returnsValueOnFirstSuccess() {
        SettableApiFuture<String> future = SettableApiFuture.create();
        future.set("ok");

        String result = retrier.await("op", "subject", () -> future);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void await_retriesOnTransientFirestoreExceptionAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        SettableApiFuture<String> success = SettableApiFuture.create();
        success.set("recovered");

        String result = retrier.await("op", "subject", () -> {
            if (calls.incrementAndGet() < 2) {
                SettableApiFuture<String> f = SettableApiFuture.create();
                f.setException(FirestoreException.forServerRejection(
                        Status.UNAVAILABLE, "network blip"));
                return f;
            }
            return success;
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void await_retriesOnAllTransientCodes() {
        for (Status.Code code : List.of(
                Status.Code.UNAVAILABLE,
                Status.Code.DEADLINE_EXCEEDED,
                Status.Code.INTERNAL,
                Status.Code.RESOURCE_EXHAUSTED,
                Status.Code.ABORTED)) {
            AtomicInteger calls = new AtomicInteger();
            SettableApiFuture<String> success = SettableApiFuture.create();
            success.set("ok");

            String result = retrier.await("op-" + code, "subject", () -> {
                if (calls.incrementAndGet() < 2) {
                    SettableApiFuture<String> f = SettableApiFuture.create();
                    f.setException(FirestoreException.forServerRejection(
                            Status.fromCode(code), code.name()));
                    return f;
                }
                return success;
            });

            assertThat(result).isEqualTo("ok");
            assertThat(calls.get()).as("code=%s", code).isEqualTo(2);
        }
    }

    @Test
    void await_throwsAfterExhaustingRetries() {
        AtomicInteger calls = new AtomicInteger();
        FirestoreException underlying = FirestoreException.forServerRejection(
                Status.UNAVAILABLE, "still down");

        assertThatThrownBy(() -> retrier.await("op", "subject", () -> {
            calls.incrementAndGet();
            SettableApiFuture<String> f = SettableApiFuture.create();
            f.setException(underlying);
            return f;
        }))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("failed op subject")
                .hasCause(underlying);

        // maxAttempts=2 → 1 initial + 2 retries = 3 invocations
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void await_doesNotRetryOnPermanentFirestoreError() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retrier.await("op", "subject", () -> {
            calls.incrementAndGet();
            SettableApiFuture<String> f = SettableApiFuture.create();
            f.setException(FirestoreException.forServerRejection(
                    Status.PERMISSION_DENIED, "denied"));
            return f;
        }))
                .isInstanceOf(FirebaseUnavailableException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void await_wrapsSupplierRuntimeException() {
        assertThatThrownBy(() -> retrier.await("op", "subject", () -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(FirebaseUnavailableException.class)
                .hasMessageContaining("failed op subject")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("boom");
    }

    @Test
    void isTransient_returnsFalseOnNull() {
        assertThat(FirestoreRetrier.isTransient(null)).isFalse();
    }

    @Test
    void isTransient_returnsFalseOnPlainRuntimeException() {
        assertThat(FirestoreRetrier.isTransient(new RuntimeException("boom"))).isFalse();
    }

    @Test
    void isTransient_returnsTrueForTransientFirestoreCodes() {
        for (Status.Code code : List.of(
                Status.Code.UNAVAILABLE,
                Status.Code.DEADLINE_EXCEEDED,
                Status.Code.INTERNAL,
                Status.Code.RESOURCE_EXHAUSTED,
                Status.Code.ABORTED)) {
            FirestoreException fe = FirestoreException.forServerRejection(
                    Status.fromCode(code), code.name());
            assertThat(FirestoreRetrier.isTransient(fe))
                    .as("code=%s", code).isTrue();
        }
    }

    @Test
    void isTransient_returnsFalseForPermanentFirestoreCodes() {
        for (Status.Code code : List.of(
                Status.Code.PERMISSION_DENIED,
                Status.Code.NOT_FOUND,
                Status.Code.ALREADY_EXISTS,
                Status.Code.INVALID_ARGUMENT)) {
            FirestoreException fe = FirestoreException.forServerRejection(
                    Status.fromCode(code), code.name());
            assertThat(FirestoreRetrier.isTransient(fe))
                    .as("code=%s", code).isFalse();
        }
    }

    @Test
    void isTransient_onApiException_isCurrentlyFalseForAllCodes() {
        // Documenting a pre-existing quirk: the ApiException branch
        // compares com.google.api.gax.rpc.StatusCode.Code values
        // against a list of io.grpc.Status.Code values, so the
        // contains() check always returns false. The retrier
        // therefore never retries on ApiException, even when the
        // code is in the transient set. Tracked as a follow-up;
        // a fix would need to change the TRANSIENT_CODES list type
        // to the gax Code enum, or compare names directly.
        StatusCode apiTransient = GrpcStatusCode.of(Status.Code.UNAVAILABLE);
        StatusCode apiPermanent = GrpcStatusCode.of(Status.Code.PERMISSION_DENIED);
        assertThat(FirestoreRetrier.isTransient(
                new ApiException("blip", null, apiTransient, false))).isFalse();
        assertThat(FirestoreRetrier.isTransient(
                new ApiException("denied", null, apiPermanent, false))).isFalse();
    }

    @Test
    void statusOf_returnsCodeNameForFirestoreException() {
        FirestoreException fe = FirestoreException.forServerRejection(
                Status.RESOURCE_EXHAUSTED, "throttled");
        assertThat(FirestoreRetrier.statusOf(fe)).isEqualTo("RESOURCE_EXHAUSTED");
    }

    @Test
    void statusOf_returnsCodeNameForApiException() {
        StatusCode apiCode = GrpcStatusCode.of(Status.Code.DEADLINE_EXCEEDED);
        ApiException api = new ApiException("slow", null, apiCode, false);
        assertThat(FirestoreRetrier.statusOf(api)).isEqualTo("DEADLINE_EXCEEDED");
    }

    @Test
    void statusOf_returnsClassNameForPlainThrowable() {
        assertThat(FirestoreRetrier.statusOf(new RuntimeException("boom")))
                .isEqualTo("RuntimeException");
    }

    @Test
    void computeBackoff_isAlwaysNonNegativeAndBounded() {
        // baseDelayMillis=1L, so backoff = (1 * 2^attempt) capped at
        // 2000, then jittered in [capped/2, capped). At attempt 0
        // the floor is 0 so the jittered value can be 0; this is
        // harmless in practice (a zero backoff is just "no
        // waiting"). The hard upper bound is the cap.
        for (int attempt = 0; attempt < 20; attempt++) {
            long delay = retrier.computeBackoffMillis(attempt);
            assertThat(delay).as("attempt=%d", attempt).isGreaterThanOrEqualTo(0L);
            assertThat(delay).as("attempt=%d", attempt).isLessThanOrEqualTo(2_000L);
        }
    }

    @Test
    void computeBackoff_atHighAttemptGrowsToCapRange() {
        // At attempt 16+ the exp term (1 * 2^16 = 65536) is well past
        // the cap, so all such attempts are within [1000, 2000).
        for (int attempt = 16; attempt < 20; attempt++) {
            long delay = retrier.computeBackoffMillis(attempt);
            assertThat(delay).isBetween(1_000L, 2_000L);
        }
    }

    @Test
    void computeBackoff_withLargerBaseIsAlwaysPositive() {
        // With base >= 2, the half is >= 1 and the jittered value is
        // always strictly positive.
        FirestoreRetrier r = new FirestoreRetrier(2, 2L);
        for (int attempt = 0; attempt < 5; attempt++) {
            long delay = r.computeBackoffMillis(attempt);
            assertThat(delay).as("attempt=%d", attempt).isPositive();
        }
    }

    @Test
    void defaultConstructor_usesDefaultBudget() {
        FirestoreRetrier defaultRetrier = new FirestoreRetrier();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> defaultRetrier.await("op", "subject", () -> {
            calls.incrementAndGet();
            SettableApiFuture<String> f = SettableApiFuture.create();
            f.setException(FirestoreException.forServerRejection(
                    Status.UNAVAILABLE, "down"));
            return f;
        }))
                .isInstanceOf(FirebaseUnavailableException.class);

        // Default maxAttempts=3 → 1 initial + 3 retries = 4 invocations.
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void zeroMaxAttempts_doesNotRetry() {
        FirestoreRetrier noRetry = new FirestoreRetrier(0, 1L);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> noRetry.await("op", "subject", () -> {
            calls.incrementAndGet();
            SettableApiFuture<String> f = SettableApiFuture.create();
            f.setException(FirestoreException.forServerRejection(
                    Status.UNAVAILABLE, "blip"));
            return f;
        }))
                .isInstanceOf(FirebaseUnavailableException.class);

        assertThat(calls.get()).isEqualTo(1);
    }
}
