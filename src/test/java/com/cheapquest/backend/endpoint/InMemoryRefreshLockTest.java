package com.cheapquest.backend.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InMemoryRefreshLockTest {

    @Test
    void starts_unheld() {
        InMemoryRefreshLock lock = new InMemoryRefreshLock();
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void first_acquire_wins() {
        InMemoryRefreshLock lock = new InMemoryRefreshLock();
        assertThat(lock.tryAcquire()).isTrue();
        assertThat(lock.isHeld()).isTrue();
    }

    @Test
    void second_acquire_loses() {
        InMemoryRefreshLock lock = new InMemoryRefreshLock();
        lock.tryAcquire();
        assertThat(lock.tryAcquire()).isFalse();
        assertThat(lock.isHeld()).isTrue();
    }

    @Test
    void release_frees_the_lock() {
        InMemoryRefreshLock lock = new InMemoryRefreshLock();
        lock.tryAcquire();
        lock.release();
        assertThat(lock.isHeld()).isFalse();
        assertThat(lock.tryAcquire()).isTrue();
    }

    @Test
    void release_on_unheld_lock_is_noop() {
        InMemoryRefreshLock lock = new InMemoryRefreshLock();
        lock.release();
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void second_release_does_not_throw() {
        InMemoryRefreshLock lock = new InMemoryRefreshLock();
        lock.tryAcquire();
        lock.release();
        lock.release();
        assertThat(lock.isHeld()).isFalse();
    }
}
