# Nghiệm thu nền tảng A–C trước khi tiếp tục D

## Phạm vi

Đánh giá model, URI builder, HTTP runner và năm assertion evaluators/engine. Không dùng tài liệu này để chứng nhận consumer RabbitMQ, TestResult, persistence, variables/chaining hoặc ứng dụng khởi động đầy đủ. Code D1–D3 đã có từ lượt trước được giữ nguyên nhưng không phát triển trong lượt nghiệm thu này.

## Luồng và trách nhiệm

```text
TestCaseRequest đã resolve
  → RequestUriBuilder (join + encode + validate URI)
  → ResolvedHttpRequest (snapshot JSON/headers)
  → WebClientHttpRunner (cold Mono, timeout, cap bytes, no retry/redirect)
  → HttpResponseData (complete response hoặc execution error)
  → AssertionEngine (ordered exhaustive rules)
  → Evaluation (PASS/FAIL; lỗi cấu hình/evaluation thì throw, không partial summary)
```

Engine không tự suy ra 2xx là PASS hoặc 4xx/5xx là FAIL. Mỗi rule quyết định theo expected; explicit empty assertions chỉ có nghĩa không có assertion thất bại. Tầng orchestration chịu trách nhiệm ERROR mapping sau này.

## Ma trận hỗ trợ và bằng chứng

| Thành phần | Hành vi được hỗ trợ | Bằng chứng |
|---|---|---|
| Model | absent body khác JSON null; defensive copies; map/list không mutable | RequestResponseModelTest, ModelOwnershipTest, AssertionModelTest |
| Snapshot safety | trước deepCopy: tối đa 10.000 nodes, depth 256; từ chối chu kỳ, Missing/POJO/Binary/NaN/Infinity | JsonSnapshotSafetyTest, gồm biên đúng giới hạn và shared acyclic subtree |
| URI | base path/query, percent encoding, Unicode, path params, repeated scalar query | RequestUriBuilderTest và WebClientRequestWireTest |
| HTTP | JSON bytes, methods REST, raw multi-headers, status kể cả 4xx/5xx, charset strict | WebClientHttpRunnerTest, WebClientRequestWireTest, WebClientResponseWireTest |
| Lifecycle | deadline toàn exchange, byte cap, no retry/follow redirect, cancellation, isolation | WebClientLifecycleWireTest, WebClientResponseWireTest |
| Logging | suppression transport logs bằng YAML mặc định, safe error string | HttpClientLoggingPolicyTest; không phải redaction |
| Status/latency | status equality; LESS_THAN nghiêm ngặt theo ms | StatusCodeAssertionTest, ResponseTimeAssertionTest |
| JSONPath | definite selector, EQUALS/NOT_NULL/CONTAINS/GREATER_THAN, numeric exact | JsonPathAssertionTest, JsonPathComparisonTest |
| Header | field-name case-insensitive, raw values, any-match, không comma split/join | HeaderAssertionTest |
| Schema | Draft 7 offline, strict parsing, local acyclic pointer refs, exact numeric equality | JsonSchemaAssertionTest |
| Engine | giữ thứ tự, đánh giá đủ rules sau FAIL; lỗi không partial summary | AssertionEngineTest: loopback HTTP qua cả 5 evaluators và concurrent shared-engine probe |

Snapshot budget tính số lần xuất hiện trong cây, không phải số identity object. Shared subtree không chu kỳ hợp lệ vẫn được copy thành các nhánh độc lập. Đây là giới hạn nội bộ, không sửa kiểu dữ liệu negative test và không cắt/truncate âm thầm.

Schema response vượt budget cấu trúc sau parse là evaluation error, không phải schema mismatch. Response JSON malformed bị FAIL theo contract. JSONPath chọn giá trị quá lớn có thể bị từ chối ở ranh giới AssertionResult snapshot; caller phải mapping evaluation error, không tự đổi thành FAIL.

## Giới hạn phải trình bày khi bảo vệ

- Giới hạn model xảy ra sau deserialize: chưa thay thế ingress byte/nesting limits của job listener. Tổng payload nhiều rule/query có thể lớn dù từng cây đều trong giới hạn.
- Không sandbox hoặc hard timeout cho Java regex/schema evaluation. Không triển khai pseudo-timeout bằng cách bỏ mặc thread regex còn chạy; cần isolation thích hợp khi nhận schema không tin cậy.
- Schema phải được producer normalize/bundle về Draft 7. Chưa chứng minh tích hợp generator OpenAPI 3.0/3.1; không hỗ trợ remote refs, root ref, anchors, cycles hoặc `$id`.
- JSONPath wildcard/filter/functions, query style/explode đầy đủ, raw/binary/multipart request, gzip decoding không thuộc scope hiện tại.
- DNS/TLS/proxy/SSRF allowlist và load/leak tests chưa được chứng minh. Không đưa worker ra Internet cho người không tin cậy chỉ vì local tests pass.
- Không Spring startup/job lifecycle/broker/database integration trong A–C. Low-level logger override hoặc log record/DTO có thể lộ raw dữ liệu; TestResult cần redaction riêng.

## Cách tái hiện

Kết quả nghiệm thu gần nhất: parent + worker `clean test` **565 tests pass**, 0 failures/errors/skips; 533 ngoài runtime và 32 runtime có sẵn. Lượt gia cố A–C thêm 15 invocations. Schema 106, engine 15, snapshot safety 11. Đây là kết quả tests thực tế, không chứng minh các giới hạn deployment ở trên đã được giải quyết.

JDK 21 và Maven 3.9.x, chạy tại thư mục gốc repository:

```powershell
mvn -B -ntp -pl worker -am clean test
git diff --check
```

Tests dùng loopback port ngẫu nhiên, không cần Internet/API target, RabbitMQ hay PostgreSQL. Xem XML trong `worker/target/surefire-reports` để phân biệt test count với số scenario. Không dùng số test làm chứng nhận production-ready.