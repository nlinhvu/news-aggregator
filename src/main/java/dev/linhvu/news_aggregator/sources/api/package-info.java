/**
 * Bề mặt công khai của module `sources`.
 *
 * Package này PHẢI được khai là named interface. Spring Modulith coi mọi
 * sub-package của module là NỘI BỘ theo mặc định — chỉ package gốc
 * (`…sources`) mới là API. Thiếu annotation này thì `SourceCatalog` gọi được
 * nhưng `SourceView` — đúng cái kiểu nó trả về — thì không, và
 * `ModuleBoundaryTest` đỏ bằng "Allowed targets: platform, sources" ngay lúc
 * `ingestion` chạm vào lượt đầu tiên.
 */
@NamedInterface("api")
package dev.linhvu.news_aggregator.sources.api;

import org.springframework.modulith.NamedInterface;
