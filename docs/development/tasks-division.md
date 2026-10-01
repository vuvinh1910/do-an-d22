# BẢNG PHÂN CHIA NHIỆM VỤ 3 NGƯỜI (WORK BREAKDOWN STRUCTURE)

Tài liệu này là **bản giao việc chi tiết** cho 3 thành viên trong nhóm làm đồ án. Mỗi người chịu trách nhiệm trọn gói một mảng độc lập, có thể giao việc cho AI IDE theo từng task nhỏ.

---

## 🧭 TỔNG QUAN PHÂN VAI (3 VÙNG ĐỘC LẬP)

```text
┌─────────────────────────────────┐
│     NGƯỜI 1: SPEC & GEN         │
│  OpenAPI Import & Test Engine   │
│  Input : File OpenAPI JSON/YAML │
│  Output: TestCase DTO (List)    │
└────────────────┬────────────────┘
                 │ (docs/api-contract/testcase-contract.md)
                 ▼
┌─────────────────────────────────┐
│     NGƯỜI 2: EXECUTION          │
│   Worker & Assertion Engine     │
│  Input : TestCase DTO           │
│  Output: TestResult DTO         │
└────────────────┬────────────────┘
                 │ (docs/api-contract/result-contract.md)
                 ▼
┌─────────────────────────────────┐
│     NGƯỜI 3: PLATFORM & UI      │
│  Project/Env/Report & Dashboard │
│  Input : TestResult DTO         │
│  Output: UI Dashboard & Reports │
└─────────────────────────────────┘
```

---

## 👤 NGƯỜI 1 — OPENAPI & AUTOMATED TEST GENERATOR

> **Motto**: "Biến file OpenAPI thành hàng trăm Test Case có logic, dữ liệu biên và ràng buộc rõ ràng."

### 3 Chức năng lớn để bảo vệ đồ án:
1. **OpenAPI Parser**: Đọc JSON/YAML $\rightarrow$ bóc tách endpoints, HTTP methods, parameters, request/response schema, security.
2. **Automatic Test Generator**: Thuật toán sinh tự động các case: `VALID`, `BOUNDARY`, `NEGATIVE`, `TYPE_ERROR`, `MISSING_REQUIRED`, `AUTH`.
3. **API Dependency / Data Chaining**: Trích xuất dữ liệu từ Response step trước (`JSONPath`) truyền vào step sau (`{{variable}}`).

### Module phụ trách trong code:
* `backend/src/main/java/com/platform/openapi/`
* `backend/src/main/java/com/platform/testgenerator/`
* Nhánh Git: `member1/openapi-parser`, `member1/test-generator`

### 3 Task Ngày Đầu Tiên (Bắt đầu code ngay):
- [ ] **Task 1.1**: Viết `OpenApiImportService`: Nhận file `.json`/`.yaml` $\rightarrow$ dùng `swagger-parser` validate OpenAPI v3 $\rightarrow$ lưu raw vào bảng `api_specs`.
- [ ] **Task 1.2**: Viết `OpenApiParserService`: Duyệt qua `OpenAPI` object $\rightarrow$ bóc tách thành danh sách `ApiOperation` (path, method, query/path params, request body schema, response schema theo status 200/400).
- [ ] **Task 1.3**: Viết Unit Test cho Parser với 1 file mẫu (ví dụ Petstore hoặc OpenAPI của `sample-api`).

### Checklist giai đoạn tiếp theo (MVP & Nâng cao):
- [ ] **Task 1.4: Sinh Test Case VALID (Happy Path)**: Duyệt JSON Schema sinh payload chuẩn kiểu, đúng format, nằm trong min/max.
- [ ] **Task 1.5: Sinh Test Case BOUNDARY**: Giá trị min, max, min-1, max+1, chuỗi rỗng `""`, chuỗi vượt `maxLength`.
- [ ] **Task 1.6: Sinh Test Case MISSING_REQUIRED & TYPE_ERROR**: Lần lượt bỏ field required; truyền sai kiểu dữ liệu (chuỗi vào số, bool vào array).
- [ ] **Task 1.7: Sinh Test Case AUTH**: Bỏ header Authorization để test 401/403.
- [ ] **Task 1.8: API Chaining Setup**: Cho phép định nghĩa quy tắc trích xuất `extraction_rules` (JSONPath).
- [ ] **Task 1.9 (Sau MVP): AI Test Suggestion**: Dùng LLM đề xuất thêm các kịch bản logic nghiệp vụ đặc thù $\rightarrow$ lưu `ai_suggestions` chờ duyệt.

