# Kế hoạch triển khai Worker — Người 2

## Phạm vi và cách làm

**Ưu tiên hiện tại:** Hoàn thành mốc B trong phạm vi HTTP loopback đã công bố; tiếp theo C1 — model assertion/result và STATUS_CODE theo contract. Giới hạn runner ghi tại [HTTP_RUNNER_DESIGN.md](HTTP_RUNNER_DESIGN.md).

- Nhánh triển khai: `vinh`.
- Chỉ thay đổi module Worker; không tự sửa backend, frontend, migration hoặc contract chung.
- Nguồn yêu cầu: `docs/development/tasks-division.md`, `AGENTS.md` và ba tài liệu trong `docs/api-contract/` tại gốc repository.
- Mỗi lượt triển khai một mục nhỏ: đọc contract/code liên quan, code, viết test, chạy kiểm tra, kiểm tra diff và cập nhật tiến độ.
- Không tạo hàng loạt service rỗng; không chuyển bước khi có lỗi chưa được giải thích.
- Chỉ đánh dấu test PASS khi đã chạy thành công. Thiếu môi trường phải ghi BLOCKED.
- Unit test không yêu cầu PostgreSQL/RabbitMQ. Test tích hợp cần dịch vụ thật phải được tách rõ.
- Chỉ commit khi người dùng yêu cầu; không tự push hoặc merge.

## A. Nền tảng

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

- [ ] C1: Rule/result model và STATUS_CODE; test expected 400/actual 400 phải PASS.
- [ ] C2: RESPONSE_TIME và toán tử; test đúng ngưỡng. Phân biệt LESS_THAN với phép so sánh <= trong mô tả task, không âm thầm đồng nhất.
- [ ] C3: JSON_PATH qua Jayway: EQUALS/NOT_NULL trước, CONTAINS/GREATER_THAN sau; test null, missing path, JSON lỗi và sai kiểu.
- [ ] C4: HEADER không phân biệt hoa/thường, hỗ trợ nhiều giá trị; xác định biểu diễn rule theo contract trước khi code.
- [ ] C5: JSON_SCHEMA qua Networknt; test required/type/enum/bounds/nested và schema lỗi; xác nhận dialect được hỗ trợ.
- [ ] C6: Tổng hợp engine; giữ kết quả từng rule, xử lý empty rules và unsupported rules rõ ràng.

Mốc C: Runner + assertion hoạt động bằng fixture, có test PASS/FAIL/ERROR phù hợp.

## D. Biến và chaining — Task 2.4, Worker README

- [ ] D1: Runtime context và thay biến trong URL/header/query/body lồng nhau; giữ kiểu dữ liệu, phát hiện missing variable, không sửa template gốc hoặc dùng context chung giữa job.
- [ ] D2: Extraction BODY/HEADER theo contract; kiểm tra missing data và tên biến trùng.
- [ ] D3: Chuỗi resolve -> HTTP -> assertion -> extraction; test Create/Get bằng ID trích xuất, thứ tự, stop-on-failure và SKIPPED.

Không tự mở rộng thành dependency graph ngoài contract hiện tại.

## E. Kết quả và lưu trữ — Task 2.7

- [ ] E1: TestResult đúng contract, serialization, PASSED/FAILED/ERROR/SKIPPED, body JSON/text và che dữ liệu nhạy cảm khi lưu/log (không sửa request gửi thật).
- [ ] E2: Mapping `test_results`, JSONB, FK và test persistence trên PostgreSQL; không sửa schema chung hoặc tự nhận lifecycle backend.

Mốc E: Fixture -> HTTP -> assertions/chaining -> TestResult -> PostgreSQL.

## F. RabbitMQ — Task 2.8

- [ ] F1: TestExecutionJob DTO/validation và fixture theo execution contract.
- [ ] F2: Exchange/routing/queue/listener; phân biệt invalid payload với test failure, kiểm soát ack/requeue.
- [ ] F3: Test tích hợp job hợp lệ/lỗi, worker failure và redelivery.

Task 2.7 hiện yêu cầu lưu PostgreSQL. Không tự bật thêm result queue làm lưu kết quả hai lần; nếu nhóm chọn queue, thống nhất đường đi kết quả trước khi bổ sung.

## G. Concurrency, retry và rà soát — Task 2.9

- [ ] G1: Concurrency hữu hạn; phân biệt listener concurrency và request concurrency; giữ thứ tự phụ thuộc, test cô lập context. Không suy đoán dependency khi contract chưa đủ.
- [ ] G2: Retry hữu hạn cho lỗi phù hợp; không retry assertion FAIL hoặc request thay đổi dữ liệu một cách mù quáng; test retry thành công và hết lượt.
- [ ] G3: Chạy toàn bộ test worker, tích hợp HTTP/PostgreSQL/RabbitMQ khi có môi trường; rà config, giới hạn response, timeout, secret/log và diff; hoàn thiện README.

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