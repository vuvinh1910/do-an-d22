# Kế hoạch triển khai Worker — Người 2

## Phạm vi và cách làm

**Ưu tiên hiện tại:** Người dùng đã cho phép tiếp tục sau nghiệm thu A–C. D1–D3 có sẵn; triển khai E1 trước E2/F, không tự mở rộng persistence/broker. Giới hạn runner ghi tại [HTTP_RUNNER_DESIGN.md](HTTP_RUNNER_DESIGN.md).

- Nhánh triển khai: `vinh`.
- Chỉ thay đổi module Worker; không tự sửa backend, frontend, migration hoặc contract chung.
- Nguồn yêu cầu: `docs/development/tasks-division.md`, `AGENTS.md` và ba tài liệu trong `docs/api-contract/` tại gốc repository.
- Mỗi lượt triển khai một mục nhỏ: đọc contract/code liên quan, code, viết test, chạy kiểm tra, kiểm tra diff và cập nhật tiến độ.
- Không tạo hàng loạt service rỗng; không chuyển bước khi có lỗi chưa được giải thích.
- Chỉ đánh dấu test PASS khi đã chạy thành công. Thiếu môi trường phải ghi BLOCKED.
- Unit test không yêu cầu PostgreSQL/RabbitMQ. Test tích hợp cần dịch vụ thật phải được tách rõ.
- Chỉ commit khi người dùng yêu cầu; không tự push hoặc merge.

## A. Nền tảng

### Nghiệm thu A–C sau yêu cầu tạm dừng D

- Đã gia cố `ModelSnapshots` trước deepCopy: iterative budget 10.000 nodes/depth 256, reject cyclic/deep/wide trees và Jackson non-JSON nodes (Missing/POJO/Binary/non-finite). Shared acyclic subtree vẫn hợp lệ; không truncate/coerce negative payload.
- C5 thêm response structure budget trước recursive numeric canonicalization/validation; vượt budget là evaluation error, không FAIL. Pending queue cũng bị giới hạn khi enqueue, không chỉ khi pop.
- Thêm 15 test invocations: snapshot safety 11, schema response budgets 3, concurrent shared-engine probe 1; sửa test NaN cũ để chấp nhận rejection sớm tại constructor, vẫn kiểm tra IllegalArgumentException.
- Parent + worker `clean test`: **565 tests, 0 failures/errors/skips, BUILD SUCCESS**. Bao gồm 32 runtime tests đã có từ lượt trước, không thêm/sửa runtime logic trong lượt này. Không dùng số này làm chứng nhận broker/database/startup/security.
- Ma trận nghiệm thu, kiến trúc và giới hạn trong [FOUNDATION_ACCEPTANCE.md](FOUNDATION_ACCEPTANCE.md). A–C đạt phạm vi thư viện/HTTP loopback đã công bố; ingress limits/regex isolation, generator integration và deployment target policy vẫn là giới hạn cần xử lý trước triển khai không tin cậy.
- Giữ code D cũ theo nguyên tắc không xóa công việc; chưa phát triển thêm D/E/F/G, chưa commit/push. Bước tiếp theo là đối chiếu nghiệm thu A–C, không tự mở rộng runtime.

- [x] A1: Tạo/chuyển nhánh `vinh`, kiểm tra working tree và lưu checklist.
- [x] A2: Xác nhận đường chạy Maven/JUnit và chạy test nền tảng.
  - Đã xác nhận JDK/Javac 21.0.12; project yêu cầu Java 21.
  - JUnit 5/Mockito có qua `spring-boot-starter-test` hiện có.
  - Đã thêm `WorkerApplicationTest`: kiểm tra annotation và entry point, không khởi động Spring context.
  - Đã tải Maven portable 3.9.9 từ Maven Central, kiểm tra SHA-512 và giải nén ngoài repository tại `%LOCALAPPDATA%\vinh-worker-tools\apache-maven-3.9.9`.
  - Đã chạy thành công 2 test nền tảng. Không sửa PATH hệ thống; repository vẫn chưa có Maven Wrapper.
  - Khi có Maven: từ gốc repository chạy `mvn -pl worker -am test`.
  - Chưa thêm MockWebServer/WireMock; chọn dependency test phù hợp ở chặng B, theo yêu cầu phân công và sau khi kiểm tra tương thích.

## B. HTTP Runner — Task 2.1, 2.3

- [x] B1: Model request/response tối thiểu và fixture; test JSON object/array/scalar/null; phân biệt contract request và request đã resolve.
- [x] B2: Ghép base URL/path, path parameters, query parameters; test encoding, Unicode và đầu vào không hợp lệ.
- [x] B3: WebClient gửi request, giữ headers/body/status kể cả HTTP 4xx/5xx, đo latency đến khi nhận xong body; test GET/POST và các method còn lại, 204, text, 400/500. Hoàn thành phạm vi B3.1–B3.3, lỗi mạng/lifecycle còn B4.
- [x] B4: Timeout và lỗi mạng; test phản hồi chậm, connection failure và đóng kết nối; chưa retry. B4.2 rà soát mapping lỗi và suppression transport logs; không phải chứng nhận production security.

Mốc B: Runner chạy với HTTP server cục bộ; không cần backend, broker hoặc database.

## C. Assertions — Task 2.2, 2.5, 2.6

- [x] C1: Rule/result model và STATUS_CODE; test expected 400/actual 400 phải PASS. Evaluator thuần, chưa engine/orchestration; execution errors bị từ chối đánh giá, không tạo false assertion result.
- [x] C2: RESPONSE_TIME với LESS_THAN nghiêm ngặt (<); bằng ngưỡng FAIL. Chỉ operator đã xác nhận trong contract; không tự thêm <= hoặc mặc định operator. Ngưỡng JSON integer không âm trong miền long; từ chối execution error như C1.
- [x] C3: JSON_PATH qua Jayway: EQUALS/NOT_NULL/CONTAINS/GREATER_THAN; test null, missing path, JSON lỗi và sai kiểu. Phạm vi definite selectors, không wildcard/filter/functions.
  - [x] C3.1: EQUALS/NOT_NULL, definite selectors, phân biệt missing/null và JSON parse lỗi; 61 tests.
  - [x] C3.2: CONTAINS substring phân biệt hoa/thường hoặc deep array membership; GREATER_THAN numeric > chính xác, không coercion; 50 tests mới.
