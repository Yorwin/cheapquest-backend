package com.cheapquest.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FirebaseConfigTest {

    @Test
    void isInitialized_returnsFalseBeforeInitialize() {
        FirebaseConfig config = new FirebaseConfig(mock(AppProperties.class));
        assertThat(config.isInitialized()).isFalse();
    }

    @Test
    void initialize_returnsFalseWhenProjectIdMissing() {
        AppProperties props = mock(AppProperties.class);
        when(props.firebaseProjectId()).thenReturn("");
        when(props.firebaseCredentialsPath()).thenReturn("firebase-credentials.json");

        FirebaseConfig config = new FirebaseConfig(props);

        assertThat(config.initialize()).isFalse();
        assertThat(config.isInitialized()).isFalse();
    }

    @Test
    void initialize_returnsFalseWhenCredentialsPathMissing() {
        AppProperties props = mock(AppProperties.class);
        when(props.firebaseProjectId()).thenReturn("cheapquest-database");
        when(props.firebaseCredentialsPath()).thenReturn("");

        FirebaseConfig config = new FirebaseConfig(props);

        assertThat(config.initialize()).isFalse();
        assertThat(config.isInitialized()).isFalse();
    }

    @Test
    void initialize_returnsFalseWhenBothMissing() {
        AppProperties props = mock(AppProperties.class);
        when(props.firebaseProjectId()).thenReturn(null);
        when(props.firebaseCredentialsPath()).thenReturn(null);

        FirebaseConfig config = new FirebaseConfig(props);

        assertThat(config.initialize()).isFalse();
    }

    @Test
    void initialize_returnsFalseWhenCredentialsFileDoesNotExist(@TempDir Path tempDir) {
        AppProperties props = mock(AppProperties.class);
        when(props.firebaseProjectId()).thenReturn("cheapquest-database");
        when(props.firebaseCredentialsPath()).thenReturn(tempDir.resolve("missing.json").toString());

        FirebaseConfig config = new FirebaseConfig(props);

        assertThat(config.initialize()).isFalse();
    }
}
