package com.cheapquest.backend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppTest {

    @Test
    void main_class_is_loadable() {
        assertThat(App.class.getName()).isEqualTo("com.cheapquest.backend.App");
    }
}