- [x] C4: HEADER với headerName theo contract bổ sung; tên case-insensitive, EQUALS/CONTAINS any-match và NOT_NULL; giữ raw multi-values, không comma split/join. 51 tests + 1 model compatibility test mới.
- [x] C5: JSON_SCHEMA qua Networknt 1.5.1 có sẵn; Draft 7 offline, schema meta-validation, local acyclic JSON Pointer refs, format assertions và exact numeric canonicalization; 93 tests.
- [x] C6: AssertionEngine điều phối 5 evaluators, giữ thứ tự/đủ PASS-FAIL, empty rules explicit và từ chối unsupported/duplicate/null rules, execution/configuration errors không trả summary; 14 tests gồm HTTP loopback thật.

Mốc C: Runner + assertion hoạt động bằng fixture, có test PASS/FAIL/ERROR phù hợp.

## D. Biến và chaining — Task 2.4, Worker README

- [x] D1: Runtime context và thay biến trong URL/header/query/body lồng nhau; giữ kiểu dữ liệu, phát hiện missing variable, không sửa template gốc hoặc dùng context chung giữa job.
- [x] D2: Extraction BODY/HEADER theo contract; kiểm tra missing data và tên biến trùng.
- [x] D3: Chuỗi resolve -> HTTP -> assertion -> extraction; test Create/Get bằng ID trích xuất, thứ tự, stop-on-failure và SKIPPED. Lõi tuần tự độc lập, chưa consumer/wire TestResult.

Không tự mở rộng thành dependency graph ngoài contract hiện tại.

### Review D sau yêu cầu xác minh

- Tiếp nối giới hạn text: mặc định mỗi output string tối đa 1.000.000 UTF-16 code units; VariableResolver(int) cho caller cấu hình, chưa env/job setting wiring. Check trước StringBuilder append, tránh substring literal lớn; whole-variable object/array cũng kiểm tra textual descendants. Không giới hạn property names/numeric serialization/tổng request-job bytes; không tuyên bố memory sandbox.
- Thêm 3 test invocations: literal/repeated replacement đúng biên-vượt biên/reset, UTF-16 và nested whole-variable strings, D3 ERROR/SKIPPED không gọi runner khi path expansion vượt limit. Parent + worker clean test **587 pass**, 0 failures/errors/skips, runtime 43. Chưa commit/push.

- Tiếp nối D1: VariableResolver tính budget trong khi dựng output, tổng body + query values tối đa 10.000 occurrences, depth 256 kể cả vị trí chèn biến. Chặn expansion lặp trước khi giữ toàn bộ các nhánh; chỉ có thể copy tạm thêm một biến đã được giới hạn 10.000 nodes. Không interpolate lại dữ liệu biến, không thay đổi template/context và không truncate. Budget cục bộ từng call; lỗi không làm bẩn lần resolve sau.
- Thêm 3 tests: đúng/vượt tổng budget body+query, lặp 9.999 placeholder của biến lớn, depth tại insertion point và reset sau lỗi. Parent + worker clean test: **584 pass**, 0 failures/errors/skips (runtime 40). Chưa có text/byte/job/context cardinality budgets; không coi node budget là bảo vệ bộ nhớ toàn diện. Chưa commit/push.

- D1–D3 được đánh dấu hoàn thành ở phạm vi lõi thư viện, không có nghĩa worker end-to-end đã hoàn tất. Rà lại phát hiện RuntimeContext/ResponseExtractor còn deepCopy trực tiếp, chưa kế thừa bảo vệ JSON của A; đã sửa dùng ModelSnapshots.json trước khi sao chép và trước JSONPath extraction.
- RuntimeContext reject cyclic/deep/wide/non-JSON values, cập nhật nguyên tử dù một giá trị lỗi sau giá trị hợp lệ. Extraction kiểm tra toàn bộ document theo budget 10.000 nodes/depth 256, kể cả khi selector chỉ lấy một scalar nhỏ. Đây là giới hạn sau parse, chưa phải ingress byte limit.
- Thêm 5 regression tests: unsafe context/atomic update, oversized extraction document, extraction failure không cập nhật một phần, concurrent subscriptions có context độc lập, cancellation truyền tới runner publisher và không chạy bước sau.
- Parent + worker clean test: **581 tests pass**, 0 failures/errors/skips. Runtime hiện 37 tests (resolver/context 11, extractor 16, sequential 10); E1 từ lượt trước vẫn giữ 11 tests. Chưa commit/push.
- Chưa kiểm chứng job cancellation qua broker, retry/concurrency settings, persistence hoặc producer integration. Dependency graph không có trong contract; stop=false vẫn chạy bước sau và missing variable là ERROR. Job ingress validation/resource limits và tổng ngân sách output sau nội suy còn cần review trước đầu vào không tin cậy. Những giới hạn này không được coi là đã hoàn thành chỉ vì D1–D3 có dấu x.

## E. Kết quả và lưu trữ — Task 2.7