### 📝 Chuỗi Prompt tuần tự cho AI IDE của Người 1 (Làm từng bước, không làm gộp):

#### 🔹 Bước 1: Viết Parser bóc tách OpenAPI
```text
Task: Xây dựng OpenApiParserService bóc tách OpenAPI v3 thành ApiOperation
Module: backend/src/main/java/com/platform/openapi/
Contract tham chiếu: docs/database/schema.md (bảng api_specs và api_operations)

Yêu cầu:
1. Đọc kỹ file AGENTS.md. Không sửa các module khác.
2. Dùng thư viện swagger-parser (đã có trong pom.xml).
3. Viết Entity, Repository và Service nhận vào raw OpenAPI (JSON/YAML):
   - Parse và trích xuất: method, path, operationId, summary.
   - Parameters (path, query, header: name, required, type, min, max, enum).
   - RequestBody JSON Schema & Responses Schema theo status (200, 400...).
4. Lưu kết quả vào bảng api_operations.
5. Viết Unit Test với 1 đoạn OpenAPI YAML mẫu. Đảm bảo chạy `mvn test` pass.
```

#### 🔹 Bước 2: Viết Engine sinh Test Case VALID (Happy Path)
*(Chỉ prompt sau khi Bước 1 đã test xanh)*
```text
Task: Xây dựng TestGeneratorService sinh Test Case loại VALID (Happy Path)
Module: backend/src/main/java/com/platform/testgenerator/
Contract tham chiếu: docs/api-contract/testcase-contract.md

Yêu cầu:
1. Đọc kỹ docs/api-contract/testcase-contract.md.
2. Viết Service nhận vào một ApiOperation:
   - Dựa vào request_schema, tự động sinh dữ liệu mẫu hợp lệ 100% (chuỗi đúng format email/uuid, số trong khoảng min-max, giá trị đúng enum).
   - Sinh assertions mặc định: Kỳ vọng HTTP status 200/201 và đúng JSON Schema.
3. Đóng gói thành TestCase DTO và lưu vào bảng test_cases (test_type = 'VALID').
4. Viết Unit Test kiểm tra payload sinh ra khớp schema. Đảm bảo `mvn test` pass.
```

#### 🔹 Bước 3: Viết Engine sinh Test Case BOUNDARY & NEGATIVE
*(Chỉ prompt sau khi Bước 2 đã test xanh)*
```text
Task: Bổ sung sinh Test Case BOUNDARY, MISSING_REQUIRED, TYPE_ERROR và AUTH
Module: backend/src/main/java/com/platform/testgenerator/
Contract tham chiếu: docs/api-contract/testcase-contract.md

Yêu cầu:
1. Mở rộng TestGeneratorService để sinh thêm:
   - BOUNDARY: min-1, max+1, chuỗi rỗng "", chuỗi vượt maxLength (Kỳ vọng HTTP 400).
   - MISSING_REQUIRED: Lần lượt bỏ từng trường bắt buộc (Kỳ vọng HTTP 400).
   - TYPE_ERROR: Truyền chuỗi vào trường số (Kỳ vọng HTTP 400).
   - AUTH: Request không có Authorization header (Kỳ vọng HTTP 401/403).
2. Lưu các case này vào bảng test_cases với test_type tương ứng.
3. Viết Unit Test kiểm thử đầy đủ các kịch bản. Đảm bảo `mvn test` pass.
```

---

## 👤 NGƯỜI 2 — TEST EXECUTION & ASSERTION WORKER

> **Motto**: "Nhận Test Case $\rightarrow$ Bắn HTTP Request $\rightarrow$ So khớp Assertion $\rightarrow$ Xuất Test Result."

