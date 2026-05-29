package com.sportsbook.betting.api;

import java.util.List;

/**
 * Cursor (keyset) page wrapper for time-series listings (ADR-0004). {@code nextCursor} is the betId
 * to pass back as {@code ?cursor=} for the next page; null when {@code hasMore} is false.
 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {}