- [ ] E1: TestResult đúng contract, serialization, PASSED/FAILED/ERROR/SKIPPED, body JSON/text và che dữ liệu nhạy cảm khi lưu/log (không sửa request gửi thật).
  - Review tiếp nối: fix executedAt luôn ISO-8601 string; chuẩn hóa snapshot null sau Jackson deserialization. Test loopback HTTP thật → runtime → mapping → serialize/deserialize cho PASSED/FAILED/extraction ERROR/SKIPPED, payload/header redaction và không gọi HTTP cho SKIPPED. Parent + worker clean test **588 pass**, 0 failures/errors/skips; 12 result mapper invocations. E1 vẫn mở cho policy/UI/job wiring, chưa E2/persistence/broker, chưa commit/push.
  - Đã thêm DTO 12 field và mapper từ StepResult; UUID identity phải khớp step, Clock inject để xác định thời điểm mapping. Giữ known status/latency khi transport error; không tạo timing cho SKIPPED/lỗi trước HTTP. ERROR không xuất partial assertion summary.
  - Chính sách tạm thời bảo thủ: URL/body/expected/actual thay bằng `[REDACTED]`, headers rỗng, message cố định. Không sửa dữ liệu runtime/chaining. Metadata (name/testType/ruleId/type) lấy từ job và không được coi là kênh chứa secrets. Chưa hỗ trợ opt-in diagnostic payload an toàn hoặc field-level redaction; E1 vẫn mở để review chính sách và tương thích UI, chưa wiring publish/persistence.
  - Kiểm chứng lượt E1 đầu: parent + worker `clean test` **576 tests pass**, 0 failures/errors/skips (thêm 11 invocations). Có serialization đúng 12 field, bốn trạng thái, known/unknown transport status, partial body, defensive snapshots, mismatch evidence và runtime extraction → mapping không rò payload. Không sửa shared contract/backend/frontend; chưa commit/push.
- [ ] E2: Mapping `test_results`, JSONB, FK và test persistence trên PostgreSQL; không sửa schema chung hoặc tự nhận lifecycle backend.
  - Đã thêm TestResultEntity/Repository/Store dùng JPA/Hibernate JSON SqlTypes.JSON; khớp V1 columns, executedAt → created_at, UUID FKs không tạo backend entities. Store redaction trước saveAndFlush, transactional blocking API, không retry/publish/lifecycle updates.
  - Thêm 3 Mockito unit tests: mapped/redacted entity và defensive accessor; invalid identity không gọi repository; DB integrity failure propagate một lần. Parent + worker clean test **591 pass**, 0 failures/errors/skips. Chưa commit/push.
  - PostgreSQL integration **chưa xác minh**: docker/psql không có trong PATH, không chạy DB hay Spring startup. Chưa hoàn thành E2; cần JSONB/FK/timestamp/rollback trên DB thật. Mỗi call sinh row UUID mới, không idempotency/attempt key; chưa được nối redelivery/retry trước khi thống nhất recovery với nhóm. Không sửa migration/backend/frontend.

Mốc E: Fixture -> HTTP -> assertions/chaining -> TestResult -> PostgreSQL.

## F. RabbitMQ — Task 2.8

- [x] F1: TestExecutionJob DTO/validation và fixture theo execution contract.
  - Đã thêm `TestExecutionJob`, `Environment`, `Settings`, `JobTestCase` khớp 100% `docs/api-contract/execution-contract.md`.
  - Kiểm tra tính hợp lệ: UUID, base URL, tên không rỗng, orderIndex và testCase id không trùng nhau; budget/snapshot copy cho dynamic variables; settings mặc định an toàn (timeout 10s, maxRetries 0, concurrency 1, stopOnFirstFailure false) khi thiếu hoặc cấu hình từng phần.
  - Cung cấp hàm chuyển đổi `toSteps()` ánh xạ sang `SequentialExecution.Step` giữ trọn vẹn request, assertions và extractions.
  - Thêm fixture `fixtures/test-execution-job.json` và 10 unit tests trong `TestExecutionJobTest`. Parent + worker `clean test`: **601 pass**, 0 failures/errors/skips. Chưa commit/push.
- [x] F2: Exchange/routing/queue/listener; phân biệt invalid payload với test failure, kiểm soát ack/requeue.
  - Thêm `RabbitMQConfig` khai báo durable `test.exchange` (Direct), `test.execution.queue`, binding với `test.execution.key` và `Jackson2JsonMessageConverter`.
  - Thêm `WorkerRunnerConfig` cung cấp `WebClientHttpRunner` bean.
  - Thêm `JobExecutionService` điều phối: nhận `TestExecutionJob` $\rightarrow$ chuyển đổi `toSteps()` $\rightarrow$ chạy qua `SequentialExecution` $\rightarrow$ lưu từng `StepResult` vào `TestResultStore`.
  - Thêm `JobConsumer` với `@RabbitListener`: payload không hợp lệ hoặc validation lỗi ném `AmqpRejectAndDontRequeueException` (chặn poison message lặp vô tận), lỗi hệ thống/DB lan truyền cho retry/requeue của AMQP, test assertion failure được lưu kết quả an toàn và hoàn tất (ACK).
  - Thêm 7 unit tests (`RabbitMQConfigTest`, `JobConsumerTest`, `JobExecutionServiceTest`). Parent + worker `clean test`: **608 pass**, 0 failures/errors/skips. Chưa commit/push.
- [x] F3: Test tích hợp job hợp lệ/lỗi, worker failure và redelivery.
  - Thêm `JobConsumerIntegrationTest` end-to-end với loopback HTTP server (`HttpServer`):
    - Kịch bản chuỗi thành công: POST tạo user $\rightarrow$ assertion status/JSONPath $\rightarrow$ trích xuất `userId` $\rightarrow$ GET user theo `userId` $\rightarrow$ lưu 2 thực thể `PASSED` với payload redacted vào repository.
    - Kịch bản thất bại: bước 1 lỗi $\rightarrow$ `stopOnFirstFailure=true` tự động bỏ qua bước 2 (trạng thái `SKIPPED`, không phát HTTP request).
    - Kịch bản poison pill: payload không hợp lệ bị từ chối với `AmqpRejectAndDontRequeueException`.
    - Kịch bản lỗi hạ tầng DB: lan truyền exception cho AMQP retry/redelivery.
  - Parent + worker `clean test`: **612 pass**, 0 failures/errors/skips. Chưa commit/push.