### 3 Chức năng lớn để bảo vệ đồ án:
1. **HTTP Test Runner**: Nhận TestCase, thế biến, bắn request (WebClient), đo latency, bắt toàn bộ header/body phản hồi.
2. **Assertion Engine**: Kiểm tra Status code (Kỳ vọng 400 nhận 400 $\rightarrow$ PASS), Response Schema (JSON Schema Validator), JSONPath, Response Time SLA.
3. **Async Worker + Queue + Concurrency**: Tiêu thụ Job từ RabbitMQ, timeout, retry, giới hạn concurrency.

### Module phụ trách trong code:
* `worker/` (Toàn bộ project Worker độc lập)
* Nhánh Git: `member2/http-runner`, `member2/assertion`, `member2/worker`

### 3 Task Ngày Đầu Tiên (Bắt đầu code ngay với Mock Data):
- [ ] **Task 2.1**: Viết `HttpRequestRunner`: Nhận một object `TestCaseRequest` $\rightarrow$ dùng Spring WebClient thực thi request $\rightarrow$ trả về `HttpResponseData` (status, headers, body string, responseTimeMs).
- [ ] **Task 2.2**: Viết `StatusCodeAssertion` & `ResponseTimeAssertion`: Kiểm tra HTTP Status thực tế có khớp `expected` không; kiểm tra `responseTimeMs <= expectedMaxMs`.
- [ ] **Task 2.3**: Viết Unit Test: Tạo mock HTTP server (WireMock hoặc MockWebServer) để test Runner và Assertion.

### Checklist giai đoạn tiếp theo (MVP & Nâng cao):
- [ ] **Task 2.4: Variable Interpolator**: Thay thế biến `{{variable}}` trong URL, Header, Body trước khi gửi request.
- [ ] **Task 2.5: JSON Schema Assertion**: Dùng `com.networknt.schema.JsonSchema` đối chiếu Response Body với schema mong đợi từ OpenAPI.
- [ ] **Task 2.6: JSONPath Assertion**: Dùng Jayway JsonPath kiểm tra các biểu thức `$.data.id` EQUALS / NOT_NULL.
- [ ] **Task 2.7: Format TestResult**: Đóng gói kết quả khớp 100% với `docs/api-contract/result-contract.md` và lưu vào PostgreSQL.
- [ ] **Task 2.8 (Sau MVP): RabbitMQ Listener**: Lắng nghe queue `test.execution.queue` nhận `TestExecutionJob` (theo `execution-contract.md`).
- [ ] **Task 2.9 (Sau MVP): Concurrency & Retry**: Giới hạn số request chạy đồng thời (bounded pool), tự động retry nếu timeout mạng.

### 📝 Chuỗi Prompt tuần tự cho AI IDE của Người 2 (Làm từng bước, không làm gộp):

#### 🔹 Bước 1: Viết HTTP Request Runner (WebClient)
```text
Task: Xây dựng HttpRequestRunnerService thực thi HTTP Request
Module: worker/src/main/java/com/platform/worker/
Contract tham chiếu: docs/api-contract/testcase-contract.md

Yêu cầu:
1. Đọc kỹ file AGENTS.md. Không sửa module backend hay frontend.
2. Dùng WebClient (Spring WebFlux) để viết Service nhận vào TestCaseRequest (method, url, headers, queryParams, body).
3. Thực thi request, đo thời gian phản hồi (responseTimeMs) chính xác.
4. Trả về HttpResponseData (statusCode, headers, body dạng String, responseTimeMs, error).
5. Viết Unit Test dùng MockWebServer kiểm tra các method GET, POST, timeout và bắt lỗi mạng.
```

