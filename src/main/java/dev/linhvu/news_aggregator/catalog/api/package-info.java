/**
 * Bề mặt công khai của module `catalog`.
 *
 * Package này PHẢI được khai là named interface. Spring Modulith coi mọi
 * sub-package của module là NỘI BỘ theo mặc định — chỉ package gốc
 * (`…catalog`) mới là API. Từ Phase 1 tới Phase 2 chỗ này không có annotation
 * mà vẫn xanh, vì `ArticleSummaryDto` chỉ được chính `catalog` dùng. Ngay khi
 * `summarization` chạm vào `ArticleCatalog`, thiếu annotation này sẽ làm
 * `ModuleBoundaryTest` đỏ bằng "Allowed targets: …" — đúng cách
 * `sources/api/package-info.java` đã trả giá ở Phase 2.
 */
@NamedInterface("api")
package dev.linhvu.news_aggregator.catalog.api;

import org.springframework.modulith.NamedInterface;
