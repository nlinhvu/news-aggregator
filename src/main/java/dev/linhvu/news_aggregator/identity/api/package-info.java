/**
 * Bề mặt công khai của module `identity`.
 *
 * Package này PHẢI được khai là named interface. Spring Modulith coi mọi
 * sub-package của module là NỘI BỘ theo mặc định — chỉ package gốc
 * (`…identity`) mới là API. Hôm nay chưa module nào chạm vào đây nên thiếu
 * annotation vẫn xanh; ngay khi `personalization` inject `CurrentUser` ở
 * Task 23, `ModuleBoundaryTest` sẽ đỏ bằng "Allowed targets: …" — đúng cách
 * `sources/api` và `catalog/api` đã trả giá ở hai phase trước. Khai sẵn để
 * không trả giá lần thứ ba.
 */
@NamedInterface("api")
package dev.linhvu.news_aggregator.identity.api;

import org.springframework.modulith.NamedInterface;