#### 🔹 Bước 2: Viết Core Assertion Engine (Status, Latency, JSONPath)
*(Chỉ prompt sau khi Bước 1 đã test xanh)*
```text
Task: Xây dựng AssertionEngine kiểm tra Status Code, Latency và JSONPath
Module: worker/src/main/java/com/platform/worker/
Contract tham chiếu: docs/api-contract/testcase-contract.md và docs/api-contract/result-contract.md

Yêu cầu:
1. Viết AssertionService duyệt qua danh sách assertions của TestCase:
   - STATUS_CODE: Expected == Actual -> PASSED (Lưu ý: Expected 400 gặp Actual 400 là PASSED).
   - RESPONSE_TIME: responseTimeMs <= expectedMaxMs -> PASSED.
   - JSON_PATH: Dùng Jayway JsonPath kiểm tra trường theo operator (EQUALS, NOT_NULL).
2. Viết Unit Test kiểm tra các case assertion PASS và FAIL. Đảm bảo `mvn test` pass.
```

#### 🔹 Bước 3: Viết JSON Schema Assertion & Đóng gói TestResult
*(Chỉ prompt sau khi Bước 2 đã test xanh)*
```text
Task: Tích hợp JSON Schema Assertion và đóng gói TestResult DTO
Module: worker/src/main/java/com/platform/worker/
Contract tham chiếu: docs/api-contract/result-contract.md

Yêu cầu:
1. Dùng com.networknt.schema.JsonSchema để đối chiếu body response nhận được với expectedSchema.
2. Tổng hợp kết quả từ Runner và các Assertions thành TestResult DTO khớp 100% với docs/api-contract/result-contract.md.
3. Lưu kết quả vào bảng test_results trong PostgreSQL.
4. Viết Unit Test kiểm thử schema validation khi response hợp lệ và khi response thiếu field bắt buộc.
```

---

## 👤 NGƯỜI 3 — PLATFORM BACKEND, WEB UI & REPORT

> **Motto**: "Quản lý dữ liệu nền tảng, điều phối chạy test, vẽ giao diện chuyên nghiệp và demo thực nghiệm."

### 3 Chức năng lớn để bảo vệ đồ án:
1. **Project + Environment + Auth + Variables**: Quản lý Dev/Staging/Prod, Base URL, Auth Tokens (Bearer/API Key), biến bí mật (Secret).
2. **Test Run + History Lifecycle**: Tạo lần chạy (QUEUED $\rightarrow$ RUNNING $\rightarrow$ COMPLETED), xem lịch sử, rerun, xem chi tiết từng test fail.
3. **Analytics / Report Dashboard**: Thống kê tổng số test, Pass Rate (%), P95/P99 latency, phân loại lỗi theo endpoint và loại lỗi.

### Module phụ trách trong code:
* `backend/src/main/java/com/platform/project/`
* `backend/src/main/java/com/platform/environment/`
* `backend/src/main/java/com/platform/testrun/`
* `backend/src/main/java/com/platform/report/`
* `frontend/` (React + TypeScript + Vite)
* `sample-api/` (Spring Boot API phục vụ demo thực nghiệm)
* Nhánh Git: `member3/project`, `member3/environment`, `member3/report`

### 3 Task Ngày Đầu Tiên (Bắt đầu code ngay):
- [ ] **Task 3.1**: Backend CRUD REST API cho `Project` và `Environment` (Dev, Staging, Prod kèm Base URL).
- [ ] **Task 3.2**: Backend CRUD cho `EnvironmentVariables` (lưu key-value, đánh dấu `is_secret`).
- [ ] **Task 3.3**: Frontend Shell: Xây dựng giao diện danh sách Project và màn hình cấu hình Biến môi trường.

### Checklist giai đoạn tiếp theo (MVP & Nâng cao):
- [ ] **Task 3.4: Test Cases & OpenAPI Explorer UI**: Màn hình xem cây API endpoints và danh sách Test Case đã sinh.
- [ ] **Task 3.5: Test Run Trigger & History API**: API tạo Test Run, lưu trạng thái, API lấy danh sách kết quả `TestResult`.
- [ ] **Task 3.6: Test Run Detail UI**: Màn hình chi tiết một lần chạy: Xem danh sách pass/fail, click vào xem Request/Response diff.
- [ ] **Task 3.7: Dashboard Báo cáo**: Biểu đồ Pass/Fail rate, P95/P99 latency, thống kê Endpoint nào nhiều bug nhất.
- [ ] **Task 3.8: Xây dựng `sample-api`**: Tạo 1 API chuẩn có OpenAPI, cố tình cài 3-5 lỗi (sai schema, thiếu validate min/required) để demo thực nghiệm.

