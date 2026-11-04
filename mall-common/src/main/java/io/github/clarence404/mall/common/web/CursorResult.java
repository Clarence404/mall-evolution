package io.github.clarence404.mall.common.web;

import java.util.List;

/** 游标分页结果：不返回总数，nextCursor 对客户端不透明，原样回传即可。 */
public record CursorResult<T>(List<T> items, String nextCursor, boolean hasMore) {}
