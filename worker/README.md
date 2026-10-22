# Worker Service (Test Execution Engine)

## G — Concurrency, Retry Policy & Production Review

Đã hoàn thiện các cơ chế đồng thời, retry an toàn và rà soát chất lượng toàn diện:
- **Concurrency & Context Isolation (G1)**:
  - Phân biệt rõ Listener Concurrency (xử lý nhiều job cùng lúc qua Spring AMQP worker pool) và Request Concurrency trong từng job (chạy tuần tự theo `orderIndex` để bảo đảm tính toàn vẹn phụ thuộc biến).
  - Kiểm chứng qua `JobConcurrencyAndIsolationTest`: các job chạy đồng thời trên nhiều luồng khác nhau với các biến cùng tên (`auth_token`) hoàn toàn độc lập, không rò rỉ context hoặc race condition.
- **Retry Policy An Toàn (G2)**:
  - Thêm `runner/RetryingHttpRunner`: chỉ tự động retry tối đa `maxRetries` khi xảy ra lỗi transport/mạng thực sự (`response.error() != null`).
  - Tuyệt đối không retry khi assertion FAIL (HTTP 4xx/5xx nhận đầy đủ response).
  - Bảo vệ phương thức: chỉ retry các HTTP method an toàn/idempotent (`GET`, `HEAD`, `PUT`, `DELETE`, `OPTIONS`), chặn retry tự động với `POST` để tránh sinh trùng dữ liệu.
- **Rà soát toàn diện (G3)**:
  - Toàn bộ **619 tests pass**, 0 failures, 0 errors, 0 skipped.
  - Mã nguồn tuân thủ nguyên tắc không hardcode credentials, che dấu payload nhạy cảm khi lưu (`[REDACTED]`), ngăn chặn exception dội dữ liệu nhạy cảm ra log, và kiểm soát ngân sách bộ nhớ JSON (10.000 nodes, depth 256).

## F3 — End-to-end Consumer Integration Test

Đã thêm bộ test tích hợp end-to-end `consumer/JobConsumerIntegrationTest` sử dụng máy chủ HTTP loopback cục bộ (`HttpServer`):
- Chuỗi thực thi thực tế: Gửi HTTP POST tạo user $\rightarrow$ so khớp status & JSONPath $\rightarrow$ trích xuất `userId` $\rightarrow$ thế biến gọi HTTP GET user chi tiết $\rightarrow$ lưu toàn bộ kết quả `TestResultEntity` an toàn đã qua redaction.
- Xác nhận cơ chế `stopOnFirstFailure`: dừng ngay khi bước trước lỗi và tự động gán trạng thái `SKIPPED` cho các bước sau mà không phát HTTP request.
- Xác nhận kiểm soát lỗi AMQP: ném `AmqpRejectAndDontRequeueException` khi gặp poison pill; lan truyền exception khi gặp lỗi cơ sở dữ liệu để phục vụ retry/requeue.
- Kiểm chứng mới nhất: **612 tests pass**, 0 failures/errors/skips.

## F2 — RabbitMQ Consumer & Job Execution Service

Đã tích hợp RabbitMQ consumer và pipeline xử lý job:
- `config/RabbitMQConfig`: Khai báo queue `test.execution.queue`, direct exchange `test.exchange`, binding key `test.execution.key` và `Jackson2JsonMessageConverter`.
- `config/WorkerRunnerConfig`: Đăng ký `WebClientHttpRunner` bean.
- `service/JobExecutionService`: Nối `TestExecutionJob` $\rightarrow$ `SequentialExecution` $\rightarrow$ `TestResultStore` (lưu kết quả từng step).
- `consumer/JobConsumer`: Lắng nghe message qua `@RabbitListener`; phân biệt lỗi cấu hình/payload không hợp lệ (ném `AmqpRejectAndDontRequeueException` để tránh poison pill lặp lại) và lỗi hạ tầng tạm thời (lan truyền để retry/requeue).
- Kiểm chứng mới nhất: **608 tests pass**, 0 failures/errors/skips.