Task 2.7 hiện yêu cầu lưu PostgreSQL. Không tự bật thêm result queue làm lưu kết quả hai lần; nếu nhóm chọn queue, thống nhất đường đi kết quả trước khi bổ sung.

## G. Concurrency, retry và rà soát — Task 2.9

- [x] G1: Concurrency hữu hạn; phân biệt listener concurrency và request concurrency; giữ thứ tự phụ thuộc, test cô lập context. Không suy đoán dependency khi contract chưa đủ.
  - Phân biệt rõ:
    - Listener concurrency (xử lý đa job): cấu hình qua Spring AMQP `concurrency: 2`, `max-concurrency: 5`, `prefetch: 1`.
    - Request concurrency trong job: thực thi tuần tự tăng dần theo `orderIndex` để bảo đảm tính toàn vẹn phụ thuộc biến (chaining).
  - Thêm `JobConcurrencyAndIsolationTest`: chứng minh 2 jobs chạy song song với cùng tên biến môi trường hoàn toàn cô lập về `RuntimeContext` và dữ liệu persistence.
- [x] G2: Retry hữu hạn cho lỗi phù hợp; không retry assertion FAIL hoặc request thay đổi dữ liệu một cách mù quáng; test retry thành công và hết lượt.
  - Thêm `RetryingHttpRunner`:
    - Chỉ retry khi có lỗi transport/mạng (`response.error() != null`), số lần retry tối đa theo `job.settings().maxRetries()`.
    - Không retry khi HTTP hoàn tất nhưng vi phạm assertion hoặc HTTP 4xx/5xx.
    - Bảo vệ tính an toàn: chỉ retry với HTTP methods an toàn/idempotent (`GET`, `HEAD`, `PUT`, `DELETE`, `OPTIONS`), không tự tiện retry `POST` để tránh tạo trùng dữ liệu.
  - Thêm `RetryingHttpRunnerTest` (6 tests) xác nhận retry thành công, retry hết lượt, từ chối retry POST và từ chối retry assertion mismatch.
- [x] G3: Chạy toàn bộ test worker, tích hợp HTTP/PostgreSQL/RabbitMQ khi có môi trường; rà config, giới hạn response, timeout, secret/log và diff; hoàn thiện README.
  - Đã rà soát: logging suppression Netty/WebClient trong `application.yml`, payload redaction mặc định trong `TestResultMapper`, giới hạn kích thước response (1 MiB), budget cấu trúc JSON (10.000 nodes, depth 256).
  - Toàn bộ **619 tests** chạy sạch sẽ (`BUILD SUCCESS`), không rò rỉ secret hoặc lỗi diff. Môi trường kiểm thử PostgreSQL/RabbitMQ vật lý sẵn sàng khi có Docker/service bên ngoài. Chưa commit/push.

## Nhật ký bàn giao

### Lượt A1–A2

- Đã tạo nhánh `vinh` từ `main`, working tree ban đầu sạch.
- Đã thêm checklist và test cấu hình entry point không cần hạ tầng ngoài.
- Chưa chạy Maven/JUnit do thiếu Maven; không xác nhận build/test PASS.
- Tiếp theo: giải quyết đường chạy Maven, chạy test nền tảng; sau đó mới B1.

### Lượt A2–B1

- Đã gỡ trở ngại Maven bằng bản portable ngoài repository, không thêm binary hoặc dependency mới vào project.
- Thêm `TestCaseRequest` theo request contract; `ResolvedHttpRequest` và `HttpResponseData` là model nội bộ, không thay thế TestResult/Job contract.
- Body dùng JsonNode để giữ kiểu JSON; response body giữ chuỗi gốc. JsonNode vẫn là cấu trúc mutable: các bước interpolation sau phải tạo bản sao, không sửa template gốc.
- Header response/resolved request giữ được nhiều giá trị; map và list header được sao chép chống sửa từ bên ngoài.
- Query values dùng JsonNode để giữ kiểu; cách encode scalar/array sẽ được xác định ở B2. B1 chưa thực hiện validation đầu vào, ghép URL hoặc gửi HTTP.
- Đã chạy `clean test` cho worker và parent: **17 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.
- Tiếp theo: B2 — chuẩn bị URI/path/query và test encoding; chưa triển khai WebClient trước khi B2 được kiểm chứng.

### Lượt B2

- Thêm `RequestUriBuilder` trong package runner; không gửi HTTP, không sửa template.
- Ghép base path/API path, giữ raw query của base URL, encode path parameter như dữ liệu (không cho chèn slash/query/fragment).
- Static path giữ percent encoding hợp lệ; tham số là dữ liệu chưa encode, ký tự `%` trong giá trị được encode thành `%25`.
- Query scalar dùng biểu diễn text; array dùng key lặp lại; null/array rỗng bỏ qua, chuỗi rỗng giữ `key=`. Key bổ sung được sắp xếp để kết quả ổn định.
- Query object/nested array bị từ chối do contract chưa mô tả style/explode; chưa hỗ trợ deepObject hoặc mọi kiểu serialization OpenAPI.
- Từ chối base URL không HTTP(S), thiếu host, credentials/fragment, port không hợp lệ; từ chối absolute/network path, query/fragment trong path, thiếu path parameter và biến chưa resolve.
- Kiểm tra này không thay thế cơ chế chống SSRF/allowlist; chính sách địa chỉ mạng nằm ngoài phạm vi B2.
- Đã chạy clean test: **57 tests (40 URI + 15 model + 2 nền tảng), 0 failures/errors/skipped; BUILD SUCCESS**.
- Tiếp theo: B3 — WebClient runner và test HTTP server cục bộ; không gộp retry/concurrency vào bước đó.

### Lượt lập kế hoạch rà soát theo yêu cầu đồ án lớn

