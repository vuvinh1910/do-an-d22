# AI CODING AGENTS GUIDE (AGENTS.md)

Tài liệu này hướng dẫn cách các AI coding assistant (Cursor, Antigravity, Copilot, Claude Dev) làm việc trên repository này.

---

## 1. Nguyên tắc làm việc bất di bất dịch
1. **Không code vượt scope**: Chỉ làm đúng task được giao. Không tự ý refactor module khác.
2. **Tuân thủ API & Data Contract**: Mọi payload, model, DTO phải khớp 100% với tài liệu trong thư mục `docs/api-contract/`.
3. **Luôn có Unit Test**: Mỗi Service/Logic mới sinh ra phải có ít nhất 1 Unit Test tương ứng (JUnit 5 + Mockito cho Java, Vitest/Jest cho TS).
4. **Không hardcode credentials**: Sử dụng biến môi trường hoặc file config.

---

## 2. Phân chia trách nhiệm & Quy ước Module

### Người 1 — OpenAPI & Test Generation (`backend/src/.../openapi` & `backend/src/.../testgenerator`)
* **Nhiệm vụ**:
  * Parse file OpenAPI v3.0 / v3.1 (JSON/YAML) $\rightarrow$ `ApiOperation`
  * Thuật toán sinh Test Case: `VALID`, `BOUNDARY`, `NEGATIVE`, `TYPE_ERROR`, `MISSING_REQUIRED`, `AUTH`.
  * Quản lý dependency/chaining giữa các API step.
* **Output chuẩn**: Khớp định dạng `docs/api-contract/testcase-contract.md`.

### Người 2 — Test Execution Worker (`worker/`)
* **Nhiệm vụ**:
  * Đọc `TestExecutionJob` từ RabbitMQ queue `test.execution.queue`.
  * Thực thi HTTP Request (dùng WebClient / RestClient / HttpClient).
  * Assertion Engine: Kiểm tra status, JSON schema, headers, latency, JSONPath assertion.
  * Xuất kết quả `TestResult` về queue `test.result.queue` hoặc lưu trực tiếp.
* **Input/Output chuẩn**: 
  * Input: `docs/api-contract/execution-contract.md`
  * Output: `docs/api-contract/result-contract.md`

### Người 3 — Platform Backend & Web UI (`backend/.../platform` & `frontend/`)
* **Nhiệm vụ**:
  * Quản lý Project, Environment, Variables, Secrets.
  * Quản lý Test Suite, Test Run lifecycle (trigger, cancel, status).
  * Dashboard, Thống kê, Báo cáo (Pass rate, P95, Failure categorization).
  * Giao diện React + TypeScript + Vite.

---

## 3. Tech Stack Quy định
* **Backend & Worker**: Java 17+, Spring Boot 3.x, Spring Data JPA, Flyway, PostgreSQL Driver, Spring AMQP (RabbitMQ).
* **Frontend**: React 18+, TypeScript, Vite, Tailwind CSS hoặc Vanilla CSS + Component library hiện đại.
* **Database**: PostgreSQL 16 (Dùng `UUID` cho ID, `JSONB` cho dynamic schema/assertions/payloads).
* **Broker**: RabbitMQ 3.13 AMQP.

---

## 4. Cách yêu cầu AI thực hiện một Task
Khi giao việc cho AI IDE, hãy prompt theo mẫu:
```text
Task: [Tên task cụ thể, ví dụ: Viết OpenAPI YAML Parser]
Module: [backend/src/main/java/.../openapi]
Contract tham chiếu: [docs/api-contract/testcase-contract.md]

Yêu cầu:
1. Đọc kỹ file contract trước khi code.
2. Không sửa các module khác.
3. Tạo đầy đủ Unit Test cho case thành công và case thất bại.
4. Đảm bảo chạy `mvn test` pass.
```