## F1 — Job model và contract validation

Đã thêm model `model/TestExecutionJob` gồm các records `Environment`, `Settings`, `JobTestCase` khớp 100% với `docs/api-contract/execution-contract.md`. DTO bảo đảm tính bất biến, sao chép phòng thủ biến động bằng `ModelSnapshots`, tự áp dụng cấu hình mặc định (timeout 10s, concurrency 1, maxRetries 0, stopOnFirstFailure false) và kiểm tra tính duy nhất của ID/orderIndex. Cung cấp adapter `toSteps()` để chuyển đổi trực tiếp sang `SequentialExecution.Step`. Fixture `test-execution-job.json` và 10 unit tests trong `TestExecutionJobTest` xác nhận tính toàn vẹn serialization/deserialization. Toàn worker: **601 tests pass**, 0 failures/errors/skips.

## E2 — Persistence adapter (chưa nghiệm thu PostgreSQL)

Đã thêm `persistence/TestResultEntity`, `TestResultRepository`, `TestResultStore` bằng Spring Data JPA/Hibernate hiện có, không thêm dependency hoặc sửa migration. Ánh xạ đúng các cột V1 `test_results`: UUID run/case, enum status dạng string, status/latency, ba JSONB snapshots/assertion_details, error_message, created_at. `executedAt` từ mapper được lưu vào `created_at`; schema không lưu name/testType riêng. FK do PostgreSQL kiểm soát, không tạo entity backend hay sửa counters/lifecycle test_runs.

Store nhận raw StepResult và map redaction trước repository; saveAndFlush trong transaction, không publish queue, không retry hoặc swallow lỗi DB. Đây là API blocking: caller phải chạy ngoài WebClient event loop. Mỗi lần gọi dùng UUID row mới; schema chưa có execution/attempt unique key nên **không idempotent khi redelivery hoặc retry sau uncertain commit**. Chưa nối consumer/runtime tới store, chưa quyết định recovery/idempotency với nhóm.

Kiểm chứng mới nhất: **591 tests pass**, 0 failures/errors/skips; 3 unit tests mới dùng Mockito repository, không phải PostgreSQL integration. Không tìm thấy docker/psql trong PATH ở môi trường hiện tại. E2 còn mở: cần chạy schema migration của backend trên PostgreSQL test riêng, kiểm tra Hibernate validate/JSONB/null/timestamptz/FK/rollback; chưa kiểm chứng startup context. Không dùng H2 để tuyên bố JSONB đã pass.

## Trạng thái D sau review

Mới nhất: **587 tests pass**, 0 failures/errors/skips (43 runtime). `VariableResolver` mặc định giới hạn mỗi output text value ở **1.000.000 UTF-16 code units**, có constructor nhận limit dương cho caller dùng riêng; SequentialExecution hiện dùng mặc định, chưa wiring setting/env. Kiểm tra literal/replacement trước append, áp dụng cả string trong object/array chèn nguyên biến. Không tính JSON property names, không giới hạn numeric serialization, không tính HTTP encoded bytes hoặc tổng số ký tự cả request/job. Scalar numeric `asText()` vẫn cấp phát trước kiểm tra; dữ liệu đầu vào vẫn đã tồn tại trước resolver. Vượt limit → ERROR trước HTTP; stop-on-first-failure → SKIPPED bước sau. Không log template/value trong lỗi.

Cập nhật mới nhất: **584 tests pass**, 0 failures/errors/skips; runtime 40 tests. Resolver giới hạn output JSON ngay khi dựng cây: tổng 10.000 node occurrences cho body và tất cả query values trong một lần resolve, độ sâu tối đa 256 tại vị trí chèn biến. Query map/key không tính là JSON nodes; body Java null không tính node. Budget riêng mỗi call, không rò qua lỗi hoặc concurrent calls. Vượt giới hạn là configuration/resolve error (D3 mapping ERROR), không truncate payload. Một snapshot biến hợp lệ (tối đa 10.000 nodes) vẫn có thể được copy tạm trước kiểm tra budget còn lại; không giữ toàn bộ output vượt budget.