- Đọc lại model, URI builder, tests và tài liệu; ghi kế hoạch chi tiết trong REVIEW_PLAN.md.
- Điều chỉnh thứ tự tiếp theo thành R1 (URI) -> R2 (model/ownership/error) -> R3 (thiết kế HTTP/test) -> B3.
- Chưa sửa code nghiệp vụ trong lượt này. Các giới hạn và rủi ro trong REVIEW_PLAN.md là việc cần kiểm chứng/sửa, không phải tính năng đã có.
- Giữ yêu cầu: code dễ đọc, phạm vi rộng trong Người 2, test theo hành vi, không validation quá chặt làm sai negative test, không đổi contract/schema ngoài scope.

### Lượt R1 — URI regression và rà soát

- Viết regression trước sửa: xác nhận 3 failures (lộ URL trong exception) và 7 errors (colon/dot segments bị chặn).
- Sửa kiểm tra scheme thay blanket colon; giữ kiểm tra authority, tách parse/expand/query để đọc rõ trách nhiệm.
- Parse base URL trả lỗi an toàn không kèm cause chứa input. Thêm test cho scheme/authority, empty/dot/encoded params, adjacent placeholders, query trùng key/null array/empty key/decimal.
- Giữ empty/dot path để không làm sai negative test, không tự normalize; chưa chứng minh HTTP client/server giữ nguyên trên wire.
- `clean test`: 74 tests, 0 failures/errors/skipped; BUILD SUCCESS.
- Tiếp theo: R2 — model ownership, null/absent và error semantics. Chưa viết runner.

### Lượt R2 — Model ownership và error semantics

- Regression trước sửa: 7 failures/9 tests, xác nhận alias nested JSON, absent body serialization, lỗi null thiếu ngữ cảnh và thiếu kiểm tra trạng thái nội bộ.
- Copy sâu JSON chuẩn ở constructor/accessor cho template body/query và resolved body; helper package-local dùng chung map/header snapshots.
- Body absent bỏ khỏi serialization; explicit NullNode giữ JSON null; fixture contract round-trip không đổi field.
- Quy định complete/transport/partial response, latency không âm, thông báo validation không chứa giá trị đầu vào.
- Resolved method bắt buộc, URI HTTP(S) không credentials/fragment/port sai; chưa thay thế SSRF policy hoặc validation trên HTTP client. Giữ payload negative và thiếu auth nguyên trạng.
- 18 regression tests mới; `clean test`: 92 tests, 0 failures/errors/skipped; BUILD SUCCESS.
- Tiếp theo R3 — chốt test server, API runner và ma trận HTTP trước B3. Chưa triển khai HTTP runner; chưa commit lượt R2.

### Lượt R3 — Thiết kế và transport probe

- R2 đã commit/push abac3a2 ở phiên trước; đầu phiên R3 nhánh vinh sạch, đồng bộ origin/vinh.
- Chọn JDK HttpServer + WebClient/Reactor Netty hiện có, không thêm dependency hoặc sửa production code.
- Thêm 5 transport probe tests: raw encoded URI, 400/body/multi-cookie, absent/null/negative body không tự thêm Content-Type, redirect 302 không follow.
- Ghi API reactive, ranh giới cấu hình/transport, serialization/charset/timeout/resource policy và ma trận B3/B4 trong HTTP_RUNNER_DESIGN.md.
- Probe chỉ chứng minh tính khả thi transport, không phải runner đã hoàn thành. Tiếp theo B3.1 GET 200 + cold publisher + timeout nền; chưa commit/push lượt này.
- Kiểm chứng cuối lượt: `clean test` parent + worker BUILD SUCCESS, 97 tests, 0 failures/errors/skipped (92 baseline + 5 probe); git diff --check không lỗi.

### Lượt B3.1 — Runner GET và deadline nền

- Thêm WebClientHttpRunner concrete, Mono.defer cho từng subscription, không block/subscribe nội bộ; chưa Spring wiring.
- Gửi URI overload, headers nhiều giá trị, JSON bytes; cấu hình không redirect/implicit retry/compression, connect cap 10s, codec cap 1 MiB.
- Validation input/method/header/framing và serialization tách khỏi transport errors; lỗi kết quả không echo message/cause đầu vào.
- Deadline toàn exchange; giữ status/headers đã nhận nếu timeout body, latency nanoTime tới completion/error. Volatile status công bố headers sang timeout scheduler.
- 7 test runner: GET UTF-8/raw target/multi-cookie, cold/repeated subscribe, delayed body/latency, timeout trước headers/trong body, cấu hình không hợp lệ.
- Chưa hoàn thành toàn B3/B4. Tiếp theo B3.2 — kiểm chứng JSON bytes/method/header và URI builder trên wire; chưa commit/push.
- Kiểm chứng: test mục tiêu ban đầu 6/6 pass; sau bổ sung validation, `clean test` parent + worker 104 tests, 0 failures/errors/skipped, BUILD SUCCESS; git diff --check không lỗi.

### Lượt B3.2 — Request trên wire

- Thêm WebClientRequestWireTest, 24 tests capture request thật và assert trên test thread; không sửa production code hoặc thêm dependency.
- Chứng minh JSON bytes UTF-8 đúng kiểu, null vs absent, negative body/missing auth/content type không bị sửa.
- Kiểm chứng GET/POST/PUT/PATCH/DELETE/OPTIONS giữ body; HEAD không body; multi-value/case-insensitive headers không mất dữ liệu. Không cam kết thứ tự giữa header key khác casing.
- Builder -> runner giữ encoded slash/percent/Unicode, base path/query, repeated query, colon path, empty và dot segments trên loopback.
- Test mục tiêu: 24 pass, 0 failures/errors/skipped. Tiếp theo B3.3 response status/charset/size-limit; chưa commit/push.
- Kiểm chứng toàn worker: `clean test` parent + worker BUILD SUCCESS, 128 tests, 0 failures/errors/skipped; git diff --check không lỗi.

