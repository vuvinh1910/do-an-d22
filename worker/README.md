# Worker Service (Test Execution Engine)

Module này là một service độc lập chịu trách nhiệm bởi **Member 2**:
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
[REVIEW_PLAN.md](REVIEW_PLAN.md). Mốc B đã kiểm chứng trong phạm vi local HTTP; tiếp theo C1 — assertion models/result và STATUS_CODE.
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
- Thiếu path parameter, biến `{{...}}` chưa được thay hoặc URI không hợp lệ gây `IllegalArgumentException`. Runtime interpolation sẽ được triển khai ở D1, trước khi gọi builder.

Validation URI không thay thế chính sách chống SSRF/allowlist cho worker thực thi.