Đây chưa phải memory/ingress budget toàn diện: chưa giới hạn số ký tự của scalar/text/URL/header, số biến/context hoặc tổng kích thước job. Các giới hạn node/depth không chứng minh chống OOM với dữ liệu không tin cậy.

D1–D3 là **lõi runtime tuần tự**, chưa phải worker end-to-end. Đã review và đóng khoảng trống defensive copy: context và extraction dùng chung JSON snapshot validation (10.000 nodes/depth 256, reject non-JSON/cyclic trees). Extraction kiểm tra cả document trước JSONPath, nhưng chỉ sau deserialize, không thay thế ingress byte limits.

Regression mới nhất: **581 tests pass**, 0 failures/errors/skips, trong đó 37 runtime tests. Có kiểm tra cập nhật nguyên tử, extraction failure không ghi đè một phần, concurrent subscription isolation và cancellation xuống publisher của runner. Chưa chứng minh cancellation qua broker, retry, cấu hình job concurrency, persistence hoặc tổng resource budget sau nội suy. Các mốc 565/576 phía dưới là lịch sử trước review này.

## E1 — Result mapping (đang triển khai)

Kiểm chứng E1 mới nhất: **588 tests pass** toàn worker, 0 failures/errors/skips; result mapper có 12 invocations. HTTP loopback thật → WebClient → runtime → mapper → JSON round-trip kiểm tra PASSED/FAILED/extraction ERROR/SKIPPED, redaction và số request không tăng cho bước skip. `executedAt` ép ISO-8601 string ngay cả khi mapper bật timestamp mặc định; snapshot vắng mặt chuẩn hóa JSON null/Java null để round-trip nhất quán. Đây chưa phải lưu PostgreSQL hoặc nhận job RabbitMQ, chưa chứng minh tương thích UI với chính sách ẩn toàn bộ payload.

`TestResultMapper` chuyển `SequentialExecution.StepResult` thành DTO 12 field của result contract, giữ PASSED/FAILED/ERROR/SKIPPED và status/latency đã biết. `executedAt` là thời điểm mapping từ Clock được inject; caller cần map ngay khi step hoàn tất nếu cần timestamp sát thực thi.

Chính sách mặc định hiện tại là **ẩn toàn bộ payload**, không phải field-level redaction: URL/body/expected/actual thành `[REDACTED]`, headers rỗng, message cố định. Áp dụng cả JSON/text/empty/partial body, không thay đổi request thật hoặc extraction context. Metadata name/testType/ruleId/type là dữ liệu job, không được đưa secrets vào các field này. DTO constructor không tự redaction; chỉ dùng mapper tại ranh giới output. Không log raw StepResult/request/response.

Chưa wiring publisher/PostgreSQL; chưa có diagnostic payload opt-in cho UI. E1 còn mở cho review chính sách này trước tích hợp; E2/F chưa triển khai.

Module này thuộc **Member 2**, có trách nhiệm mục tiêu sau (không phải mọi mục đã được tích hợp):
- Lắng nghe Message Queue từ RabbitMQ (`test.execution.queue`).
- Thay thế các biến động (`{{token}}`, `{{userId}}`).
- Gửi HTTP Request đến Target REST API.
- Đo thời gian phản hồi (Latency).
- Kiểm tra tính đúng đắn với **Assertion Engine** (Status Code, JSON Schema, JSONPath).
- Trích xuất dữ liệu cho các bước tiếp theo (Dependency Chaining).
- Lưu kết quả hoặc trả về queue kết quả.

## Cấu trúc package dự kiến:
```text
worker/src/main/java/com/platform/worker/
├── consumer/        # RabbitMQ Listener (Nhận TestExecutionJob)
├── runner/          # HTTP Request Executor (WebClient / HttpClient)
├── assertion/       # Assertion Engine (Status, JSON Schema Validator, JSONPath)
├── context/         # Variable Interpolation & Runtime Context (Chaining)
└── model/           # DTOs khớp với docs/api-contract/
```