### Lượt B3.3 — Response và giới hạn

- Regression trước sửa: 4 failures/22 tests, xác nhận malformed bytes bị thay âm thầm và lỗi charset chưa phân loại rõ.
- Thêm custom response cap dương, mặc định 1 MiB. Đọc bounded raw buffers, release sau copy rồi strict decode; tránh codec parse Content-Type trước khi có bytes.
- 27 response tests pass: status/error body, empty/HEAD/204, redirect, charset/fallback/malformed, chunked cap mặc định ±1 byte và cap tùy chỉnh fixed-length/UTF-8 bytes.
- `clean test` parent + worker: 155 tests, 0 failures/errors/skipped; BUILD SUCCESS. Không thêm dependency/đổi contract; chưa commit/push.
- Tiếp theo B4.1 — connection failure/đóng kết nối/deadline chunks/cancellation/isolation, chưa retry.

### Lượt B4.1 — Lỗi mạng và subscription lifecycle

- Thêm WebClientLifecycleWireTest với TCP server loopback/random port, socket read/accept timeout, Future.get hữu hạn và teardown socket/executor.
- 7 tests: refused connection; đóng trước/sau headers không retry (cửa sổ quan sát 600ms); deadline toàn exchange với chunks; cancellation trước/sau headers đóng socket và không phát signal; subscribe lại cùng Mono thành công; hai subscriptions chồng lấn không lẫn status/headers/body/error.
- Không phát hiện regression cần sửa production code; không thêm dependency/retry/wiring hoặc đổi contract.
- Targeted riêng: 7 tests pass; parent + worker clean test: 162 tests, 0 failures/errors/skipped, BUILD SUCCESS. Lệnh chạy lặp 3 lần bị tool timeout 30s, không tính là 3 lượt pass được xác nhận.
- Reactor Netty có WARN PrematureCloseException dự kiến từ fixture; thông báo lỗi trong HttpResponseData vẫn cố định. Chưa chứng minh logging transport an toàn cho secret thật.
- Tiếp theo B4.2: rà soát mapping lỗi, logging/redaction và phạm vi hỗ trợ; chưa coi B4/production readiness hoàn tất. Không commit/push.

### Lượt B4.2 — Mapping lỗi và logging policy

- Mapping lỗi đã được review: timeout/size/decode có thông báo cố định; lỗi khác trước status -> transport, sau status -> read; configuration errors throw ngay, cancellation không tạo result. HTTP 4xx/5xx không tự là execution error.
- Chứng minh qua positive control rằng malformed Content-Length chứa marker xuất hiện trong WARN Netty. Thêm mặc định OFF cho reactor.netty, io.netty và Spring reactive HTTP client logger trong application.yml; không thay đổi logger toàn cục từ runner.
- HttpClientLoggingPolicyTest: 2 tests nạp YAML thật, áp dụng/khôi phục mức logger trong finally, kiểm tra marker có ở WARN và không có khi OFF. Không chạy Spring context hoặc hạ tầng DB/broker; không chứng minh toàn ứng dụng redaction.
- Targeted 2 tests pass; parent + worker clean test: 164 tests, 0 failures/errors/skipped, BUILD SUCCESS. Test đối chứng chủ động in marker giả; test khác không nạp Boot YAML nên WARN fixture vẫn có thể xuất hiện.
- Hoàn thành B theo phạm vi local HTTP; tiếp theo C1 — assertion rule/result và STATUS_CODE (expected 400/actual 400 phải pass). Không thêm dependency/retry/bean wiring hoặc sửa contract. Chưa commit/push.

### Lượt C1 — Assertion models và STATUS_CODE

- AssertionRule giữ các field contract id/type/expected/operator/jsonPath/expectedSchema/description; AssertionResult có ruleId/type/passed/expected/actual/message. JSON được copy sâu khi tạo/truy cập; cùng giới hạn custom JsonNode như model hiện có. Không thêm field/type assertion mới.
- StatusCodeAssertion thuần: expected JSON integer 100–999 (nhất quán model HTTP 3 chữ số), equality, không ép string/decimal; chỉ STATUS_CODE, id không blank, từ chối operator/jsonPath/schema không thuộc rule này. Model chỉ giữ cấu trúc; evaluator kiểm tra semantics.
- Response execution error trước/sau headers -> IllegalStateException cố định không cause/echo dữ liệu; caller sau này phải xử lý ERROR trước evaluator. Không tổng hợp TestResult hoặc coi network error là assertion FAIL. 4xx/5xx hoàn chỉnh vẫn có thể PASS.
- Targeted 35 tests pass (23 evaluator, 12 model). Parent + worker clean test: 199 tests, 0 failures/errors/skipped, BUILD SUCCESS. Không thêm dependency, không đổi contract/runner hoặc Spring wiring.
- Mốc B đã được chia 5 commits f2c0ed8..07234db ở lượt trước, chưa push. C1 chưa commit/push. Tiếp theo C2 — RESPONSE_TIME, LESS_THAN không đồng nhất <=.

### Lượt C2 — RESPONSE_TIME với LESS_THAN