### 📝 Chuỗi Prompt tuần tự cho AI IDE của Người 3 (Làm từng bước, không làm gộp):

#### 🔹 Bước 1: Backend CRUD Project, Environment & Variables
```text
Task: Xây dựng CRUD API cho Project, Environment và EnvironmentVariables
Module: backend/src/main/java/com/platform/project/ và backend/src/main/java/com/platform/environment/
Contract tham chiếu: docs/database/schema.md (bảng projects, environments, environment_variables)

Yêu cầu:
1. Đọc kỹ file AGENTS.md. Không sửa module openapi hay worker.
2. Tạo JPA Entity, Repository, Service, DTO và REST Controller cho:
   - Project: CRUD, validate name không rỗng.
   - Environment: Thuộc về Project, gồm name (Dev/Staging), baseUrl, isDefault.
   - EnvironmentVariables: Key, Value, isSecret (nếu isSecret=true thì mask giá trị khi get).
3. Viết MockMvc Unit Test cho các API. Đảm bảo chạy `mvn test` pass.
```

#### 🔹 Bước 2: Frontend Shell & Quản lý Project/Environment
*(Chỉ prompt sau khi Bước 1 đã test xanh)*
```text
Task: Xây dựng giao diện React Quản lý Project và Cấu hình Môi trường
Module: frontend/src/
Contract tham chiếu: REST API từ Bước 1

Yêu cầu:
1. Dùng React 18 + TypeScript + Lucide icons. Sử dụng CSS variables hiện có trong index.css.
2. Tạo trang danh sách Projects (Card layout, tạo mới, chỉnh sửa, xóa).
3. Tạo trang cấu hình Environment: Chọn Dev/Staging, nhập baseUrl, bảng thêm/xóa biến môi trường (hỗ trợ toggle ẩn/hiện password cho secret key).
4. Kết nối API backend qua Axios service.
```

#### 🔹 Bước 3: Test Run Lifecycle & Màn hình Báo cáo kết quả
*(Chỉ prompt sau khi Người 2 đã có TestResult)*
```text
Task: Xây dựng API TestRun và Màn hình chi tiết kết quả chạy kiểm thử
Module: backend/src/main/java/com/platform/testrun/ và frontend/src/
Contract tham chiếu: docs/api-contract/result-contract.md

Yêu cầu:
1. Backend: Viết API POST /api/v1/test-runs (tạo run), GET /api/v1/test-runs/{id}/results (lấy danh sách kết quả).
2. Frontend: Màn hình hiển thị tiến độ và kết quả:
   - Thanh tiến độ (Pass/Fail rate bar).
   - Bảng danh sách Test Case kèm badge PASSED / FAILED.
   - Modal hiển thị chi tiết khi click vào 1 test case: Xem Request đã gửi, Response nhận về và Assertion nào bị lỗi.
```

---

## 🚀 QUY TẮC PHỐI HỢP NHÓM & TIẾN ĐỘ

```text
TUẦN 1 - 2: MVP ĐỒNG BỘ (Cả 3 code bằng Mock Data)
├── Người 1: Ingest OpenAPI -> Extract Operation -> Sinh Test VALID
├── Người 2: Dùng Mock TestCase -> Viết Runner -> Status & Latency Assertion
└── Người 3: CRUD Project/Env -> UI Quản lý -> Render Mock Result ra bảng

TUẦN 3 - 4: TÍCH HỢP HOÀN CHỈNH (E2E PIPELINE)
├── Ghép nối Người 1 -> Người 2 -> Người 3
├── Kích hoạt RabbitMQ Worker
└── Chạy thử trên sample-api phát hiện lỗi đầu tiên

TUẦN 5 - 6: TÍNH NĂNG ĂN ĐIỂM & BẢO VỆ
├── Boundary & Negative Testing đầy đủ (Người 1)
├── JSON Schema Assertion & Chaining (Người 2)
├── Dashboard P95, Failure Classification & UI trau chuốt (Người 3)
└── Viết Báo cáo đồ án & Slide demo
```