## Kế hoạch triển khai

Theo dõi các đầu việc nhỏ, trạng thái kiểm thử và bàn giao trong [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

Tiêu chuẩn chất lượng, các điểm cần rà lại và hướng dẫn tiếp tục mỗi phiên nằm trong
[REVIEW_PLAN.md](REVIEW_PLAN.md). Ưu tiên hiện tại là nghiệm thu A–C trước khi phát triển tiếp D; lõi D1–D3 từ lượt trước được giữ nguyên, chưa nối job pipeline.
API runner và ma trận test nằm trong [HTTP_RUNNER_DESIGN.md](HTTP_RUNNER_DESIGN.md).
`WebClientTransportProbeTest` chạy HTTP loopback thật bằng JDK HttpServer (port ngẫu nhiên),
không cần dependency mới; đây là probe thư viện, chưa phải kiểm thử runner production.

`WebClientHttpRunner` hiện có API reactive `execute(ResolvedHttpRequest, Duration)`:
không gửi trước subscribe, mỗi subscription là một request, không block trong runner.
Test loopback qua runner đã kiểm chứng GET/status/headers/body UTF-8, latency tới khi đọc xong body,
timeout trước headers và trong body. Không retry/redirect tự động; response cap 1 MiB,
không custom Host/Content-Length/Transfer-Encoding. Chưa đăng ký Spring bean hoặc nối job/listener.
`WebClientRequestWireTest` đã kiểm chứng request bytes JSON (kể cả string/null/absent/negative),
GET/POST/PUT/PATCH/DELETE/OPTIONS với body, HEAD không body, headers khác casing/nhiều giá trị,
và builder -> runner giữ raw encoded URI/query/dot segments trên loopback.
`WebClientResponseWireTest` kiểm chứng status 2xx/3xx/4xx/5xx không biến thành lỗi assertion,
empty/HEAD/204, redirect không follow, charset/fallback và biên giới hạn bytes.
Constructor `(ObjectMapper, int maxResponseBytes)` cho response cap dương (mặc định 1 MiB).
Bytes text lỗi/charset không hợp lệ báo decoding error, không thay bytes âm thầm; vẫn giữ status/headers.
Body là text view, không hỗ trợ binary/gzip decoding tự động.
`WebClientLifecycleWireTest` thêm 7 tests TCP loopback: refused/đóng kết nối, deadline chunks,
cancellation đóng socket và subscription isolation. Thêm 2 logging policy tests; toàn worker `clean test`: 164 tests pass.
`application.yml` mặc định tắt logger Netty/Spring reactive HTTP client vì exception có thể chứa raw header/URL.
Đây là suppression (mất diagnostic logs), không phải redaction; runner được nhúng ngoài Boot cần policy tương đương.
Không override bật wiretap/HTTP DEBUG hoặc log DTO với secrets thật. DTO giữ nguyên dữ liệu phục vụ assertion;
redaction khi lưu/log còn ở E1. Unit tests không tự nạp Boot config nên vẫn có WARN và marker giả của positive control.
Chưa kiểm chứng DNS/TLS/proxy/load/SSRF hoặc production security; không retry và chưa Spring bean wiring.

## Chạy test

Ma trận nghiệm thu A–C, sơ đồ luồng, giới hạn và lệnh tái hiện: [FOUNDATION_ACCEPTANCE.md](FOUNDATION_ACCEPTANCE.md).

Kết quả mới nhất sau gia cố A–C: **565 tests pass**, 0 failures/errors/skips (533 ngoài runtime + 32 runtime cũ). Các số 164/508/518/550 ở nhật ký phía dưới là baseline lịch sử. JSON model được kiểm tra trước defensive copy: tối đa 10.000 nodes, depth 256; từ chối Jackson non-JSON nodes và cây có chu kỳ. Schema response vượt structure budget là evaluation error, không schema FAIL. Chưa có ingress byte limits hoặc regex sandbox.

Yêu cầu JDK 21 và Maven có trong PATH. Từ thư mục gốc repository:

```powershell
mvn -pl worker -am test
```

Test nền tảng `WorkerApplicationTest` chỉ kiểm tra annotation cấu hình và chữ ký entry point;
không gọi `main`, không khởi động Spring context và không cần PostgreSQL/RabbitMQ.
Đây không phải test khởi động toàn bộ ứng dụng hay test tích hợp.

Repository hiện chưa có Maven Wrapper. Nếu lệnh `mvn` không được nhận diện,
cần chuẩn bị Maven trước khi có thể xác nhận compile hoặc JUnit thành công.

Trên máy triển khai hiện tại, đã chuẩn bị Maven portable 3.9.9 ngoài repository.
Từ thư mục gốc repository có thể chạy (không cần thay đổi PATH hệ thống):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12'
& "$env:LOCALAPPDATA\vinh-worker-tools\apache-maven-3.9.9\bin\mvn.cmd" -B -ntp -pl worker -am clean test
```

Đường dẫn JDK/Maven trên là cấu hình máy hiện tại, không phải yêu cầu đường dẫn cho máy khác.

## Assertion C1 hiện có

- `AssertionRule` dùng tên field theo TestCase contract, `AssertionResult` dùng `ruleId` theo TestResult contract; expected/actual giữ kiểu JSON và được copy sâu khi tạo/truy cập.
- `StatusCodeAssertion.evaluate(rule, response)` kiểm tra bằng nhau; expected phải là JSON integer 100–999, không tự ép chuỗi/decimal. Expected 400/actual 400 PASS, expected 400/actual 500 FAIL.
- Rule STATUS_CODE không có operator/jsonPath/expectedSchema. Rule khác chỉ được giữ cấu trúc trong model, chưa được evaluator này hỗ trợ; HEADER representation vẫn chưa chốt.
- Response có execution error (kể cả đã có status) bị từ chối bằng IllegalStateException an toàn; orchestration phải quyết định ERROR trước khi gọi evaluator. Chưa có engine tổng hợp/TestResult lifecycle hoặc Spring wiring.
- 35 tests C1; parent + worker `clean test` hiện có **199 tests pass**. Không coi boolean passed của một rule là trạng thái toàn TestResult.

## Assertion C2 hiện có

- `ResponseTimeAssertion.evaluate(rule, response)` dùng thời gian full-exchange runner đã đo theo milliseconds, không đo lại và không phụ thuộc clock trong evaluator.
- Chỉ hỗ trợ operator `LESS_THAN` theo contract: actual < expected. Với expected 1500ms: 1499 PASS, 1500 FAIL, 1501 FAIL. Không có mặc định operator hoặc tự đổi thành <=.
- Ngưỡng expected là JSON integer không âm trong miền long, không ép string/decimal; 0 hợp lệ nhưng không có thời gian không âm nào nhỏ hơn 0. Đây là chính sách validation C2; không thay đổi shared contract.
- Execution error bị từ chối bằng IllegalStateException an toàn như C1, bất kể thời gian đã đo; HTTP 400/500 hoàn chỉnh vẫn kiểm tra latency bình thường. Chưa tổng hợp TestResult.ERROR.
- 42 tests C2 xác định, không sleep; C1+C2+model 77 tests pass. Parent + worker `clean test`: **241 tests pass**, không failure/error/skip. Chưa engine/wiring; không thêm dependency.

## Assertion C3 hiện có

- `JsonPathAssertion` hỗ trợ EQUALS/NOT_NULL/CONTAINS/GREATER_THAN với root-based definite selectors (một giá trị), nested object/array, bracket notation và index âm. Chưa wildcard/filter/function; cấu hình không hỗ trợ bị từ chối, không âm thầm PASS.
- EQUALS giữ kiểu JSON: 18 khác "18", nhưng số 18 bằng 18.0; object/array so sánh sâu, array giữ thứ tự. Response dùng exact decimal parsing, expected giữ JsonNode caller truyền vào.
- Missing path/index FAIL, không coi là JSON null. EQUALS explicit null chỉ PASS khi tìm thấy JSON null thật; NOT_NULL FAIL với null/missing, nhưng PASS với false/0/empty string/array/object. Expected của NOT_NULL phải absent hoặc JSON null.
- JSON lỗi/empty/trailing tokens/duplicate keys -> assertion FAIL an toàn; rule/path sai -> lỗi cấu hình, execution error -> từ chối đánh giá. Message không echo path/body; actual vẫn là dữ liệu raw, không phải đã redaction.
- Jayway/Jackson có bước chuyển array index thiếu thành null; provider cục bộ kiểm tra index để ngăn false PASS. Không đổi Jayway global defaults, không thêm dependency/contract/engine wiring.
- Sau chỉnh readability C2 (39 tests, giữ các scenario cũ) và thêm 61 tests C3.1: parent + worker `clean test` **299 tests pass**, không failure/error/skip. Các số test 199/241 phía trên là bằng chứng lịch sử từng mốc.
- C3.2 CONTAINS: case-sensitive literal substring với string; deep equality membership với array, không subset/key lookup/recursive search. Empty substring PASS, empty array không chứa phần tử nào; explicit null có thể match phần tử null. String expected phải là string nếu actual là string, không coercion.
- C3.2 GREATER_THAN: strict numeric >, exact decimal, equal FAIL, không ép string/boolean. Expected phải finite JSON number (kể cả BigDecimal lớn hơn double range); actual sai kiểu FAIL. Cả hai operator cần expected, cấu hình thiếu/sai bị từ chối an toàn. Đây là semantics worker được công bố, không thay shared payload contract.
- **C3 hoàn thành trong phạm vi trên**: JSONPath 111 tests pass (61 baseline + 50 comparison). Baseline tại lượt C3: parent + worker **349 tests pass**; kết quả mới nhất xem mục C5–C6 bên dưới.

## Assertion C4 — HEADER

- Contract bổ sung `headerName` riêng cho HEADER, được người dùng cho phép; không dùng ké jsonPath/expected. Các rule cũ không có field này vẫn round-trip như trước; constructor 7 arguments được giữ. Generator/backend/UI cần dùng cùng contract khi tích hợp; chưa có kiểm chứng end-to-end các module đó.
- `HeaderAssertion` hỗ trợ EQUALS/CONTAINS (expected string, any-match), NOT_NULL (expected absent/null). HTTP field name là ASCII token, lookup không phân biệt casing; values case-sensitive, literal, không trim/comma split/join, kể cả Set-Cookie.
- Có ít nhất một value match là PASS; NOT_NULL nhận empty string là có mặt. Missing hoặc list rỗng FAIL; actual luôn array strings (missing -> []). Thu thập tất cả key cùng tên khác casing; thứ tự giữa map entries không đảm bảo.
- Cấu hình sai -> lỗi cấu hình, execution error -> từ chối đánh giá. Message không echo raw values; expected/actual chưa redaction. Không Spring wiring hoặc tổng hợp engine.
- 51 header tests + 1 model compatibility test mới; focused assertions/model 237 pass. Baseline tại lượt C4: parent + worker **401 tests pass**; kết quả mới nhất xem mục C5–C6 bên dưới.

## Assertion C5–C6 — JSON Schema và engine

- `JsonSchemaAssertion`: Networknt 1.5.1 có sẵn, JSON Schema Draft 7 mặc định; chỉ URI canonical `http://json-schema.org/draft-07/schema#` nếu khai báo. Schema object/boolean kiểm tra bằng meta-schema bundled; schema sai/keyword lạ/dialect khác là lỗi cấu hình. OpenAPI nullable phải chuyển thành type union trước khi gửi.
- Offline local JSON Pointer refs `#/...` (escaping ~0/~1), không cycle/$id/anchor/root #/percent-encoding; depth tối đa 64. External loader bị chặn kể cả HTTP/file/classpath; definitions chưa dùng vẫn được kiểm tra. Format assertions bật cho format thư viện nhận biết, format khác annotation. Chưa sandbox/time budget riêng cho regex/schema không tin cậy.
- Strict JSON body: malformed/empty/duplicate-key/trailing-token FAIL; JSON null được validate. Canonicalize số chính xác cho const/enum nested và uniqueItems (18 = 18.0), không ép chuỗi. Result mô tả cố định, actual valid/invalid, message không raw diagnostic/body.
- `AssertionEngine`: điều phối cả 5 loại, giữ input order và mọi PASS/FAIL; empty list explicit PASS, không suy ra HTTP 2xx. Null list/rule, id trống/trùng, type lạ hoặc rule sai là lỗi cấu hình; execution error/evaluation failure từ chối summary, không partial results. Summary nội bộ immutable, chưa TestResult DTO hoặc Spring wiring.
- **93 schema + 14 engine tests mới**; có HTTP loopback thật -> runner -> đủ 5 evaluator. Parent + worker `clean test`: **508 pass**, 0 failures/errors/skips. Chưa commit/push. Mốc D variables/extraction/chaining và E/F persistence/RabbitMQ còn mở; chưa sản phẩm end-to-end.

Review C1–C6 bổ sung 10 schema regression tests (tổng schema 103): decimal multipleOf, exact large bounds, nested uniqueItems, dependencies, invalid unused definitions và resource budgets. Evaluator chặn schema >10.000 JSON nodes/depth >256 trước recursive meta-validation/canonicalization, giữ schema/ref depth <=64; chặn mở rộng số integral quá 10.000 chữ số. Đây chưa phải budget ở tầng deserialize/model hoặc sandbox/time limit cho regex. Parent + worker clean test sau review: **518 pass**, 0 failures/errors/skips.

## Model HTTP hiện có

- `TestCaseRequest`: request template theo contract (`method`, `path`, `headers`, `pathParams`, `queryParams`, `body`). Map bị thiếu được chuẩn hóa thành map rỗng.
- `ResolvedHttpRequest`: input nội bộ cho runner; URI sẽ bao gồm path/query đã resolve, headers hỗ trợ nhiều giá trị.
- `HttpResponseData`: output nội bộ, giữ status, headers, body gốc dạng String, latency và thông tin lỗi. Lỗi transport không cần mã HTTP giả.
- Body/query dùng Jackson `JsonNode` chuẩn từ JSON parser: constructor và accessor đều copy sâu object/array, tránh sửa template qua tham chiếu bên ngoài. Accessor trả bản sao có thể sửa, không phải view vào model. Không cam kết ownership cho custom JsonNode/POJONode bọc Java object tùy ý.
- Java `null` body là không có body; `NullNode` là JSON `null`. Khi serialize template, body vắng mặt được bỏ qua, JSON `null` vẫn xuất hiện. Không thêm field contract.
- Map thiếu hoặc null trở thành map rỗng. Java null key/value/list element bị từ chối với tên trường, không đưa giá trị đầu vào vào lỗi; JSON null trong query vẫn được giữ dưới dạng NullNode.
- `ResolvedHttpRequest` yêu cầu method và URI HTTP(S) có host, không credentials/fragment/port không hợp lệ. Runner kiểm tra token method/header, control characters và từ chối custom authority/framing headers trước khi gửi. Không tự thêm Content-Type/Authorization hoặc sửa payload negative test.
- `HttpResponseData`: response hoàn chỉnh có status 3 chữ số, body String (rỗng nếu không có bytes), error null; lỗi transport có status/body null, headers rỗng và error không rỗng; lỗi đọc response giữ status/headers đã nhận. Model cho phép body partial nhưng runner hiện luôn trả body null khi đọc/decode/limit lỗi, không trả partial text. Latency không âm; hành vi runner đã kiểm chứng qua loopback tests.

Model chỉ validation cấu trúc nội bộ nêu trên, không kiểm tra business/schema payload, interpolation, URI encoding hoặc gửi HTTP.
Test model/fixture hiện chạy độc lập với PostgreSQL và RabbitMQ.

## Dựng URI (B2)

`RequestUriBuilder.build(baseUrl, request)` tạo URI, không gửi HTTP hoặc sửa request template.

- Chấp nhận HTTP/HTTPS base URL, giữ path/query có sẵn. Không chấp nhận credentials hoặc fragment trong base URL.
- `path` chỉ chứa đường dẫn API, không phải absolute URL và không chứa query/fragment. Query bổ sung đặt trong `queryParams`.
- Dấu `:` trong path như `/users:search` được hỗ trợ; chuỗi bắt đầu bằng scheme như `https:other` bị từ chối. Base URL parse lỗi không được đưa nguyên URL/cause có thể chứa credential vào exception.
- `{id}` lấy giá trị từ `pathParams`. Giá trị được encode như dữ liệu; `/`, `?`, `#`, `%` không được dùng để chèn cấu trúc URI.
- Static path có percent encoding hợp lệ được giữ nguyên; path/query parameter phải cung cấp giá trị chưa encode để tránh double encoding.
- Query scalar chuyển thành text; array scalar dùng key lặp lại (`tag=a&tag=b`). Null/mảng rỗng được bỏ qua; chuỗi rỗng giữ `key=`. Key mới được sắp xếp, query có sẵn giữ nguyên thứ tự và encoding.
- Query trùng key với base URL được nối thêm, không ghi đè; null trong array bị bỏ qua, empty key được giữ.
- Path parameter rỗng, `.`/`..` và static dot segments được giữ nguyên, không tự normalize để tránh sửa dữ liệu negative test. Percent trong tham số vẫn encode; `%2e` đầu vào trở thành `%252e`. Hành vi trên wire/client/server sẽ được kiểm chứng khi triển khai runner; builder không phải bộ chặn path traversal.
- Query object/nested array chưa được hỗ trợ; builder báo lỗi thay vì đoán serialization. Đây không phải bộ serialization đầy đủ cho mọi style/explode của OpenAPI.
- Thiếu path parameter, biến `{{...}}` chưa được thay hoặc URI không hợp lệ gây `IllegalArgumentException`. Runtime interpolation D1 chạy trước khi gọi builder.

Validation URI không thay thế chính sách chống SSRF/allowlist cho worker thực thi.

## Runtime và chaining D1–D3

`runtime/RuntimeContext`, `VariableResolver`, `ResponseExtractor` và `SequentialExecution` cung cấp lõi chạy tuần tự độc lập với broker. Tạo executor bằng `new SequentialExecution(runner::execute)` với `WebClientHttpRunner`; gọi `execute(baseUrl, variables, steps, timeout, stopOnFirstFailure)` trả `Mono<List<StepResult>>`. Mỗi subscription có context mới; snapshot input được giữ độc lập với caller.

- Resolve baseUrl/path/header/pathParams/query/body, giữ kiểu JSON khi toàn bộ giá trị là placeholder. Không resolve tên field hoặc method; không nội suy đệ quy giá trị biến.
- Chạy theo orderIndex không trùng; assertion FAIL không extraction; extraction lỗi không cập nhật một phần context. Stop-on-failure đánh dấu phần còn lại SKIPPED.
- Extraction BODY definite JSONPath hoặc HEADER `headerName` với đúng một raw value. Quy tắc chi tiết trong execution contract.
- Kết quả D3 là dữ liệu nội bộ raw, **không được publish/lưu trực tiếp** trước redaction và mapping TestResult. Chưa tích hợp listener, retry/concurrency/cancel, persistence hay dependency graph.
- Test HTTP loopback kiểm chứng Create → extract numeric ID → GET; unit tests kiểm tra biến thiếu, type preservation, malformed JSON, duplicate extraction, lỗi từng stage, stop/continue và isolation giữa các lần chạy.
- Kiểm chứng sau D1–D3: parent + worker `clean test` **550 pass**, 0 failures/errors/skips; 32 test runtime mới. Số 518 ở review C là baseline lịch sử, không phải kết quả mới nhất.