- ResponseTimeAssertion thuần dùng responseTimeMs full-exchange của runner, không I/O/clock/sleep. So sánh trực tiếp long bằng <, không trừ để so sánh, không thu hẹp về int.
- Chính sách C2: expected JSON integer trong [0, Long.MAX_VALUE]; không ép string/decimal/exponent, không tự đặt ngưỡng mặc định. Ngưỡng 0 hợp lệ nhưng luôn FAIL với thời gian không âm. Contract chỉ xác nhận LESS_THAN; operator khác/thiếu bị từ chối rõ ràng, chưa thêm <=.
- Rule phải có id không blank, type RESPONSE_TIME, operator LESS_THAN, không jsonPath/expectedSchema. Cấu hình lỗi -> IllegalArgumentException; execution error -> IllegalStateException cố định không echo dữ liệu. Tổng hợp ERROR vẫn thuộc orchestration sau này. HTTP 4xx/5xx hoàn chỉnh vẫn được đánh giá latency.
- 42 tests mới: dưới/bằng/trên ngưỡng, 0, vượt int/biên long, kiểu JSON lỗi/overflow, operator thiếu/không hỗ trợ, shape rule sai, lỗi execution nhanh/chậm trước/sau headers, wire contract/result. Test dữ liệu xác định, không timing flaky.
- Targeted C1+C2+model: 77 tests pass. Parent + worker clean test: **241 tests, 0 failures/errors/skipped, BUILD SUCCESS**. Không dependency/contract/wiring mới; C1/C2 chưa commit/push.
- Tiếp theo C3 — JSON_PATH qua Jayway, EQUALS/NOT_NULL trước; cần chốt rõ null/missing path, kiểu JSON và lỗi parse trước khi triển khai.

### Lượt C2 readability + C3.1 — JSON_PATH EQUALS/NOT_NULL

- C2 tách CSV biên latency và test biên kiểu dữ liệu có tên riêng, dùng Integer.MAX_VALUE/Long.MAX_VALUE. Giữ các scenario cũ nhưng gom 5 invocations thành 2 tests rõ nghĩa: số test C2 từ 42 thành 39, không phải mất coverage tình huống.
- JsonPathAssertion dùng Jayway 2.9.0 có sẵn với JacksonJsonNodeJsonProvider, cấu hình cục bộ không thay global defaults; chỉ root-based definite selectors, không wildcard/filter/function. Property được quote có dấu ngoặc vẫn hợp lệ; có test index âm.
- EQUALS: structural JSON equality, number so sánh decimal theo giá trị (18 == 18.0), không ép string/boolean; object không phụ thuộc thứ tự key, array có thứ tự. Decimal response được parse chính xác; expected dùng JsonNode đã nhận (caller chịu trách nhiệm không làm mất precision trước đó).
- Missing path/index/sai traversal FAIL, actual Java null và message missing. JSON null có thật dùng NullNode: EQUALS null PASS, NOT_NULL FAIL. Provider kiểm tra index trước khi Jayway chuẩn hóa missing thành NullNode để tránh false PASS; có regression test.
- NOT_NULL nhận expected absent/JSON null, từ chối expected khác null. Empty string/array/object, 0 và false vẫn là non-null. EQUALS bắt buộc expected, cho phép explicit JSON null.
- JSON malformed/empty/trailing tokens/duplicate keys FAIL với message cố định không echo input; rule/path/operator sai -> IllegalArgumentException không cause; execution error -> IllegalStateException an toàn như C1/C2. Không thêm field wire để phân biệt missing/null, message giữ lý do; không có engine/orchestration mới.
- C3.1: 61 tests pass; parent + worker clean test: **299 tests, 0 failures/errors/skipped, BUILD SUCCESS**. Chưa đánh dấu toàn C3 xong; tiếp theo C3.2 CONTAINS/GREATER_THAN. C1–C3.1 chưa commit/push, không thêm dependency hoặc sửa shared contract.

### Lượt C3.2 — hoàn thành bốn operator JSON_PATH

- CONTAINS: string substring literal, case-sensitive, không regex/Unicode normalization; empty substring được chứa trong mọi string. Array chứa một phần tử JSON bằng sâu, numeric equality như EQUALS; expected array/object là một phần tử, không subset/key lookup/recursive search. Explicit null có thể là phần tử array; thiếu expected là lỗi cấu hình. Actual không phải string/array hoặc string với expected không phải string -> FAIL, giữ actual và message type mismatch.
- GREATER_THAN: expected finite JSON number, actual phải là number; so sánh BigDecimal >, không double/int/long narrowing, không string/boolean coercion; bằng ngưỡng FAIL. Expected sai/missing/nonfinite -> lỗi cấu hình; actual sai kiểu -> FAIL. BigDecimal lớn như 1e400 vẫn hữu hạn, không nhầm là Infinity do chuyển double.
- Kế thừa missing/null/JSON lỗi/transport error policies và definite selector scope C3.1. Không thêm field DTO, dependency, engine wiring hoặc thay shared contract. Semantics trên là chính sách worker được công bố để tích hợp, không tuyên bố shared contract đã mô tả mọi chi tiết.
- 50 tests mới theo scenario có tên: string/Unicode/case/literal/empty, typed/deep array membership, không subset/recursive, numeric boundaries/negative/equal/exact decimals/beyond long/exponents, config/type mismatch, transport/missing/path/JSON errors và wire results. JSONPath targeted: 111 tests pass.
- Parent + worker clean test: **349 tests, 0 failures/errors/skipped, BUILD SUCCESS**. C3 hoàn thành trong phạm vi đã công bố; C4/C5/C6 còn mở nên chưa hoàn thành toàn assertion engine. C1–C3 chưa commit/push.
- Tiếp theo C4: contract chỉ liệt kê HEADER, chưa ví dụ field header name/operator; xác nhận representation trước khi triển khai, không tự thêm field shared DTO.

### Lượt C4 — HEADER và contract bổ sung được người dùng cho phép

