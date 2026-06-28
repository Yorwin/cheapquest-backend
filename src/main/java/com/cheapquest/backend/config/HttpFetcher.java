package com.cheapquest.backend.config;

import com.cheapquest.backend.exception.ApiUnavailableException;

@FunctionalInterface
public interface HttpFetcher {

    String get(String url) throws ApiUnavailableException;
}
