/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2022, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.stac.client;

import static java.util.Collections.singletonMap;

import com.bedatadriven.jackson.datatype.jts.JtsModule;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.geotools.data.geojson.GeoJSONReader;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.http.HTTPClient;
import org.geotools.http.HTTPResponse;

/**
 * Minimal STAC API client supporting the needs of the STAC datastore. Not currently meant to be a
 * generic STAC client.
 */
public class STACClient implements Closeable {

    /** Used to indicate how to perform search queries. */
    public static enum SearchMode {
        /** Forces usage of GET requests (might fail if parameters are too big/long) */
        GET,
        /** Forces usage of POST requests (might fail if not supported by the server) */
        POST,
        /** Will prefer POST if supported by the server, GET otherwise */
        AUTO
    }

    static final String JSON_MIME = "application/json";
    static final String GEOJSON_MIME = "application/geo+json";

    private static final Map<String, String> ACCEPTS_JSON = singletonMap("Accepts", JSON_MIME);
    static ObjectMapper OBJECT_MAPPER;

    /**
     * Initialize an ObjectMapper that's tolerant, won't generate missing fields, and can parse
     * GeoJSON geometries
     */
    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
        OBJECT_MAPPER.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
        OBJECT_MAPPER.registerModule(new JtsModule());
        OBJECT_MAPPER.setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);
    }

    private final HTTPClient http;

    private final STACLandingPage landingPage;

    public STACClient(URL landingPageURL, HTTPClient http) throws IOException {
        this.http = http;
        HTTPResponse response = http.get(landingPageURL, ACCEPTS_JSON);
        checkJSONResponse(response);
        try (InputStream is = response.getResponseStream()) {
            this.landingPage = OBJECT_MAPPER.readValue(is, STACLandingPage.class);
        }
    }

    private void checkJSONResponse(HTTPResponse response) {
        String mime = response.getContentType();
        if (mime == null || !mime.startsWith(JSON_MIME))
            throw new IllegalArgumentException(
                    "Was expecting a JSON response, got a different mime type: " + mime);
    }

    private void checkGeoJSONResponse(HTTPResponse response) {
        String mime = response.getContentType();
        if (mime == null || !mime.startsWith(GEOJSON_MIME))
            throw new IllegalArgumentException(
                    "Was expecting a GeoJSON response, got a different mime type: " + mime);
    }

    /**
     * Returns the landing page of the STAC service
     *
     * @return
     */
    public STACLandingPage getLandingPage() {
        return this.landingPage;
    }

    @Override
    public void close() throws IOException {
        if (http instanceof Closeable) {
            ((Closeable) http).close();
        }
    }

    /**
     * Returns the collection description, if a "data" link is available in the home page
     *
     * @return
     * @throws IOException
     */
    public List<Collection> getCollections() throws IOException {
        Optional<Link> maybeData =
                landingPage.getLinks().stream().filter(this::isDataJSONLink).findFirst();
        if (!maybeData.isPresent()) return Collections.emptyList();

        HTTPResponse response = http.get(new URL(maybeData.get().getHref()));
        checkJSONResponse(response);
        try (InputStream is = response.getResponseStream()) {
            return OBJECT_MAPPER.readValue(is, CollectionList.class).getCollections();
        }
    }

    private boolean isDataJSONLink(Link l) {
        return l.getRel().equals("data")
                && (StringUtils.isEmpty(l.getType()) || l.getType().equals(JSON_MIME));
    }

    public SimpleFeatureCollection search(SearchQuery search, SearchMode mode) throws IOException {
        if (!STACConformance.ITEM_SEARCH.matches(landingPage.getConformance()))
            // might want to take a different path and see if OGC API - Features is supported
            // instead
            throw new IllegalStateException(
                    "The server does not support the item-search conformance class, cannot query it");

        try {
            // prefer POST, supports large requests (intersection geometries, complex filters)
            if (mode == SearchMode.AUTO) {
                String postURL = landingPage.getSearchLink(HttpMethod.POST);
                if (postURL != null) mode = SearchMode.POST;
                else mode = SearchMode.GET;
            }

            HTTPResponse response = null;
            if (mode == SearchMode.GET) {
                URL getURL = new SearchGetBuilder(landingPage).toGetURL(search);
                response = this.http.get(getURL);
            } else {
                URL postURL = new URL(landingPage.getSearchLink(HttpMethod.POST));
                if (postURL == null) {
                    throw new IllegalArgumentException("Cannot find GeoJSON search GET link");
                }
                String body = OBJECT_MAPPER.writeValueAsString(search);
                response =
                        this.http.post(
                                postURL,
                                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                                "application/json");
            }
            checkGeoJSONResponse(response);

            // TODO: support paging following links
            return new GeoJSONReader(response.getResponseStream()).getFeatures();
        } catch (URISyntaxException e) {
            throw new IOException("Failed to build the search query URL", e);
        }
    }
}
