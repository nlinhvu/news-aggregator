Tên project: Website tổng hợp tin tức kỹ thuật (News Aggregator) với Java 25, Spring Boot 4 và Spring AI 2 (Hầu hết các dependencies mới nhất đều đã được để sẵn trong build.gradle, nếu cần chỉ cần uncomment nó).

Functional requirements:
1. Thu thập dữ liệu từ RSS các trang blog như Java, Spring, AWS,... và chứa chúng vào database.
2. Dùng Spring AI để tóm tắt nội dung, cho mỗi article, trả về tiêu đề, ngày xuất bản, đường dẫn đến bài gốc, và nội dung tóm tắt

Non-functional requirements:
1. Project này là modular monolith được implemented bởi Spring Modulith.
2. Project này cũng là IaC project (về sau sẽ tác riêng ra), sử dụng AWS CDK for Java cho Dev, QA, Prod, và Floci cho Local với Docker compose. Các tiêu chuẩn của best practices devops đều phải được áp dụng ở đây: mỗi directory/env hoặc là một cách nào khác để ci/cd cho IaC độc lập với ci/cd cho services và các môi trường độc lập với nhau. CDK for java phải được tạo bằng init trước sau đó mới thay đổi maven thành gradle thay vì generate thẳng.
3. Tech Stack được trọn trên AWS hiện tại phải là cost optimization. (đã có sẵn domain trên route53 là *.linhvu.dev)
4. Code nên chuẩn bị sẵn feature-toggle với togglz để cho các feature sẽ được add thêm sau này như: web scraping, Auto Categorization/Tagging, Smart Translation, Chatbot...