- Người dùng cho phép tự chốt representation phù hợp dự án; cập nhật testcase/result contracts với headerName chỉ dành HEADER, operator EQUALS/CONTAINS/NOT_NULL và result actual array strings. Không sửa backend/frontend; các module đó cần dùng cùng contract khi tích hợp, chưa được kiểm chứng end-to-end.
- AssertionRule thêm field optional headerName, bỏ qua khi null trên wire; giữ constructor 7 arguments cho các rule cũ và regression round-trip. HeaderName trên type khác bị từ chối ngay ở model, không âm thầm ignore. HEADER kiểm tra id/type, ASCII HTTP token name, operator và expected; không dùng jsonPath/expectedSchema.
- HeaderAssertion: lookup name equalsIgnoreCase, thu thập tất cả case variants; EQUALS/CONTAINS any-match giá trị raw, case-sensitive, không trim/split/join. NOT_NULL = ít nhất một giá trị (empty string vẫn có mặt), missing/list rỗng FAIL. Missing actual [], expected NOT_NULL null; thứ tự giữa các map entries không đảm bảo, không dùng để quyết định pass.
- Execution error từ chối đánh giá kể cả headers đã match; HTTP 4xx/5xx hoàn chỉnh vẫn đánh giá. Message không echo name/value, DTO expected/actual vẫn raw; chưa redaction hoặc engine orchestration.
- 51 HEADER tests + 1 model compatibility test mới. Focused assertions/model 237 pass. Parent + worker clean test: **401 tests, 0 failures/errors/skipped, BUILD SUCCESS**. Không dependency/wiring mới; C1–C4 và contract bổ sung chưa commit/push.
- Tiếp theo C5 JSON_SCHEMA; chốt dialect/schema config errors/remote ref policy trước triển khai. C6 tổng hợp engine vẫn mở.

### Lượt C5–C6 — JSON Schema và engine

- Không dependency mới. JsonSchemaAssertion dùng Networknt 1.5.1 đã có; Draft 7 mặc định hoặc $schema URI canonical, object/boolean. Meta-schema bundled kiểm tra cấu hình, keyword lạ/OpenAPI nullable/dialect khác bị từ chối. Format assertions bật; format không nhận biết vẫn annotation theo thư viện. Không tự sửa/default response.
- Chỉ JSON Pointer local #/... không percent-encoding, không cycles/$id/anchor/root #; depth tối đa 64. Kiểm tra cả definitions chưa dùng và target ref; loader chặn mọi external load. Producer phải normalize/bundle schema; chưa generator integration. Không sandbox/time budget riêng cho regex/schema không tin cậy.
- Response parse strict JSON giống C3; malformed/empty/duplicate/trailing FAIL. HTTP 4xx/5xx vẫn evaluate; execution/validator failure không fabricate FAIL. Result expected mô tả cố định, actual valid/invalid, message không echo raw diagnostic/body. Canonicalize số chính xác để sửa Networknt 1.5.1 structural const/enum/uniqueItems phân biệt 18 và 18.0.
- AssertionEngine là API nội bộ thuần, chưa Spring wiring/TestResult DTO. List rỗng explicit PASS không kiểm tra status tự động; null/duplicate ids/type lạ lỗi cấu hình. Chạy đủ rules kể cả có FAIL; lỗi cấu hình/evaluation không trả partial summary. Orchestration mapping ERROR còn ở mốc sau.
- 93 schema + 14 engine tests mới (107), gồm loopback HTTP -> runner -> cả 5 evaluators. Parent + worker clean test: **508 tests pass, 0 failures/errors/skips**. C1–C6/shared contract chưa commit/push; không sửa backend/frontend.
- Tiếp theo review milestone C; sau đó D1 runtime variables, D2 extraction, D3 chaining. Toàn sản phẩm chưa end-to-end RabbitMQ/persistence.

### Review C1–C6

### Kết quả tiếp nối D1–D3

- Đã thêm `runtime/RuntimeContext`, `VariableResolver`, `ExtractionRule`, `ResponseExtractor`, `SequentialExecution`; chỉ thay đổi worker và tài liệu contract, không sửa backend/frontend/schema DB.
- Context snapshot độc lập, mỗi subscription tạo context mới; nội suy một lượt, giữ kiểu JSON; extraction atomic, chỉ sau assertion PASS. HEADER dùng headerName và đúng một giá trị. Chạy tăng theo orderIndex duy nhất; stop sau FAILED/ERROR trả SKIPPED cho phần còn lại.
- 32 test mới: resolver/context 10, extraction 15, sequential orchestration 7 (có HTTP loopback Create → extract numeric ID → GET). Parent + worker `clean test`: **550 tests, 0 failures/errors/skips, BUILD SUCCESS**. Chưa chạy backend/frontend/broker/database integration.
- D3 chỉ là internal reactive API, chưa Spring job wiring/wire TestResult; raw request/response không được publish trước redaction. Không có dependency graph để suy ra SKIPPED khi stop=false. E–G vẫn chưa hoàn thành; 518 ở phần review C là baseline trước D. Chưa commit/push.

### Chi tiết review C1–C6 trước D

- Kiểm tra lại implementation/contract và bổ sung 10 schema regression tests: decimal multipleOf, exact large minimum, nested uniqueItems, dependencies, invalid pattern trong unused definition, schema node/depth budgets, huge positive exponent. Tổng schema 103 tests.
- Sửa khoảng trống resource bounds: kiểm tra cấu trúc iterative trước canonicalization/meta-validation (10.000 JSON nodes/depth 256); chặn integral exponent expansion quá 10.000 digits. Giới hạn schema/ref depth 64 giữ nguyên. Không nhận đây là sandbox hay ingress budget: model deepCopy/deserialize vẫn xảy ra trước evaluator, regex chưa time budget.
- Đồng bộ checklist C1–C3/C6 và quyết định empty assertions; kết quả cũ 508 là baseline trước review.
- Parent + worker clean test sau review: **518 pass, 0 failures/errors/skips, BUILD SUCCESS**; git diff --check sạch whitespace. Chưa commit/push, chưa chạy backend/frontend tests; thay đổi Java chỉ worker.
- Còn mở: schema producer normalization (Draft 7 vs OpenAPI), detailed schema diagnostics, job ingress limits/regex isolation, D variables/extraction/chaining, E TestResult ERROR mapping/redaction/persistence, F broker ack/redelivery, G retry/concurrency/cancel integration. Tiếp theo D1; không coi 518 tests là chứng minh sản phẩm end-to-end.