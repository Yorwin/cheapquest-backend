package com.cheapquest.backend.service.sections;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InMemorySectionsLockTest {

    @Test
    void starts_unheld() {
        InMemorySectionsLock lock = new InMemorySectionsLock();
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void first_acquire_wins() {
        InMemorySectionsLock lock = new InMemorySectionsLock();
        assertThat(lock.tryAcquire()).isTrue();
        assertThat(lock.isHeld()).isTrue();
    }

    @Test
    void second_acquire_loses() {
        InMemorySectionsLock lock = new InMemorySectionsLock();
        lock.tryAcquire();
        assertThat(lock.tryAcquire()).isFalse();
        assertThat(lock.isHeld()).isTrue();
    }

    @Test
    void release_frees_the_lock() {
        InMemorySectionsLock lock = new InMemorySectionsLock();
        lock.tryAcquire();
        lock.release();
        assertThat(lock.isHeld()).isFalse();
        assertThat(lock.tryAcquire()).isTrue();
    }

    @Test
    void release_on_unheld_lock_is_noop() {
        InMemorySectionsLock lock = new InMemorySectionsLock();
        lock.release();
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void second_release_does_not_throw() {
        InMemorySectionsLock lock = new InMemorySectionsLock();
        lock.tryAcquire();
        lock.release();
        lock.release();
        assertThat(lock.isHeld()).isFalse();
    }
}
